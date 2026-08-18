package com.xjtu.toolbox.auth

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 体育场馆预订系统登录（202.117.17.144:8080）。
 *
 * 认证链路：
 * 1. org.xjtu.edu.cn 开放平台 OAuth2（appId=1659）
 * 2. → CAS → `202.117.17.144:8080/web/cas/oauth2url.html?code=…`
 * 3. 场馆站下发 SESSION cookie → 302 → `/web/index.html`
 *
 * 会话凭据：8080 端口上的 SESSION cookie。
 *
 * 早前这里走的是另一套部署（CAS client_id=1439 → 80 端口的 `/xjtu/…`，业务路径不带
 * `/web/` 前缀）。那套会话很不稳定、订单接口也取不到数据，改成本文件现在这套。
 */
class VenueLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null
) : XJTULogin(
    loginUrl = VENUE_OAUTH_URL,
    existingClient = session,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey
) {
    companion object {
        private const val TAG = "VenueLogin"

        /** 业务基址。所有接口都在 `/web/` 下。 */
        const val BASE_URL = "http://202.117.17.144:8080"

        /**
         * 支付页在 80 端口的另一套站点上，且要求浏览器自身的会话，
         * 不能把 App 内的 cookie 拼进 URL。
         */
        const val PAY_BASE_URL = "http://202.117.17.144"

        const val VENUE_OAUTH_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?" +
                "responseType=code&scope=user_info&appId=1659&state=1&" +
                "redirectUri=http://202.117.17.144:8080/web/cas/oauth2url.html"

        /** 探活接口。返回 JSON 数组即认为会话有效。 */
        private const val PROBE_URL =
            "$BASE_URL/web/product/productData.html" +
                "?page=1&rows=8&merccode=100001&remark=defaultProList"
    }

    var sessionValid: Boolean = false
        private set

    override fun postLogin(response: Response) {
        Log.d(TAG, "postLogin: finalUrl=${response.request.url}")

        // 首页里带 userno 才算真拿到身份；只看落点 URL 不够，未登录时同样会停在站内
        sessionValid = runCatching {
            client.newCall(
                Request.Builder().url("$BASE_URL/web/index.html").get().build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("").hasUserNo()

        if (!sessionValid) {
            Log.w(TAG, "postLogin: index 未包含有效 userno，回退探活接口")
            sessionValid = validateLogin()
        }
        if (!sessionValid) throw RuntimeException("登录失败：无法建立场馆系统会话")
    }

    override fun validateLogin(): Boolean = try {
        client.newCall(
            Request.Builder().url(PROBE_URL)
                .header("Referer", "$BASE_URL/web/index.html")
                .get().build()
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            // 未登录时这里会返回登录跳转页而非 JSON 数组
            resp.code == 200 && body.trimStart().startsWith("[") && body.trimStart() != "[]"
        }
    } catch (_: Exception) {
        false
    }

    override fun keepAlive(): KeepAliveStatus = try {
        when {
            validateLogin() -> KeepAliveStatus.VALID
            reAuthenticate() -> KeepAliveStatus.REAUTH_OK
            else -> KeepAliveStatus.AUTH_INVALID
        }
    } catch (_: java.io.IOException) {
        KeepAliveStatus.NETWORK_ERROR
    } catch (_: Exception) {
        KeepAliveStatus.ERROR
    }

    private val reAuthLock = Any()

    /** 先试 SSO 直通，不行再走完整 CAS。 */
    fun reAuthenticate(): Boolean = synchronized(reAuthLock) {
        try {
            if (validateLogin()) {
                sessionValid = true
                return true
            }
            runCatching {
                client.newCall(Request.Builder().url(VENUE_OAUTH_URL).get().build())
                    .execute().close()
            }
            if (validateLogin()) {
                sessionValid = true
                Log.d(TAG, "reAuthenticate: SSO 直通成功")
                return true
            }
            casAuthenticate(VENUE_OAUTH_URL) ?: return false
            if (validateLogin()) {
                sessionValid = true
                Log.d(TAG, "reAuthenticate: CAS 重认证成功")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "reAuthenticate failed", e)
        }
        sessionValid = false
        return@synchronized false
    }
}

/**
 * 未登录时 `/web/index.html` 也能打开，但 userno 是空的，
 * 所以要连值一起看，不能只判断字段出现过。
 */
private fun String.hasUserNo(): Boolean {
    val at = indexOf("userno")
    if (at < 0) return false
    return !substring(at, minOf(length, at + 50)).contains("value=\"\"")
}
