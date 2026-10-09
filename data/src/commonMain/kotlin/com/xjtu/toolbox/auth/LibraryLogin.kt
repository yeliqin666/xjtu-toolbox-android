package com.xjtu.toolbox.auth

import com.xjtu.toolbox.util.redactUrl
import com.xjtu.toolbox.platform.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 图书馆座位预约系统登录
 *
 * 认证链路：
 * 直接对 rg.lib.xjtu.edu.cn:8086/seat/ 发起 CAS SSO 认证。
 * 座位系统本身是 CAS 保护的服务，访问时会 302 到 login.xjtu.edu.cn，
 * 如果已有 TGC cookie（已登录其它服务），CAS 自动签发 ticket 回跳。
 *
 * ⚠️ 不再走 www.lib.xjtu.edu.cn 门户（那是 Vue SPA，没有 CAS 表单）
 *
 * `cachedRsaKey`：与其余 15 个站点（`Sites.kt` 里除了 campus_card 的每一个）同型的参数 ——
 * 会话管家已经缓存了统一认证的公钥时直接拿来用，省掉一次
 * `GET https://login.xjtu.edu.cn/cas/jwt/publicKey`。传进来的 key 解不出来时
 * [XJTULogin.encryptPassword] 仍会照旧重新取一份，所以能力上只是少一次冗余请求。
 * 图书馆站点早年漏传了这个参数（每次登录都白取一次公钥），这是把它与其余站点对齐。
 */
class LibraryLogin(
    existingClient: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(
    // 直接认证座位系统——它本身是 CAS 服务
    loginUrl = "http://rg.lib.xjtu.edu.cn:8086/seat/",
    existingClient = existingClient,
    visitorId = visitorId,
    cachedRsaKey = cachedRsaKey,
) {
    companion object {
        private const val TAG = "LibraryLogin"
        const val SEAT_BASE_URL = "http://rg.lib.xjtu.edu.cn:8086"
    }

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        val body = lastResponseBody  // body 已在 XJTULogin 中读取并存储
        Log.d(TAG, "postLogin: finalUrl=${finalUrl.redactUrl()}, bodyLen=${body.length}")

        // 因为 loginUrl 就是座位系统，init 成功后 response 已经是座位页面。
        // WebVPN 模式下 finalUrl 是 webvpn.xjtu.edu.cn/http-8086/... 包装域名，
        // 需还原为原始 URL 再判断是否已抵达座位系统（否则直连判断永远 false）。
        if (isAtSeatSystem(finalUrl) && looksLikeSeatPage(body)) {
            Log.d(TAG, "postLogin: Seat system ready (direct CAS auth succeeded)")
            return
        }

        // 如果 init 返回的不是座位页面（比如 CAS 发了 ticket 但没跟踪到最终页面），
        // 再显式访问一次座位系统
        Log.d(TAG, "postLogin: init response not seat page, retrying explicit access...")
        try {
            val seatRequest = Request.Builder()
                .url("$SEAT_BASE_URL/seat/")
                .get()
                .build()
            val seatResponse = client.newCall(seatRequest).execute()
            val seatBody = seatResponse.body.use { it.string() }
            val seatFinalUrl = seatResponse.request.url.toString()

            Log.d(TAG, "postLogin retry: code=${seatResponse.code}, finalUrl=${seatFinalUrl.redactUrl()}, bodyLen=${seatBody.length}")

            if (!looksLikeSeatPage(seatBody)) Log.w(TAG, "postLogin retry: still not seat page")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin retry failed", e)
        }
    }

    /** 判断最终 URL 是否已抵达座位系统（兼容直连与 WebVPN 包装域名），且不在 CAS 登录页。 */
    private fun isAtSeatSystem(finalUrl: String): Boolean {
        if (finalUrl.contains("login.xjtu.edu.cn")) return false
        if (finalUrl.contains("rg.lib.xjtu.edu.cn")) return true
        // WebVPN 模式：还原 webvpn 包装 URL 后再判断
        val original = com.xjtu.toolbox.webvpn.WebVpnUtil.getOriginalUrl(finalUrl)
        return original?.contains("rg.lib.xjtu.edu.cn") == true
    }

    /** 座位页内容嗅探（放宽信号，兼容壳页面/改版）。 */
    private fun looksLikeSeatPage(body: String): Boolean =
        body.contains("btn-group") || body.contains("tab-select") ||
        body.contains("seat") || body.contains("qseat") ||
        body.contains("座位") || body.contains("scount")
}