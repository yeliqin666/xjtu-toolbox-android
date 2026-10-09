package com.xjtu.toolbox.platform

import android.util.Log as AndroidLog

/**
 * Android 侧落到 `android.util.Log` —— **行为与 :app 现在完全一致**（同样的 tag、同样的级别、
 * 同样进 logcat），这是「搬位置不改行为」这条约束在日志上的具体含义。
 */
actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    when (level) {
        LogLevel.DEBUG -> AndroidLog.d(tag, message)
        LogLevel.INFO -> AndroidLog.i(tag, message)
        LogLevel.WARN -> AndroidLog.w(tag, message, error)
        LogLevel.ERROR -> AndroidLog.e(tag, message, error)
    }
}
