package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * 校园卡系统登录 (card.xjtu.edu.cn)
 *
 * 认证链路：
 * 1. 访问 card.xjtu.edu.cn → 重定向到 cas.xjtu.edu.cn（旧 CAS）认证
 * 2. 旧 CAS 认证成功后回跳 → card.xjtu.edu.cn 设置 hallticket cookie
 * 3. 使用 hallticket 调用校园卡 API
 *
 * 注意：校园卡系统使用旧 CAS (cas.xjtu.edu.cn)，与新 CAS (login.xjtu.edu.cn) 是独立的。
 * cassyno/index 已废弃（404），改用 Category/ContechFirstPage 作为服务入口。
 */
class CampusCardLogin(
    existingClient: OkHttpClient? = null,
    visitorId: String? = null
) : XJTULogin(
    loginUrl = LOGIN_URL,
    existingClient = existingClient,
    visitorId = visitorId
) {
    companion object {
        private const val TAG = "CampusCardLogin"
        /** 校园卡登录入口（触发 CAS 重定向） */
        const val LOGIN_URL = "http://card.xjtu.edu.cn/Category/ContechFirstPage"
        const val BASE_URL = "http://card.xjtu.edu.cn"
        /** 旧 CAS 服务器 */
        private const val OLD_CAS_URL = "https://cas.xjtu.edu.cn"
        /** 校园卡 CAS service URL */
        private const val CARD_SERVICE_URL = "http://card.xjtu.edu.cn/Category/ContechFirstPage"
    }

    /** 校园卡 hallticket 会话令牌 */
    var hallticket: String? = null
        private set

    /** 校园卡账号（非学号） */
    var cardAccount: String? = null
        internal set

    /** 系统是否就绪 */
    var systemReady: Boolean = false
        private set

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        val body = lastResponseBody
        Log.d(TAG, "postLogin: finalUrl=$finalUrl, bodyLen=${body.length}")

        // 尝试从当前响应提取信息
        if (tryExtractInfo(body, finalUrl)) return

        // 仅在已有凭据时尝试旧 CAS 认证（init 阶段 login() 尚未被调用，凭据为空）
        if (storedUsername != null && rawPassword != null) {
            Log.d(TAG, "postLogin: no hallticket, trying old CAS auth...")
            try {
                if (authenticateViaOldCas()) return
            } catch (e: Exception) {
                Log.e(TAG, "postLogin: old CAS auth failed", e)
            }
        } else {
            Log.d(TAG, "postLogin: no credentials yet (will be available after login()), skipping old CAS")
        }

        // 最后尝试访问 /Page/Page
        try {
            val pageRequest = Request.Builder()
                .url("$BASE_URL/Page/Page")
                .get()
                .build()
            val pageResponse = client.newCall(pageRequest).execute()
            val pageBody = pageResponse.body?.use { it.string() } ?: ""
            val pageFinalUrl = pageResponse.request.url.toString()
            Log.d(TAG, "postLogin /Page/Page: code=${pageResponse.code}, finalUrl=$pageFinalUrl, bodyLen=${pageBody.length}")

            if (tryExtractInfo(pageBody, pageFinalUrl)) return
        } catch (e: Exception) {
            Log.e(TAG, "postLogin /Page/Page failed", e)
        }
    }

    /**
     * 通过旧 CAS (cas.xjtu.edu.cn) 认证校园卡系统
     * 旧 CAS 与新 CAS (login.xjtu.edu.cn) 是独立的，需要单独认证
     */
    private fun authenticateViaOldCas(): Boolean {
        val username = storedUsername ?: return false
        val password = rawPassword ?: return false

        val serviceUrl = java.net.URLEncoder.encode(CARD_SERVICE_URL, "UTF-8")
        val casLoginUrl = "$OLD_CAS_URL/login?service=$serviceUrl"
        Log.d(TAG, "oldCasAuth: GET $casLoginUrl")

        // Step 1: 访问旧 CAS 登录页
        val casResp = client.newCall(Request.Builder().url(casLoginUrl).get().build()).execute()
        val casBody = casResp.body?.string() ?: ""
        val casFinalUrl = casResp.request.url.toString()
        Log.d(TAG, "oldCasAuth: GET → code=${casResp.code}, finalUrl=$casFinalUrl, bodyLen=${casBody.length}")

        // 检查是否已成功重定向到校园卡
        if (tryExtractInfo(casBody, casFinalUrl, strict = true)) return true

        // Step 2: 提取旧 CAS 登录表单的 execution / lt 字段
        val doc = Jsoup.parse(casBody)
        val execution = doc.select("input[name=execution]").first()?.attr("value") ?: ""
        val lt = doc.select("input[name=lt]").first()?.attr("value") ?: ""

        if (execution.isEmpty() && lt.isEmpty()) {
            // 既没有登录表单也没有重定向成功，可能是错误页面
            Log.w(TAG, "oldCasAuth: no execution/lt found, page preview: ${casBody.take(500)}")
            return false
        }

        // Step 3: 提交登录表单（旧 CAS 通常使用明文密码）
        val formBuilder = FormBody.Builder()
            .add("username", username)
            .add("password", password)  // 旧 CAS 使用明文密码
            .add("_eventId", "submit")

        // 旧 CAS 可能用 execution 或 lt 字段
        if (execution.isNotEmpty()) formBuilder.add("execution", execution)
        if (lt.isNotEmpty()) formBuilder.add("lt", lt)

        // 可能存在的其他隐藏字段
        doc.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotEmpty() && name !in listOf("username", "password", "execution", "lt", "_eventId")) {
                formBuilder.add(name, value)
            }
        }

        val postUrl = casResp.request.url.toString()  // 提交到最终 URL
        Log.d(TAG, "oldCasAuth: POST to $postUrl (execution=${execution.take(20)}, lt=${lt.take(20)})")

        val loginResp = client.newCall(
            Request.Builder().url(postUrl).post(formBuilder.build()).build()
        ).execute()
        val loginBody = loginResp.body?.use { it.string() } ?: ""
        val loginFinalUrl = loginResp.request.url.toString()
        Log.d(TAG, "oldCasAuth: POST → code=${loginResp.code}, finalUrl=$loginFinalUrl, bodyLen=${loginBody.length}")

        // 检查是否认证成功并重定向到校园卡
        if (tryExtractInfo(loginBody, loginFinalUrl, strict = true)) return true

        // 可能需要再访问一次 /Page/Page 来触发 hallticket cookie 设置
        val pageResp = client.newCall(
            Request.Builder().url("$BASE_URL/Page/Page").get().build()
        ).execute()
        val pageBody = pageResp.body?.use { it.string() } ?: ""
        Log.d(TAG, "oldCasAuth: /Page/Page → code=${pageResp.code}, finalUrl=${pageResp.request.url}")

        return tryExtractInfo(pageBody, pageResp.request.url.toString(), strict = true)
    }

    /**
     * @param strict 严格模式（reAuth 时使用）：必须提取到 hallticket 才返回 true
     *               非严格模式（首次 postLogin）：在 card 页面上且非 404 可假定就绪
     */
    private fun tryExtractInfo(html: String, url: String, strict: Boolean = false): Boolean {
        // 从 cookie 提取 hallticket
        try {
            // 兼容 PersistentCookieJar 和 JavaNetCookieJar
            val cardUrl = okhttp3.HttpUrl.Builder().scheme("http").host("card.xjtu.edu.cn").build()
            val cookies = client.cookieJar.loadForRequest(cardUrl)
            val ht = cookies.find { it.name == "hallticket" }
            if (ht != null) {
                hallticket = ht.value
                Log.d(TAG, "Found hallticket from cookies: ${ht.value.take(8)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract hallticket from cookies: ${e.message}")
        }

        // 从 HTML 提取 cardAccount（JS 中的 toinitInfos('255798') 模式）
        val accountPattern = Regex("""toinitInfos\(\s*'(\d+)'\s*\)""")
        accountPattern.find(html)?.let {
            cardAccount = it.groupValues[1]
            Log.d(TAG, "Found cardAccount from HTML: $cardAccount")
        }

        if (hallticket != null) {
            systemReady = true
            Log.d(TAG, "Campus card system ready (hallticket found)")
            return true
        }

        // 严格模式：必须拿到 hallticket（reAuth 场景）
        if (strict) {
            Log.d(TAG, "tryExtractInfo(strict): no hallticket, returning false")
            return false
        }

        // 非严格模式（首次登录）：在 card 页面上且非错误页可假定已认证
        val host = try { java.net.URI(url).host } catch (_: Exception) { "" }
        val isErrorPage = html.contains("404") && html.length < 2000  // 404 页面通常很短
        if (host == "card.xjtu.edu.cn" && !isErrorPage && html.length > 500) {
            systemReady = true
            Log.d(TAG, "Campus card page reached (host=$host), assuming system ready")
            return true
        }

        Log.d(TAG, "tryExtractInfo: no hallticket, host='$host', bodyLen=${html.length}")
        return false
    }

    /**
     * 重新认证（会话过期 -989 时调用）
     * 策略：
     * 1. 先尝试直接访问校园卡页面（如果 cookie 未过期）
     * 2. 再通过旧 CAS (cas.xjtu.edu.cn) 重新认证
     */
    fun reAuthenticate(): Boolean {
        Log.d(TAG, "reAuthenticate: refreshing hallticket...")
        hallticket = null
        systemReady = false
        try {
            // 方式1: 直接访问校园卡服务入口（触发 CAS 重定向）
            Log.d(TAG, "reAuthenticate[1]: visiting card service URL...")
            val resp1 = client.newCall(
                Request.Builder().url(CARD_SERVICE_URL).get().build()
            ).execute()
            val body1 = resp1.body?.use { it.string() } ?: ""
            val url1 = resp1.request.url.toString()
            Log.d(TAG, "reAuthenticate[1]: code=${resp1.code}, finalUrl=$url1, bodyLen=${body1.length}")

            if (tryExtractInfo(body1, url1, strict = true)) return true

            // 方式2: 通过旧 CAS 认证
            Log.d(TAG, "reAuthenticate[2]: trying old CAS authentication...")
            if (authenticateViaOldCas()) return true

            // 方式3: 访问 /Page/Page
            val pageResp = client.newCall(
                Request.Builder().url("$BASE_URL/Page/Page").get().build()
            ).execute()
            val pageBody = pageResp.body?.use { it.string() } ?: ""
            Log.d(TAG, "reAuthenticate[3]: /Page/Page code=${pageResp.code}, finalUrl=${pageResp.request.url}")

            if (tryExtractInfo(pageBody, pageResp.request.url.toString(), strict = true)) return true

            Log.w(TAG, "reAuthenticate: all methods failed")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
            return false
        }
    }
}
