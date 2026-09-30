package com.xjtu.toolbox.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.xjtu.toolbox.data.SecurePrefs
import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 判断手机现在能不能直连校内网。三路同时发，谁先给出确定结论就用谁：
 *
 * 1. **内网探针**：几台只在校内可达的机器，任意一台连上就是校内（开着学校 VPN 也算，本来就能直连）。
 *    为什么不止一台：过去只探旧考勤 bkkq，它停用后探针恒为 false，人在宿舍也被判成校外。
 *    - 图书馆座位 `rg.lib.xjtu.edu.cn:8086`：非标准端口不对公网开放；
 *    - 快速考勤流水 iclassface、考勤 kq：校外连不上。
 *    教务、ehall、思源学堂都已公网直连，拿它们探恒为 true，不能用。
 * 2. **服务器判定**：学校超级 App 用的一网通办 `networkCheck`，服务端按来源 IP 判校内外，
 *    要带一网通办令牌。必须直连发：绕 WebVPN 的话服务端看到的是网关的校内地址。
 *    连着 Wi-Fi 时，说「校外」后再给探针一小段宽限，防校园网 IPv6 之类服务端不认的地址被误判。
 * 3. **公网可达**：没令牌时同一个请求也能说明公网通不通，用来区分「在校外」和「根本没网」。
 *
 * 校外时探针要等满超时才算失败，有服务器判定就不用干等。
 */
object CampusProbe {
    private const val TAG = "Campus"

    private val PROBE_URLS = listOf(
        "http://rg.lib.xjtu.edu.cn:8086/",
        "https://iclassface.xjtu.edu.cn/",
        "https://kq.xjtu.edu.cn/",
    )
    private const val CHECK_URL = "https://ywtb.xjtu.edu.cn/portal-api/v2/service/networkCheck"

    private const val PREFS = "campus_probe"
    private const val KEY_TOKEN = "ywtb_token"
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    @Volatile private var token: String? = null
    @Volatile private var tokenLoaded = false

    /**
     * 最近一次一网通办登录拿到的令牌，加密存盘，重启和站点失效都不丢——一网通办平时不一定
     * 登录，只靠内存的话切网时多半没有。networkCheck 不看是谁。令牌是 JWT、8 小时有效，
     * 过期的当没有；服务器说没权限也作废，退回靠探针。
     */
    var ywtbToken: String?
        get() {
            if (!tokenLoaded && ::app.isInitialized) {
                token = runCatching { SecurePrefs.open(app, PREFS).getString(KEY_TOKEN, null) }.getOrNull()
                tokenLoaded = true
            }
            return token?.takeUnless { Jwt.isExpired(it, System.currentTimeMillis()) }
        }
        set(value) {
            token = value
            tokenLoaded = true
            if (!::app.isInitialized) return
            runCatching {
                SecurePrefs.open(app, PREFS).edit().apply { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }.apply()
            }
        }

    /** 服务器说校外后，再等探针多久。校内探针一般几十毫秒就回。 */
    private const val GRACE_MS = 800L

    /**
     * 服务器认不出的校园网地址（IPv6 之类）只会出现在 Wi-Fi 上；只走流量又没开 VPN 时
     * 不可能在校园网里，服务器说校外就是校外，不再干等。
     */
    private fun graceMs(): Long {
        if (!::app.isInitialized) return GRACE_MS
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return GRACE_MS
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return GRACE_MS
        val cellularOnly = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return if (cellularOnly) 0L else GRACE_MS
    }

    enum class Server { ON, OFF, REACHABLE, UNREACHABLE }

    sealed interface Signal {
        data class Probe(val ok: Boolean) : Signal
        data class Check(val answer: Server) : Signal
    }

    /**
     * @param onCampus 能否直连校内网
     * @param strong 有直接证据（探针连上 / 服务器明确回答）；只凭「探针全连不上」得出的校外是弱结论
     * @param offline 公网和内网都不通，手机这会儿没网，结论不可信
     */
    data class Verdict(val onCampus: Boolean, val strong: Boolean, val offline: Boolean = false, val why: String)

    private val client by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * 请求不挂在调用方的协程下：阻塞中的 OkHttp 请求取消协程打断不了，挂在下面的话一有结论
     * 也得等剩下的请求超时才能返回。有结论就直接取消底层请求、不等它们。
     */
    suspend fun detect(ywtbToken: String?): Verdict {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val calls = PROBE_URLS.map { client.newCall(Request.Builder().url(it).head().build()) }
        val check = client.newCall(
            Request.Builder().url(CHECK_URL).get().apply { if (ywtbToken != null) header("x-id-token", ywtbToken) }.build(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        calls.forEach { call -> scope.launch { signals.trySend(Signal.Probe(reach(call))) } }
        scope.launch { signals.trySend(Signal.Check(ask(check, ywtbToken))) }
        return try {
            decide(signals, calls.size, graceMs()).also { Log.d(TAG, "probe: $it (token=${ywtbToken != null})") }
        } finally {
            (calls + check).forEach { it.cancel() }
            scope.cancel()
        }
    }

    /** 按信号到达顺序下结论，和网络无关，便于单测。 */
    internal suspend fun decide(signals: ReceiveChannel<Signal>, probeCount: Int, graceMs: Long = GRACE_MS): Verdict {
        var failed = 0
        var server: Server? = null
        var graceUntil = 0L
        while (true) {
            val signal = if (server == Server.OFF) {
                withTimeoutOrNull((graceUntil - System.currentTimeMillis()).coerceAtLeast(1)) { signals.receive() }
                    ?: return Verdict(false, strong = true, why = "服务器判定校外")
            } else {
                signals.receive()
            }
            when (signal) {
                is Signal.Probe -> if (signal.ok) return Verdict(true, strong = true, why = "内网探针可达") else failed++
                is Signal.Check -> {
                    server = signal.answer
                    if (signal.answer == Server.ON) return Verdict(true, strong = true, why = "服务器判定校内")
                    if (signal.answer == Server.OFF) graceUntil = System.currentTimeMillis() + graceMs
                }
            }
            if (failed == probeCount && server != null) {
                return when (server) {
                    Server.OFF -> Verdict(false, strong = true, why = "服务器判定校外，探针也连不上")
                    Server.UNREACHABLE -> Verdict(false, strong = false, offline = true, why = "公网内网都不通")
                    else -> Verdict(false, strong = false, why = "内网探针全连不上")
                }
            }
        }
    }

    private fun reach(call: Call): Boolean = try {
        call.execute().use { it.code < 500 }
    } catch (_: Exception) {
        false
    }

    private fun ask(call: Call, token: String?): Server = try {
        call.execute().use { resp ->
            val body = resp.body.string()
            parseCheck(body).also {
                if (token != null && it == Server.REACHABLE) {
                    Log.d(TAG, "networkCheck 无结论：${resp.code} ${body.take(120)}")
                    // 令牌过期：别再带着它反复问
                    if ("权限" in body && ywtbToken == token) ywtbToken = null
                }
            }
        }
    } catch (_: Exception) {
        Server.UNREACHABLE
    }

    /** 成功时 `{"code":0,"data":true}`；没权限、令牌过期、网关报错页等只说明公网通。 */
    internal fun parseCheck(body: String): Server {
        val json = runCatching { body.safeParseJsonObject() }.getOrNull() ?: return Server.REACHABLE
        if ((json["code"] as? JsonPrimitive)?.intOrNull != 0) return Server.REACHABLE
        val data = json["data"] as? JsonPrimitive ?: return Server.REACHABLE
        val on = data.booleanOrNull ?: data.intOrNull?.let { it != 0 } ?: return Server.REACHABLE
        return if (on) Server.ON else Server.OFF
    }
}
