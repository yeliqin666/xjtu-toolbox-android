package com.xjtu.toolbox.util

/**
 * 日志 / 崩溃报告统一脱敏。
 *
 * 所有**可能离开进程**的文本（logcat、[com.xjtu.toolbox.error.FileErrorReporter] 落盘、
 * [com.xjtu.toolbox.error.CrashReporter] 上报）在写出前都走这里，规则只维护这一份。
 *
 * release 包里 `android.util.Log` 已被 R8 剥掉（proguard `-assumenosideeffects`），
 * logcat 这一路只影响 debug 包；崩溃上报在 release 里是真的会发出去的，所以规则按「会上传」的标准定。
 *
 * 覆盖：
 * - URL / 表单里的凭据参数：ticket、code、token 系、secState、execution、JSESSIONID、学工号……
 * - JSON 里同名字段的值
 * - CAS 页面隐藏表单（execution / secState / lt）的 value
 * - 6 位以上连续数字（学号、工号、手机号、卡号）
 * - 32 位以上的 base64 / hex 长串（token、cookie 值、JWT 段）
 *
 * 只替换值，保留键名和结构，排查时仍能看出「哪个参数有没有」。
 */
object LogRedact {
    private const val MASK = "<redacted>"

    /** URL 查询串 / 表单 / Cookie 中按键脱敏。`code` 只在这里算敏感（OAuth 授权码）。 */
    private val PARAM_KEYS = listOf(
        "ticket", "code", "token", "access_token", "refresh_token", "id_token", "auth_token", "authToken",
        "secState", "execution", "lt", "password", "pwd", "passwd", "fpVisitorId",
        "employeeNo", "userId", "userNo", "stuNo", "studentId", "idcard", "phone", "mobile",
        "JSESSIONID", "SESSION", "sessionId", "sid", "CASTGC", "TGC", "openid",
    )

    /** JSON 中按键脱敏。不含 `code`：接口返回的 `"code": 200` 是状态码，脱掉反而没法排查。 */
    private val JSON_KEYS = PARAM_KEYS.filter { it != "code" } +
        listOf("barcode", "qrcode", "payCode", "cardNo", "account", "realName", "xm", "idNumber")

    private fun alt(keys: List<String>) = keys.joinToString("|") { Regex.escape(it) }

    private val PARAM_RE = Regex("""(?i)(^|[?&#;\s,])(${alt(PARAM_KEYS)})=([^&#;\s"'<>]+)""")
    private val JSON_RE = Regex("""(?i)("(?:${alt(JSON_KEYS)})"\s*:\s*)("(?:[^"\\]|\\.)*"|[^,}\]\s]+)""")
    private val INPUT_RE = Regex("""(?i)(name=["'](?:execution|secState|lt|fpVisitorId)["'][^>]*?value=["'])([^"']*)""")
    private val DIGITS_RE = Regex("""\d{6,}""")
    private val LONG_TOKEN_RE = Regex("""[A-Za-z0-9+/_\-]{32,}={0,2}""")

    fun redact(s: String): String = s
        .replace(PARAM_RE) { m -> "${m.groupValues[1]}${m.groupValues[2]}=$MASK" }
        .replace(JSON_RE) { m -> "${m.groupValues[1]}\"$MASK\"" }
        .replace(INPUT_RE) { m -> "${m.groupValues[1]}$MASK" }
        .replace(LONG_TOKEN_RE, MASK)
        .replace(DIGITS_RE, "#")
}

/** 任意 URL（String / HttpUrl / null）脱敏后转字符串，给日志插值用。 */
fun Any?.redactUrl(): String = this?.toString()?.let(LogRedact::redact) ?: "null"

/** 响应体 / HTML 预览：压空白、脱敏、再截断。先脱敏后截断，避免截断把 token 切成短串漏过规则。 */
fun CharSequence?.redactBody(max: Int = 200): String =
    this?.toString()?.replace(Regex("""\s+"""), " ")?.let(LogRedact::redact)?.take(max) ?: "null"
