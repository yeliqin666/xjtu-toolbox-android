package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.util.redactUrl
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.xjtu.toolbox.auth.XJTULogin

/**
 * 西交人脸识别签到系统（iclassface.xjtu.edu.cn）登录
 *
 * 认证链路（CAS OAuth2，与 superapp 同构，仅 client_id 不同）：
 * 1. GET CAS login 页，service 指向 callbackAuthorize（client_id=1543）
 * 2. CAS 登录成功 → callbackAuthorize → oauth2/authorize → org.xjtu 中转
 * 3. org.xjtu 302 → https://iclassface.xjtu.edu.cn/oauth/callback/xjtu_new?code=SW-OC-...
 * 4. 最终落地 /face/detect（首页），PHPSESSID cookie 维持后续会话
 *
 * 会话凭据：PHPSESSID cookie（服务端 session），无额外 token 需提取。
 * 所有后续请求凭 cookie 发起即可，不需要额外头信息。
 *
 * 仅支持校园网：iclassface.xjtu.edu.cn 不开放校外访问，WebVPN 可作为校外备选。
 */
class IclassfaceLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    private var sessionReady = false

    override fun postLogin(response: Response) {
        val finalUrl = response.request.url.toString()
        Log.d(TAG, "postLogin: finalUrl=${finalUrl.redactUrl()}")
        // 走 WebVPN 时 finalUrl 形如 https://webvpn.xjtu.edu.cn/https/{AES加密域名}/face/detect，
        // 明文 "iclassface.xjtu.edu.cn" 根本不出现——原来的字符串包含判断必然为 false，
        // 于是明明已经 200 落在 /face/detect 上，却仍被判为"登录回调异常"。
        if (com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(finalUrl, "iclassface.xjtu.edu.cn")) {
            sessionReady = true
            return
        }
        // 重访首页触发最后一次 cookie 建立，以防重定向链未完整跟随
        try {
            val resp = client.newCall(
                Request.Builder().url(FACE_DETECT_URL).get().build()
            ).execute()
            val land = resp.request.url.toString()
            resp.close()
            sessionReady = com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(land, "iclassface.xjtu.edu.cn")
            Log.d(TAG, "postLogin: fallback access land=$land, ready=$sessionReady")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: fallback access failed", e)
        }
        if (!sessionReady) throw RuntimeException("快速考勤流水登录回调异常")
    }

    override fun validateLogin(): Boolean {
        if (!sessionReady) return false
        return try {
            val resp = client.newCall(
                Request.Builder().url(CHECKIN_URL).get().build()
            ).execute()
            val land = resp.request.url.toString()
            val code = resp.code
            resp.close()
            // 同 postLogin：WebVPN 下域名是密文，明文匹配会把正常会话误判为失效。
            // isAtTargetSite 内部已包含"不在 CAS 登录页"的判断，无需再单独排除 login.xjtu.edu.cn。
            code == 200 && com.xjtu.toolbox.webvpn.WebVpnUtil.isAtTargetSite(land, "iclassface.xjtu.edu.cn")
        } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "IclassfaceLogin"
        const val BASE_URL = "https://iclassface.xjtu.edu.cn"
        const val FACE_DETECT_URL = "$BASE_URL/face/detect"
        const val CHECKIN_URL = "$BASE_URL/checkin/records"

        /**
         * CAS OAuth2 授权 URL（client_id=1543 → 人脸识别签到平台）
         * 链路：CAS → callbackAuthorize → oauth2/authorize → org.xjtu 中转 → iclassface callback
         *
         * **2026-08-01 真机日志定位（两轮）**：
         *
         * 原来用的是 `cas/login?service=<urlencode(callbackAuthorize?client_id=1543&…)>`。
         * 抓包（`webvpn.xjtu.edu.cn_Archive [iclassface].har`）证明这条 URL 在**浏览器里
         * 完全可用**，全程无 4xx：cas/login(200) → POST(302) → callbackAuthorize(302)
         * → authorize(302) → org.xjtu(302) → iclassface callback(302) → /face/detect(200)。
         * 所以它不是"写错了"。
         *
         * 但在 App 里必然 404，诊断日志（`XJTULogin.init` 的逐跳还原）显示：
         * ```
         * hop0 302 cas/login?service=…                    (ST 票已签发)
         * hop1 302 cas/oauth2.0/callbackAuthorize?…&ticket=ST-600606-…
         * hop1 Location: https://webvpn.xjtu.edu.cn/https/{login.xjtu.edu.cn}/   ← CAS 打回根目录
         * hop2 404
         * ```
         * 关键差异：**浏览器是 POST 密码新登录，App 是携 TGC 的免密 SSO**。同一条 URL 在
         * 免密上下文下，CAS 的 callbackAuthorize 不再转发到 authorize，而是 302 回自己的
         * 根目录（Location 里 `/https/{hex}` 前缀完整，排除了网关改写出错）。
         *
         * 改为直接从 `cas/oauth2.0/authorize` 进入——这正是本项目里**在同样的免密 SSO 上下文下
         * 能跑通**的两个同类站点所用的形状（[com.xjtu.toolbox.auth.VenueLogin] client_id=1439、
         * [com.xjtu.toolbox.auth.CouponLogin] client_id=1596）。CAS 见 TGC 直接签 code，
         * 不再经过 callbackAuthorize 这一跳。
         */
        const val LOGIN_URL =
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?" +
            "response_type=code&client_id=1543&" +
            "redirect_uri=https%3A%2F%2Forg.xjtu.edu.cn%2Fopenplatform%2Foauth%2Fauthorizesw" +
            "%3Fredirect_uri%3Dhttps%3A%2F%2Ficlassface.xjtu.edu.cn%2Foauth%2Fcallback%2Fxjtu_new&" +
            "state=1234"
    }
}
