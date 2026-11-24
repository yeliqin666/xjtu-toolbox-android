package com.xjtu.toolbox.auth

import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.dataFilesDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 登录与认证失效的落盘轨迹（#450）：logcat 会被冲掉，难复现的问题要事后能翻。
 *
 * 写在 `filesDir/auth_trace.log`，超过 [MAX_BYTES] 只留后一半。debug 包取：
 * `adb shell run-as com.xjtu.toolbox cat files/auth_trace.log`。
 * 调用方只传接口路径、状态码、判据，不传学号、姓名、密码和完整响应。
 */
object AuthTrace {
    private const val MAX_BYTES = 256 * 1024
    private const val TAG = "AuthTrace"
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun write(level: String, line: String) {
        Log.w(TAG, "$level $line")
        runCatching {
            synchronized(lock) {
                val file = File(dataFilesDir(), "auth_trace.log")
                if (file.length() > MAX_BYTES) {
                    val text = file.readText()
                    file.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                file.appendText("${time.format(Date())} $level ${line.replace('\n', ' ')}\n")
            }
        }
    }

    /** 抛出的异常连同 cause 链一起记：类名 + 消息，外加最近几帧本 App 的调用位置。 */
    fun error(where: String, e: Throwable) {
        val chain = generateSequence(e) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}" }
        val frames = e.stackTrace.filter { it.className.startsWith("com.xjtu") }.take(6)
            .joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        write("ERROR", "$where 失败：$chain @ $frames")
    }

    /** 响应预览去掉长数字串（学号、手机号、ID），只留结构判据。 */
    fun redact(text: String?, max: Int = 200): String =
        text.orEmpty().replace(Regex("\\d{6,}"), "#").replace(Regex("\\s+"), " ").take(max)
}
