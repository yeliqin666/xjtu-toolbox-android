package com.xjtu.toolbox.newattendance

import android.util.Log
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SafetyVerifyRequiredException
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

/**
 * 新版考勤（kq.xjtu.edu.cn/sa）登录。
 *
 * 入口走 CAS，回调带 `loginRequestId` + `ticket`，再 POST `/sa/auth/cas/exchange`
 * 换成业务令牌。令牌不能放在带初始化器的子类字段里：[XJTULogin] 构造期间就会调
 * [postLogin]，子类属性初始化器随后会把值冲掉。
 */
class NewAttendanceLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
    /**
     * 走 WebVPN 网关而非直连。
     *
     * 考勤这几个域名只在校内网络可达（校外直连 443 端口连超时都不给，直接 12 秒卡死）。
     * 以前 [com.xjtu.toolbox.auth.NewAttendanceSession] 写的是 `mustUseWebVpn = false`，
     * 被永久锁在直连，于是校外「死活打不开」。旧考勤一直是走网关的。
     */
    private val useWebVpn: Boolean = false,
    /**
     * 已知的账号类型（本科/研究生）时，直接去对应业务站（bk-kq / yjs-kq）的 CAS
     * 入口登录，跳过"先登门户、门户答复 systemSelectionRequired、再选子系统重登
     * 一遍"这三次往返。故意不存成子类属性——本类 postLogin 在 super() 构造期间
     * 就会被调用，那时子类属性初始化器还没跑（见 [authToken] 处的说明），只能在
     * 这里、也就是能确定被用来拼 super() 入参的这一次，用一次就完。之后是否走了
     * 直连、走的是哪个 host，postLogin 改从 response 的原始请求 URL 反查
     * （见 [initialHost]），不依赖这个参数本身。
     */
    knownAccountType: XJTULogin.AccountType? = null,
) : XJTULogin(proxied(entryUrlFor(knownAccountType), useWebVpn), session, visitorId, cachedRsaKey) {

    /** 本实例的地址改写。网关地址只在发请求时拼，存下来的基址始终是原始域名。 */
    private fun via(url: String): String = proxied(url, useWebVpn)

    var authToken: String?
        get() = tokens[this]
        private set(value) {
            if (value.isNullOrBlank()) tokens.remove(this) else tokens[this] = value
        }

    /**
     * 这个账号实际所在的考勤站点根地址。
     *
     * 考勤现在是**三个域名**：`kq.xjtu.edu.cn` 是门户，本科业务在 `bk-kq.xjtu.edu.cn`，
     * 研究生业务在 `yjs-kq.xjtu.edu.cn`。门户自己不签业务令牌，它的交换接口回的是
     * `systemSelectionRequired: true`，要再去对应业务站各走一遍 CAS（见 [selectBusinessSystem]）。
     *
     * 路径前缀三边一致（都是 `/sa`），变的只是主机，所以只认落地页的 host。
     */
    var resolvedBaseUrl: String?
        get() = bases[this]
        private set(value) {
            if (value.isNullOrBlank()) bases.remove(this) else bases[this] = value
        }

    /** 交换与探活都要打到 [resolvedBaseUrl]；还没解析出来时退回默认。 */
    private val baseUrl: String get() = resolvedBaseUrl ?: BASE_URL

    private val jsonType = "application/json".toMediaType()
    private val reAuthLock = Any()

    override fun postLogin(response: Response) {
        // 这一轮到底打的是直连业务站还是门户，从 response 的原始请求链反查——
        // 不能读构造参数（此刻子类属性初始化器还没跑，见类头 knownAccountType 的说明）。
        val intendedHost = initialHost(response)
        val wasDirect = intendedHost != null && !intendedHost.equals(HOST, ignoreCase = true)
        try {
            if (consumeLanding(response.request.url, lastResponseBody, intendedHost = intendedHost)) return
        } catch (e: Exception) {
            if (!wasDirect) throw e
            // 直连猜错了账号类型（比如本科账号被当成研究生去敲 yjs-kq）最常见的表现
            // 就是交换直接报错——门户会自己认出真实身份，退回去让它选。
            Log.w(TAG, "直连业务站($intendedHost)登录失败，退回门户流程：${e.message}")
            fallbackToPortal()
            return
        }
        if (wasDirect) {
            // 落地了但没给票据：同样退回门户，让它自己判断该进哪个子系统。
            Log.w(TAG, "直连业务站($intendedHost)未取得 CAS 回调票据，退回门户流程")
            fallbackToPortal()
            return
        }
        // 门户流程：重来一次只为了「这一轮压根没拿到票」的情况。
        // 拿到票但交换失败时 consumeLanding 会直接抛，不会走到这里——
        // 那种情况下再跑一遍会带着已经被消费掉的 ticket，
        // 换回一句「CAS登录浏览器绑定无效或已失效」，把真正的失败原因盖掉。
        fallbackToPortal()
    }

    private fun fallbackToPortal() {
        client.newCall(Request.Builder().url(via(LOGIN_URL)).get().build()).execute().use { retry ->
            val body = retry.body?.string().orEmpty()
            if (!consumeLanding(retry.request.url, body)) {
                throw RuntimeException("考勤系统登录失败：未取得 CAS 回调票据")
            }
        }
    }

    /**
     * 沿着 OkHttp 的 [Response.priorResponse] 链条找回这一轮最初打的是哪个 host
     * （WebVPN 网关地址会先还原成明文域名）。用来判断走的是直连业务站还是门户，
     * 不依赖任何子类属性。
     */
    private fun initialHost(response: Response): String? {
        var r = response
        while (r.priorResponse != null) r = r.priorResponse!!
        val url = r.request.url
        val plain = com.xjtu.toolbox.util.WebVpnUtil.getOriginalUrl(url.toString())?.toHttpUrlOrNull() ?: url
        return plain.host.takeIf { it.isNotBlank() }
    }

    override fun validateLogin(): Boolean {
        val token = authToken ?: return false
        return try {
            client.newCall(
                Request.Builder()
                    .url(via("$baseUrl/student/home"))
                    .header(TOKEN_HEADER, token)
                    .header(SYSTEM_HEADER, SYSTEM_VALUE)
                    .get()
                    .build()
            ).execute().use { resp ->
                if (resp.code != 200) return false
                val body = resp.body?.string() ?: return false
                if (isAuthFailureResponse(body)) return false
                body.safeParseJsonObject().get("code")?.takeIf { !it.isJsonNull }?.asInt == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun keepAlive(): KeepAliveStatus {
        return try {
            when {
                validateLogin() -> KeepAliveStatus.VALID
                reAuthenticate() -> KeepAliveStatus.REAUTH_OK
                else -> KeepAliveStatus.AUTH_INVALID
            }
        } catch (_: IOException) {
            KeepAliveStatus.NETWORK_ERROR
        } catch (_: SafetyVerifyRequiredException) {
            KeepAliveStatus.AUTH_INVALID
        } catch (_: Exception) {
            KeepAliveStatus.ERROR
        }
    }

    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        val pair = casAuthenticate(via(LOGIN_URL)) ?: return false
        val parsed = pair.second.toHttpUrlOrNull()
        if (parsed != null && consumeLanding(parsed, pair.first)) return true
        if ("/cas/callback" !in pair.second) return false
        client.newCall(Request.Builder().url(pair.second).get().build()).execute().use { postLogin(it) }
        !authToken.isNullOrBlank()
    }

    /**
     * @param intendedHost 已知这一轮 CAS 就是打给这个业务站时传入（[loginAt] 直连、
     * 或 [postLogin] 判断出这轮是直连业务站），[rememberBase]/[normalizeRedirect]
     * 就不用再去猜落地页的 host——落地页有时会变成内网 IP（202.117.22.36），猜的话
     * 只会猜回门户，而门户不签业务令牌，交换必然失败。
     */
    private fun consumeLanding(url: HttpUrl, body: String, hops: Int = 0, intendedHost: String? = null): Boolean {
        if (hops > 2) return false
        val normalized = normalizeRedirect(url, intendedHost)
        val loginRequestId = normalized.queryParameter("loginRequestId")?.trim()
        val ticket = normalized.queryParameter("ticket")?.trim()
        rememberBase(normalized, intendedHost)
        if (!loginRequestId.isNullOrBlank() && !ticket.isNullOrBlank()) {
            applyExchange(loginRequestId, ticket, hops)
            return !authToken.isNullOrBlank()
        }
        extractToken(body)?.let {
            authToken = it
            return true
        }
        return false
    }

    /**
     * 记下落地页所在的考勤站。
     *
     * [intendedHost] 已知时直接采信、不看落地页——这是"力"而非"猜"，专治落地页
     * 变成内网 IP、看不出 host 的情况。没有 [intendedHost] 时才退回旧逻辑：只认
     * `*.xjtu.edu.cn` 下带 `kq` 的主机，落地页可能是 CAS 自己的域名或别的中转，
     * 照单全收会把令牌打到不相干的地方去。
     */
    private fun rememberBase(url: HttpUrl, intendedHost: String? = null) {
        if (!intendedHost.isNullOrBlank()) {
            resolvedBaseUrl = "https://$intendedHost/sa"
            return
        }
        // WebVPN 模式下落地页的 host 是网关，真实域名藏在路径里，先还原再判。
        // 不还原的话这里认不出 kq，基址会停在默认值，业务请求又打回直连。
        val plain = com.xjtu.toolbox.util.WebVpnUtil.getOriginalUrl(url.toString())
            ?.toHttpUrlOrNull() ?: url
        val host = plain.host.lowercase()
        if (host.endsWith(".xjtu.edu.cn") && "kq" in host.substringBefore('.')) {
            resolvedBaseUrl = "https://$host/sa"
        }
    }

    private fun applyExchange(loginRequestId: String, ticket: String, hops: Int) {
        val data = exchange(loginRequestId, ticket)
        val token = data.get("tokenValue")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (token.isNotEmpty()) {
            authToken = token
            Log.d(TAG, "got business token, length=${token.length}")
            return
        }
        // 门户不签业务令牌，只告诉你「还得选一个系统」。以前这里当成失败直接抛
        // 「交换登录票据未返回业务令牌」，表现就是新版考勤怎么都打不开。
        if (data.get("systemSelectionRequired")?.takeIf { !it.isJsonNull }?.asBoolean == true) {
            selectBusinessSystem(data, hops)
            return
        }
        val handoff = data.get("handoffPath")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (!isSafeHandoffPath(handoff)) {
            val msg = data.get("message")?.takeIf { !it.isJsonNull }?.asString
            Log.w(TAG, "exchange gave no token: handoff=<$handoff> data=$data")
            throw RuntimeException("考勤系统登录失败：${msg ?: "交换登录票据未返回业务令牌"}")
        }
        followHandoff(handoff).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!consumeLanding(resp.request.url, body, hops + 1)) {
                throw RuntimeException("考勤系统研究生交接后仍未取得业务令牌")
            }
        }
    }

    /**
     * 门户要求选系统时，替用户选掉。
     *
     * 门户 `/auth/portal/context` 会把本科、研究生两块的可用性都列出来，形如
     * `{"undergraduate":{"scope":"BK","available":true,...},"graduate":{"scope":"YJS",
     * "available":false,"status":"NOT_LAUNCHED","message":"研究生考勤系统暂未上线"}}`。
     * 取第一个 available 的，再去它自己的域名重走一遍 CAS——业务令牌只有业务站会签。
     *
     * scope 到域名的映射是部署常量，门户前端也是写死的，不从接口返回值里推。
     */
    private fun selectBusinessSystem(exchangeData: JsonObject, hops: Int) {
        val target = loginTargetOf(exchangeData)
        val context = portalContext()
        val choices = listOf("undergraduate" to UNDERGRAD_HOST, "graduate" to GRADUATE_HOST)
        val unavailable = mutableListOf<String>()
        for ((key, host) in choices) {
            val node = context.getAsJsonObject(key) ?: continue
            if (node.get("available")?.takeIf { !it.isJsonNull }?.asBoolean != true) {
                node.get("message")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    ?.ifBlank { null }?.let { unavailable += it }
                continue
            }
            Log.d(TAG, "portal selection -> $key ($host), target=$target")
            if (loginAt(host, target, hops)) return
        }
        // 一个都进不去：门户自己给的说明比我们编的准（「暂未上线」「没有您的用户信息」）。
        throw RuntimeException(
            unavailable.distinct().joinToString("；").ifBlank {
                "考勤系统没有可进入的子系统，请在网页端确认账号是否已开通。"
            }
        )
    }

    /** 门户 `student-pc` / `student-h5` 这类入口后缀，由交换结果里的终端与渠道决定。 */
    private fun loginTargetOf(data: JsonObject): String {
        val terminal = data.get("terminal")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val channel = data.get("entryChannel")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val role = if (terminal.equals("TEACHER", true)) "teacher" else "student"
        val suffix = if (channel.equals("H5", true)) "h5" else "pc"
        return "$role-$suffix"
    }

    private fun portalContext(): JsonObject {
        val request = Request.Builder()
            .url(via("$baseUrl/auth/portal/context"))
            .header("Accept", "application/json")
            .header(SYSTEM_HEADER, SYSTEM_VALUE)
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("考勤门户读取失败 (HTTP ${resp.code})")
            val json = body.safeParseJsonObject()
            if (json.get("code")?.takeIf { !it.isJsonNull }?.asInt != 0) {
                val msg = json.get("message")?.takeIf { !it.isJsonNull }?.asString
                throw RuntimeException("考勤门户读取失败：${msg ?: "响应异常"}")
            }
            return json.getAsJsonObject("data")
                ?: throw RuntimeException("考勤门户响应缺少 data")
        }
    }

    /**
     * 去某个业务站重走一遍 CAS 并消费落地页。拿到令牌返回 true。
     *
     * 交换必须打到 `host` 本身，不能指望从落地页猜——落地页偶尔会是内网 IP
     * （202.117.22.36），猜的话会退回门户，门户不签业务令牌，交换必然失败。
     * 显式把 `host` 当 intendedHost 传下去，强制 base/exchange 落在这里。
     */
    private fun loginAt(host: String, target: String, hops: Int): Boolean {
        val entry = via("https://$host/sa/auth/cas/login/$target")
        val pair = casAuthenticate(entry) ?: return false
        val landed = pair.second.toHttpUrlOrNull() ?: return false
        return try {
            consumeLanding(landed, pair.first, hops + 1, intendedHost = host)
        } catch (e: Exception) {
            // 某一侧没开通会在这里抛（104 之类），不该连累还没试的另一侧。
            Log.w(TAG, "loginAt $host failed: ${e.message}")
            false
        }
    }

    private fun exchange(loginRequestId: String, ticket: String): JsonObject {
        val payload = JsonObject().apply {
            addProperty("loginRequestId", loginRequestId)
            addProperty("ticket", ticket)
        }
        client.newCall(
            Request.Builder()
                .url(via("$baseUrl/auth/cas/exchange"))
                .header("Accept", "application/json")
                .header(SYSTEM_HEADER, SYSTEM_VALUE)
                .post(payload.toString().toRequestBody(jsonType))
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("考勤系统登录交换失败 (HTTP ${resp.code})")
            val json = body.safeParseJsonObject()
            val code = json.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: -1
            if (code != 0) {
                val msg = json.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "code=$code"
                Log.w(TAG, "exchange failed at $baseUrl: code=$code msg=$msg")
                throw RuntimeException(friendlyExchangeError(code, msg))
            }
            return json.getAsJsonObject("data")
                ?: throw RuntimeException("考勤系统登录交换响应缺少 data")
        }
    }

    /**
     * 把服务端的原始报错换成用户能据以行动的话。
     *
     * 考勤按学生类型分两套部署，学校是逐步开通的：账号没被纳入本科生考勤时，
     * 网页端显示「当前系统没有您的用户信息或访问权限」，而接口这边回的是
     * `code=104 学生身份与本地数据不一致` 或干脆一个 500 —— 原样抛给用户，
     * 看起来像是我们把身份搞错了，其实是那边还没有这个人的数据。
     */
    private fun friendlyExchangeError(code: Int, msg: String): String = when {
        // 票据失效要排在 104 前面判：104 同时用于「身份不一致」和「浏览器绑定失效」，
        // 先判 104 会把一次普通的重试失败说成「学校还没给你开通」，把真正的首因盖掉。
        "绑定无效" in msg || "已失效" in msg ->
            "登录票据已失效，请退出后重新进入。"
        code == 104 || "身份" in msg && "不一致" in msg ->
            "学校的考勤系统里还没有你的数据（网页端同样显示「没有您的用户信息或访问权限」）。" +
                "这不是登录失败，等学校开通后即可使用。"
        code >= 500 || "暂时不可用" in msg ->
            "考勤系统暂时不可用（服务端 $code）。也可能是学校还没给你开通，可先在网页端确认。"
        else -> "考勤系统登录失败：$msg"
    }

    private fun followHandoff(handoffPath: String): Response {
        var url = HttpUrl.Builder()
            .scheme("https")
            // 跟着已解析出的站点走，不要写死 HOST——研究生交接同样分部署。
            .host(resolvedBaseUrl?.toHttpUrlOrNull()?.host ?: HOST)
            .addPathSegments(handoffPath.trimStart('/'))
            .build()
        val hopClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(MAX_HOPS) {
            val resp = hopClient.newCall(Request.Builder().url(url).get().build()).execute()
            if (resp.code !in 300..399) return resp
            val location = resp.header("Location")
            resp.close()
            if (location.isNullOrBlank()) throw RuntimeException("考勤系统 handoff 缺少跳转地址")
            val resolved = url.resolve(location) ?: throw RuntimeException("考勤系统 handoff 跳转地址无效")
            url = normalizeRedirect(resolved)
        }
        throw RuntimeException("考勤系统 handoff 跳转次数过多")
    }

    private fun extractToken(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val data = body.safeParseJsonObject().getAsJsonObject("data") ?: return null
            data.get("tokenValue")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeHandoffPath(path: String): Boolean =
        path == "/sa/auth/cas/handoff/graduate-student-pc" ||
            path == "/sa/auth/cas/handoff/graduate-student-h5"

    /**
     * 把落地页里出现的内网 IP（202.117.22.36）改写回域名。
     *
     * 以前这里不管三七二十一都改写成门户 [HOST]，可这段代码在研究生直连
     * （[loginAt] 打 [GRADUATE_HOST]）时一样会跑到——把研究生的落地页硬改成门户
     * 域名，后面 [rememberBase] 就会把 base 定成门户，交换必然失败。改写目标
     * 优先用 [intendedHost]（调用方已经知道这一轮打的是哪个 host），只有两边都
     * 不知道时才退回门户（兼容原来的门户流程）。
     */
    private fun normalizeRedirect(url: HttpUrl, intendedHost: String? = null): HttpUrl {
        val fallbackHost = intendedHost ?: HOST
        var next = url
        if (next.host.equals(LEGACY_INTERNAL_HOST, ignoreCase = true)) {
            next = next.newBuilder().scheme("https").host(fallbackHost).port(443).build()
        }
        val service = next.queryParameter("service") ?: return next
        if (LEGACY_INTERNAL_HOST !in service) return next
        val rewritten = service
            .replace("https://$LEGACY_INTERNAL_HOST", "https://$fallbackHost")
            .replace("http://$LEGACY_INTERNAL_HOST", "https://$fallbackHost")
        return next.newBuilder().setQueryParameter("service", rewritten).build()
    }

    companion object {
        private const val TAG = "NewAttendanceLogin"
        private val tokens = Collections.synchronizedMap(WeakHashMap<NewAttendanceLogin, String>())
        private val bases = Collections.synchronizedMap(WeakHashMap<NewAttendanceLogin, String>())
        /** 门户，只负责认人和分流，不签业务令牌。 */
        const val HOST = "kq.xjtu.edu.cn"
        const val BASE_URL = "https://kq.xjtu.edu.cn/sa"
        /**
         * 门户的**通用**入口，不是 `student-pc`。
         *
         * 门户上请求 `student-pc` 会让 CAS 直接把票签给业务站的回调，可那个
         * `loginRequestId` 是门户签发的，拿到业务站去换，服务端找不到绑定，回 500
         * 「服务暂时不可用」。网页端从 `kq.xjtu.edu.cn/` 进来时跳的就是这个 `/pc`：
         * 落在门户自己的回调上，换回 `systemSelectionRequired`，再由业务站**另签**
         * 一个 requestId（见 [selectBusinessSystem]）。终端后缀由那一步按
         * `terminal`/`entryChannel` 自己拼。
         */
        const val LOGIN_URL = "$BASE_URL/auth/cas/login/pc"
        /** 业务站。scope BK / YJS 对应的域名是部署常量，门户前端同样写死。 */
        const val UNDERGRAD_HOST = "bk-kq.xjtu.edu.cn"
        const val GRADUATE_HOST = "yjs-kq.xjtu.edu.cn"
        const val TOKEN_HEADER = "X-Business-Token"
        /** 网页端每个业务请求都带，缺了这一对头部服务端可能不认。 */
        const val SYSTEM_HEADER = "X-System"
        const val SYSTEM_VALUE = "WEB"

        /**
         * WebVPN 模式下把原始地址换成网关地址；直连模式原样返回。
         *
         * 放在伴生对象里是因为要给 [XJTULogin] 的构造参数用——构造期基类就会发第一个
         * 请求，那时实例方法还不能调。
         */
        fun proxied(url: String, useWebVpn: Boolean): String =
            if (useWebVpn) com.xjtu.toolbox.util.WebVpnUtil.getVpnUrl(url) else url

        /**
         * 主构造器的入口 URL：账号类型已知就直接打对应业务站的 `student-pc` 入口，
         * 未知则退回门户通用入口（原来唯一的行为）。纯函数，不依赖任何实例状态，
         * 因为要在 super() 的参数位置上用，那时候 `this` 还不存在。
         */
        fun entryUrlFor(accountType: XJTULogin.AccountType?): String = when (accountType) {
            XJTULogin.AccountType.UNDERGRADUATE -> "https://$UNDERGRAD_HOST/sa/auth/cas/login/student-pc"
            XJTULogin.AccountType.POSTGRADUATE -> "https://$GRADUATE_HOST/sa/auth/cas/login/student-pc"
            null -> LOGIN_URL
        }
        private const val LEGACY_INTERNAL_HOST = "202.117.22.36"
        private const val MAX_HOPS = 8
    }
}
