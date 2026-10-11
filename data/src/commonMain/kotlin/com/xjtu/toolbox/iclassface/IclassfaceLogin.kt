package com.xjtu.toolbox.iclassface

import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.redactUrl
import com.xjtu.toolbox.webvpn.WebVpnUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val TAG = "IclassfaceLogin"

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
 *
 * ## 它为什么搬进 `:data`
 *
 * 与它的站点类 [com.xjtu.toolbox.auth.IclassfaceSession] 一起从 `:app` 的
 * `iclassface/IclassfaceLogin.kt` / `auth/Sites.kt` 剪出来（桌面端第 20 条真数据路由：
 * 桌面要自己登 `iclassface`、自己查今天的刷卡/人脸记录）。类名与包路径一字未改 ⇒ `:app` 的
 * `AppLoginState` / `HeadlessSessions` 里那两处 `register(IclassfaceSession())` 一行不用改。
 *
 * 搬迁时被替换的写法只有两处：`android.util.Log` → `:core` 的 `Log`（tag 与级别不变）；
 * `com.xjtu.toolbox.webvpn.WebVpnUtil` 的全限定名换成顶部 import（同一个类，本来就在 `:data`）。
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
        if (WebVpnUtil.isAtTargetSite(finalUrl, "iclassface.xjtu.edu.cn")) {
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
            sessionReady = WebVpnUtil.isAtTargetSite(land, "iclassface.xjtu.edu.cn")
            Log.d(TAG, "postLogin: fallback access land=$land, ready=$sessionReady")
        } catch (e: Exception) {
            Log.e(TAG, "postLogin: fallback access failed", e)
        }
        if (!sessionReady) throw RuntimeException("快速考勤流水登录回调异常")
    }

    companion object {
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
