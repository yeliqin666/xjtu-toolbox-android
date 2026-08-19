package com.xjtu.toolbox.hello

import android.util.Log
import com.xjtu.toolbox.auth.XJTULogin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 迎新/个人信息系统（hello.xjtu.edu.cn）登录。
 *
 * 认证链路与 jwapp 同构（org.xjtu 开放平台 OAuth，appId=966），
 * 依据 `hello.xjtu.edu.cn_Archive [26-08-01 00-39-40].har` 逐跳核实：
 * ```
 * 302 org.xjtu.edu.cn/openplatform/oauth/authorize?appId=966&redirectUri=…/yingxin/login/xjtu/oauth/pc
 * 302 → login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=966&…
 * 302 → cas/login?service=…（已登录则直接带 ST 往下走）
 * 302 → org.xjtu.edu.cn/openplatform/oauth/authorizesw?…&code=OC-…
 * 302 → hello.xjtu.edu.cn/yingxin/login/xjtu/oauth/pc?code=SW-OC-…
 * 302 → hello.xjtu.edu.cn/yingxin-pc/?uuid=…&token=eyJhbGciOiJIUzI1NiJ9.…
 * ```
 *
 * **会话凭据是最后那个 URL 上的 `token`（JWT），不是 cookie。**
 * 后续接口靠请求头携带（见 [HelloApi]），所以这里必须把它从落地 URL 上取下来。
 */
class HelloLogin(
    session: OkHttpClient? = null,
    visitorId: String? = null,
    cachedRsaKey: String? = null,
) : XJTULogin(LOGIN_URL, session, visitorId, cachedRsaKey) {

    /**
     * 落地 URL 上的 JWT，后续所有接口调用的凭据。
     *
     * **必须是计算属性，不能是带初始化器的字段。** [XJTULogin] 的构造函数里就会调用
     * 虚方法 `postLogin()`，那时子类自己的属性初始化器还没执行；若写成
     * `var accessToken: String = ""`，postLogin 里赋的值会在随后被初始化器重新置空——
     * 日志显示"已拿到 token"、字段却是空的。（SuperAppLogin 用 companion 里的
     * `lastSuccessfulLaunchUrl` 兜底，绕的就是这个坑。）
     *
     * 超类的 `finalUrl`（即 postUrl）在超类构造期间赋值，不受子类初始化顺序影响，
     * 所以直接从它现算最稳。
     */
    val accessToken: String
        get() = runCatching {
            finalUrl.toHttpUrlOrNull()?.queryParameter("token").orEmpty()
        }.getOrDefault("")

    /** JWT payload 里的 systemType，接口要求原样回传到 `systemtype` 头。 */
    val systemType: String
        get() = parseSystemType(accessToken)

    override fun postLogin(response: Response) {
        val landed = response.request.url
        Log.d(TAG, "postLogin: finalUrl=$landed")
        // 走 WebVPN 时域名段是 AES 密文，明文 host 不出现——统一用 isAtTargetSite 判断，
        // 别再用 contains("hello.xjtu.edu.cn")（iclassface 就栽在这个写法上）。
        if (!com.xjtu.toolbox.util.WebVpnUtil.isAtTargetSite(landed.toString(), BASE_HOST)) {
            throw RuntimeException("个人信息系统登录回调异常")
        }
        if (landed.queryParameter("token").isNullOrBlank()) {
            throw RuntimeException("个人信息系统未返回访问令牌")
        }
        Log.d(TAG, "postLogin: token present on landing url")
    }

    override fun validateLogin(): Boolean = accessToken.isNotBlank()

    companion object {
        private const val TAG = "HelloLogin"

        const val BASE_HOST = "hello.xjtu.edu.cn"
        const val BASE_URL = "http://$BASE_HOST"

        /** 学生端；教师端是 yingxin_teacher_pc，本 App 只服务学生。 */
        private const val DEFAULT_SYSTEM_TYPE = "yingxin_student_pc"

        const val LOGIN_URL =
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize" +
                "?appId=966" +
                "&redirectUri=http://hello.xjtu.edu.cn/yingxin/login/xjtu/oauth/pc" +
                "&responseType=code&scope=user_info&state=pc"

        /**
         * 从 JWT payload 取 systemType。解析失败一律退回学生端——这个头只影响服务端
         * 选哪套业务视图，猜错了最多是拿不到数据，不会造成越权。
         */
        private fun parseSystemType(jwt: String): String = runCatching {
            val payload = jwt.split(".").getOrNull(1) ?: return@runCatching DEFAULT_SYSTEM_TYPE
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            Regex(""""systemType"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: DEFAULT_SYSTEM_TYPE
        }.getOrDefault(DEFAULT_SYSTEM_TYPE)
    }
}
