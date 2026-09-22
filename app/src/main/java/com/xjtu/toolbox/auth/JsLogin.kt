package com.xjtu.toolbox.auth

import android.util.Log
import com.xjtu.toolbox.util.WebVpnUtil
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 智慧教室运维平台 js.xjtu.edu.cn 的登录。
 *
 * 链路（2026-09-22 抓包）：
 * 1. `login.xjtu.edu.cn/cas/login?service=https://js.xjtu.edu.cn/`，标准 CAS；
 * 2. 回跳 `https://js.xjtu.edu.cn/?ticket=ST-…`，落地页只是个 Vue 壳，**不验票**；
 * 3. 前端把票 POST 给 `/server/cas/loginCas`，body `{"ticket","serviceUrl"}`，
 *    serviceUrl 必须和第 1 步的 service 一字不差；
 * 4. 回 `{"code":200,"data":{"tokenName":"TOKEN-AUTH","tokenValue":"<JWT>","tokenTimeout":36000}}`。
 *    之后每个业务请求带请求头 `TOKEN-AUTH: <JWT>` 和 `X-System: WEB`，不认 cookie。
 *    没带或过期时是 HTTP 401 + `{"code":401,"message":"token不存在或者过期"}`。
 *
 * 结果不能放在带初始化器的字段里：[XJTULogin] 走 SSO 时在父类构造期间就调 [postLogin]，
 * 子类字段初始化器随后才跑，会把刚拿到的令牌冲掉（考勤那边为此用了 WeakHashMap，
 * 见 NewAttendanceLogin）。这里用 lateinit：它不生成构造期赋值，postLogin 写进去的值留得住。
 */
class JsLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    /** 换到的令牌。只在 [postLogin] 里写；外部用 [grantOrNull] 读。 */
    private lateinit var grant: JsGrant

    val grantOrNull: JsGrant? get() = if (::grant.isInitialized) grant else null

    override fun postLogin(response: Response) {
        val ticket = findTicket(response) ?: retryForTicket()
        grant = exchangeTicket(client, ticket)
        Log.d(TAG, "postLogin: token ok, timeout=${grant.timeoutSeconds}s")
    }

    /**
     * CAS 回跳偶尔停在「200 + 自动提交表单」上，OkHttp 不会替你提交，落地地址里就没有票。
     * 这时 TGC 已经建好，重访一次入口 CAS 会直接 302 带票回来（同 JwxtLogin 的做法）。
     */
    private fun retryForTicket(): String {
        client.newCall(Request.Builder().url(LOGIN_URL).get().build()).execute().use { retry ->
            val body = retry.body?.string().orEmpty()
            if (XJTULogin.isSafetyVerifyPage(body)) throw SafetyVerifyRequiredException(retry, body)
            return findTicket(retry) ?: throw IOException("智慧教室登录失败：CAS 没有回跳到 js.xjtu.edu.cn")
        }
    }

    companion object {
        private const val TAG = "JsLogin"

        const val BASE_URL = "https://js.xjtu.edu.cn"
        /** CAS service 与 loginCas 的 serviceUrl 必须是同一个字符串（带结尾斜杠）。 */
        const val SERVICE_URL = "$BASE_URL/"
        const val LOGIN_URL = "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjs.xjtu.edu.cn%2F"

        const val TOKEN_HEADER = "TOKEN-AUTH"
        const val SYSTEM_HEADER = "X-System"
        const val SYSTEM_VALUE = "WEB"

        private val JSON = "application/json;charset=UTF-8".toMediaType()

        /**
         * 从整条重定向链里找 `ticket=ST-…`：落地地址、每一跳的请求地址和 Location 都看。
         * 走 WebVPN 时地址是网关形式，先还原成原始地址再解析。
         */
        internal fun findTicket(response: Response): String? {
            fun ticketIn(raw: String?): String? {
                if (raw.isNullOrBlank()) return null
                val plain = WebVpnUtil.getOriginalUrl(raw) ?: raw
                val url = plain.toHttpUrlOrNull() ?: return null
                if (!url.host.equals("js.xjtu.edu.cn", ignoreCase = true)) return null
                return url.queryParameter("ticket")?.trim()?.takeIf { it.startsWith("ST-") }
            }
            return generateSequence(response) { it.priorResponse }.firstNotNullOfOrNull { r ->
                ticketIn(r.request.url.toString()) ?: ticketIn(r.header("Location"))
            }
        }

        /** POST loginCas，把 service ticket 换成 TOKEN-AUTH。 */
        internal fun exchangeTicket(client: OkHttpClient, ticket: String): JsGrant {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("ticket", ticket)
                addProperty("serviceUrl", SERVICE_URL)
            }.toString()
            val request = Request.Builder()
                .url("$BASE_URL/server/cas/loginCas")
                .post(payload.toRequestBody(JSON))
                .header("Accept", "application/json, text/plain, */*")
                .header(SYSTEM_HEADER, SYSTEM_VALUE)
                .header("Origin", BASE_URL)
                .header("Referer", SERVICE_URL)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("智慧教室换取令牌失败：HTTP ${resp.code}")
                val root = runCatching { text.safeParseJsonObject() }.getOrNull()
                    ?: throw IOException("智慧教室换取令牌失败：响应不是 JSON")
                val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val token = data?.get("tokenValue")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                if (token.isEmpty()) {
                    val msg = root.get("message")?.takeIf { !it.isJsonNull }?.asString
                    throw IOException("智慧教室换取令牌失败：${msg ?: "未返回令牌"}")
                }
                val timeout = data?.get("tokenTimeout")?.takeIf { !it.isJsonNull }
                    ?.runCatching { asLong }?.getOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS
                return JsGrant(token, timeout, System.currentTimeMillis())
            }
        }

        /** 抓包里是 36000 秒（10 小时）；没给就按这个算。 */
        const val DEFAULT_TIMEOUT_SECONDS = 36_000L
    }
}

/** loginCas 换来的令牌。只在内存里，存进 SiteSession.localToken 时拆成字符串。 */
data class JsGrant(val token: String, val timeoutSeconds: Long, val obtainedAtMs: Long)
