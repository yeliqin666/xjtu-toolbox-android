package com.xjtu.toolbox.platform

/** Web：落到浏览器 console。 */
actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    val line = "[${level.name.lowercase()}] $tag: $message" +
        (error?.let { " :: $it" } ?: "")
    when (level) {
        LogLevel.DEBUG -> console.log(line)
        LogLevel.INFO -> console.info(line)
        LogLevel.WARN -> console.warn(line)
        LogLevel.ERROR -> console.error(line)
    }
}

private external object console {
    fun log(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}
