package com.xjtu.toolbox.auth

import org.jsoup.Jsoup

/** 「这一页是不是统一身份认证的登录/二次验证页」的纯判据。 */
object CasLoginPages {

    /** 是不是 CAS 的「Safety Verify」（安全验证/二次认证）页面。 */
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

    /** 判断响应 HTML 是否表明当前业务站点的登录态已失效。 */
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
