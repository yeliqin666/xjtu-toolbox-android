package com.xjtu.toolbox.auth

import org.jsoup.Jsoup

/**
 * 「这一页是不是统一身份认证的登录/二次验证页」的**纯判据**。
 *
 * `:app` 的 `XJTULogin` 里那两个函数原样搬到这里，一行逻辑都没改（那边改成一行委托）。
 * 搬的原因：它不只 auth 包要用 —— 每个「被重定向到登录页」的业务站点都要靠它认出来
 * （图书馆那份在 `:data` 的 `LibraryApi.isRedirectedToLogin` 里就调它），而桌面端也要有
 * 同一份判据。放在 `:data` 是为了让**同一份实现**被三种承载共用，而不是各写一遍
 * （两份判据 = 静默漂移，正是交接文档说的「契约的一致」那一层）。
 *
 * 这里不碰 Android：只有 jsoup 与字符串。
 */
object CasLoginPages {

    /**
     * 是不是 CAS 的「Safety Verify」（安全验证/二次认证）页面。
     *
     * 检测条件：fm1 表单含 secState/execution/_eventId 三个字段，
     * 且文档标题含 "Safety Verify" 或文档体含 "/cas/sec/initByType"、「选择安全认证」、「二次认证」。
     */
    fun isSafetyVerifyPage(html: String): Boolean {
        if (html.isBlank()) return false
        return try {
            val doc = Jsoup.parse(html)
            val form = doc.selectFirst("#fm1") ?: return false
            val hasVerifyForm = form.selectFirst("input[name=secState]")?.attr("value")?.isNotEmpty() == true
            val hasExecution = form.selectFirst("input[name=execution]")?.attr("value")?.isNotEmpty() == true
            val hasSubmitEvent = form.selectFirst("input[name=_eventId]")?.attr("value")?.isNotEmpty() == true
            val title = doc.selectFirst("title")?.text() ?: ""
            val hasSafetyTitle = "Safety Verify" in title
            val hasSecInitApi = "/cas/sec/initByType" in html || "\\/cas\\/sec\\/initByType" in html
            val hasSafetyText = "选择安全认证" in html || "二次认证" in html
            hasVerifyForm && hasExecution && hasSubmitEvent && (hasSafetyTitle || hasSecInitApi || hasSafetyText)
        } catch (_: Exception) {
            html.contains("name=\"secState\"") && html.contains("name=\"execution\"")
        }
    }

    /**
     * 判断响应 HTML 是否表明当前业务站点的登录态已失效。
     *
     * 检测两种场景：Safety Verify 页面和统一身份认证登录页。
     */
    fun isAuthFailureResponse(html: String): Boolean {
        if (html.isBlank()) return false
        if (isSafetyVerifyPage(html)) return true
        // 统一身份认证登录页（fm1 表单 + CAS 标识）
        val hasLoginForm = "id=\"fm1\"" in html && "name=\"execution\"" in html
        val hasLoginMarker = "login.xjtu.edu.cn" in html ||
            "cas/login" in html ||
            "统一身份认证" in html
        return hasLoginForm && hasLoginMarker
    }
}
