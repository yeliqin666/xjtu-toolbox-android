package com.xjtu.toolbox.library

import java.net.URLDecoder

/** CAS 的「Safety Verify」二次认证页（`login.xjtu.edu.cn` 渲染的那一页）—— 页面原文只有这一份。 */
object CasSafetyVerifyPage {

    /** 二次认证页上的 `secState`：这次验证的标识（回提与取手机号都要带它）。 */
    const val SEC_STATE = "sec-state-1"

    /** 二次认证页上的新 `execution`（与登录页的 `e1s1` 不是同一个 —— 回提必须带新的那个）。 */
    const val EXECUTION = "e2s1"

    const val EVENT_ID = "submit"
    const val SUBMIT_VALUE = "Login1"

    /** 页面原文（判据见类 KDoc）。 */
    val HTML: String = """
        <html><head><title>统一身份认证 · Safety Verify</title></head><body>
        <form id="fm1" action="/cas/sec/login" method="post">
          <input type="hidden" name="secState" value="$SEC_STATE">
          <input type="hidden" name="execution" value="$EXECUTION">
          <input type="hidden" name="_eventId" value="$EVENT_ID">
          <input type="hidden" name="submit" value="$SUBMIT_VALUE">
          <div>请完成二次认证（短信验证码已发送到绑定手机号）</div>
        </form>
        </body></html>
    """.trimIndent()

    /** 验证码校验通过后，`MFAContext.verifyCode` 要回提的那些字段。 */
    private val REQUIRED_FIELDS = listOf("secState", "execution", "_eventId", "submit", "fpVisitorId")

    /** 校验回提上来的隐藏表单；合格返回 null，否则返回一句人话（带上收到的原文）。 */
    fun rejection(form: String): String? {
        val fields = form.split('&').filter { it.isNotEmpty() }.associate { part ->
            val raw = part.substringAfter('=', "")
            part.substringBefore('=') to runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        val missing = REQUIRED_FIELDS.filter { fields[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return "二次认证回提少了 ${missing.joinToString("/")}，收到：$form"
        }
        if (fields["secState"] != SEC_STATE ||
            fields["execution"] != EXECUTION ||
            fields["_eventId"] != EVENT_ID ||
            fields["submit"] != SUBMIT_VALUE
        ) {
            return "二次认证回提的字段对不上二次认证页上的那几个，收到：$form"
        }
        return null
    }
}
