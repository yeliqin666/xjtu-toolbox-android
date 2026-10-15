package com.xjtu.toolbox.platform

import android.util.Log as AndroidLog

/** Android 侧落到 `android.util.Log`。 */
actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    when (level) {
        LogLevel.DEBUG -> AndroidLog.d(tag, message)
        LogLevel.INFO -> AndroidLog.i(tag, message)
        LogLevel.WARN -> AndroidLog.w(tag, message, error)
        LogLevel.ERROR -> AndroidLog.e(tag, message, error)
    }
}
