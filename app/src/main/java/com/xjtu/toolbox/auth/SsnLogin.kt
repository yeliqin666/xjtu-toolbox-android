package com.xjtu.toolbox.auth

import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 宿舍电费（ssn.xjtu.edu.cn/cems，公寓用电管理系统的手机页）。
 *
 * org 开放平台 OAuth（appId=1759）→ CAS → 带 code 回 `/cems/index/mobile/pay`，站点下发会话 cookie。
 * 页面 HTML 里内嵌一个 `cid`，绑定、解绑、宿舍列表都要带它；页面服务端渲染时没登录会把 `loginUrl` 写成非空。
 */
open class SsnLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(SSN_OAUTH_URL, session, visitorId, cachedRsaKey) {

    companion object {
        const val BASE_URL = "https://ssn.xjtu.edu.cn/cems"
        const val PAY_PAGE_URL = "$BASE_URL/index/mobile/pay"
        const val SSN_OAUTH_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1759" +
                "&redirectUri=http://ssn.xjtu.edu.cn/cems/index/mobile/pay&responseType=code&scope=user_info&state=1234"

        private val CID = Regex("""var\s+cid\s*=\s*"([0-9a-fA-F]{16,64})"""")
        private val LOGIN_URL = Regex("""var\s+loginUrl\s*=\s*"([^"]*)"""")

        /** 页面里的 cid；没有或页面要求登录时返回 null。 */
        fun parseCid(html: String): String? {
            if (LOGIN_URL.find(html)?.groupValues?.get(1).orEmpty().isNotEmpty()) return null
            return CID.find(html)?.groupValues?.get(1)
        }
    }

    var cid: String? = null
        private set

    override fun postLogin(response: Response) {
        if (!response.isSuccessful) {
            val body = lastResponseBody
            android.util.Log.w(
                "SsnLogin",
                "入口 HTTP ${response.code} title=${Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()} " +
                    "loginUrl=${LOGIN_URL.find(body)?.groupValues?.get(1)} cid=${CID.containsMatchIn(body)} " +
                    "尾部：${body.takeLast(400).replace(Regex("""\s+"""), " ")}",
            )
            throw RuntimeException("登录失败：宿舍电费入口返回 HTTP ${response.code}")
        }
        cid = parseCid(lastResponseBody) ?: throw RuntimeException("登录失败：没能取得宿舍电费的会话")
    }
}
