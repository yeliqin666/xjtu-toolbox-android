package com.xjtu.toolbox.platform

/** 平台能力：日志。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 各端把一行日志落到哪里去。 */
expect fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?)

/** 跨端日志门面。 */
object Log {
    fun d(tag: String, message: String) = platformLog(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = platformLog(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) =
        platformLog(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        platformLog(LogLevel.ERROR, tag, message, error)
}
