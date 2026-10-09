package com.xjtu.toolbox.platform

/**
 * Web：落到浏览器 console。
 *
 * 用 `console.*` 而不是自造缓冲：浏览器开发者工具是 Web 端唯一能看日志的地方，
 * 把日志藏进自造结构等于把它藏起来。
 *
 * ⚠️ 实测：Kotlin/Wasm 的 JS interop **不接受 `Any?` 参数**（只支持 external / primitive /
 * string / function 类型），所以这里先把异常拼进字符串再交给 console，而不是把 Throwable 传过去。
 */
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
