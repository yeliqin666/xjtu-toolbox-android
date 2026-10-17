package com.xjtu.toolbox.webvpn

/**
 * 网关把请求拒了：账号没有 WebVPN 权限，或没被授权访问这个资源。
 *
 * 网关的拒绝页是 **HTTP 200** 的 HTML，状态码看不出来，只能认页面内容（见 [isDeniedPage]）。
 * 重试没用：得在校园网内用，或先去事务中心申请 WebVPN，申请入口见 [APPLY_URL]。
 * 抛出的异常（`WebVpnDeniedException`）在 `:shared:data`，它要继承 `java.io.IOException` 才能从 OkHttp 拦截器里抛。
 */
object WebVpnDenied {
    const val MESSAGE = "学校 WebVPN 没给你的账号开通这项权限：在校内请连校园网，在校外请先申请 WebVPN"

    /** 事务中心里的「WebVPN 权限申请」。和一网通办同一套统一认证，带着登录态进去不用再登。 */
    const val APPLY_URL = "https://workflow.xjtu.edu.cn/fe/taskCenter/one/application?app_id=326&platform_id=33"

    private val DENIED_TITLE = Regex("""<title>\s*访问被拒绝\s*</title>""")

    /** 只看页首：拒绝页很短，别为这个把大页面读进内存。 */
    const val PEEK_BYTES = 4096L

    fun isDeniedPage(html: String): Boolean =
        "您没有访问该资源的权限" in html || DENIED_TITLE.containsMatchIn(html)
}