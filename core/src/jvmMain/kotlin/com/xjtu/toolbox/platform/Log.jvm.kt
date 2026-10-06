package com.xjtu.toolbox.platform

/** jvm/desktop：标准输出。本地跑 `:core:jvmTest` 时能直接看到。 */
actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    println("[${level.name.lowercase()}] $tag: $message")
    error?.printStackTrace()
}
