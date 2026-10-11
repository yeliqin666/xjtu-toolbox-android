package com.xjtu.toolbox.library

import java.net.URLDecoder

/**
 * CAS 的「Safety Verify」二次认证页（`login.xjtu.edu.cn` 渲染的那一页）—— **页面原文只有这一份**。
 *
 * ## 为什么它不在某一个站点的夹具里
 *
 * `testkit` 的第一条纪律就是「页面原文只能有一份」（见 `testkit/build.gradle.kts` 的 KDoc）：改一处，
 * 所有消费者一起动。而这一页**不属于任何一个业务站**：真站点上是 CAS 把它渲染出来的，拦下的是
 * 「任意一个业务请求」。两个业务站夹具都发它，差别只在**发出它的那一刻**：
 *
 * | 夹具 | 那一页出现在哪一步 |
 * |---|---|
 * | [LibraryFakeUpstream]（图书馆那条登录链） | 凭据 POST 的落点 —— 密码已经对了、CAS 仍要二验 |
 * | [JwxtFakeUpstream]（教务那条链） | **回跳**的落点 —— TGC 已下发、`client_id=1675` 仍被拦 |
 *
 * 两者都是真站点的实证形态（`JwxtLogin.postLogin` 的注释、`XJTULogin.open` 的 `initialSafetyVerify`
 * 分支就分别对着这两种落点写的）。
 *
 * ## 形状：逐字段对着判据写
 *
 * `CasLoginPages.isSafetyVerifyPage` 的判据是「`#fm1` 表单里 `secState` / `execution` / `_eventId`
 * 三个隐藏字段都非空，且标题含 "Safety Verify"」。少任何一个，`XJTULogin.captureSafetyVerify` 就
 * 认不出来 —— 表现是登录直接失败、根本走不到 MFA（这正是这条剧本要钉住的那一步）。
 *
 * `submit` 不是判据的一部分，但 `MFAContext.verifyCode` 会把它（连同 `secState`/`execution`/`_eventId`）
 * 原样回提到触发页 —— 所以夹具给上，并在回提时逐个校验（[rejection]）。
 */
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

    /**
     * 校验回提上来的隐藏表单；合格返回 null，否则返回一句人话（带上收到的原文）。
     *
     * 为什么把校验放在夹具里：回提漏字段（或拿默认值凑）在真站点上会被拒，而客户端那边只会看到
     * 「登录没完成」—— 漂了的那一次提交会以 400 + 表单原文出现在测试输出里，而不是变成一个含糊的失败。
     */
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
