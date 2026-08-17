package com.xjtu.toolbox.error

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误上报接口。
 *
 * 默认实现 [FileErrorReporter] 把错误写到 cache/error_reports/ 下，
 * 后续接入 Crashlytics / Sentry 只需替换实现，不必改调用点。
 *
 * 调用约定：
 * - **调用方负责切线程**：不要在主线程同步调用，落盘开销较重
 * - **不抛出**：上报本身失败绝不能再把异常往上抛
 */
interface ErrorReporter {
    fun report(tag: String, throwable: Throwable, extra: Map<String, Any?> = emptyMap())
}

/**
 * 文件落盘的默认实现。每次写一条文本到 cache/error_reports/yyyy-MM-dd.log。
 *
 * 不放入 Auto Backup 白名单——错误日志是临时调试信息，重装即清。
 */
class FileErrorReporter(private val appContext: Context) : ErrorReporter {

    override fun report(tag: String, throwable: Throwable, extra: Map<String, Any?>) {
        try {
            val dir = File(appContext.cacheDir, "error_reports").apply { mkdirs() }
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "$day.log")
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("[").append(ts).append("] ").append(tag).append(": ")
            sb.append(throwable.javaClass.simpleName).append(": ").append(throwable.message ?: "")
            sb.append("\n")
            throwable.stackTrace.take(20).forEach {
                sb.append("    at ").append(it.toString()).append("\n")
            }
            if (extra.isNotEmpty()) {
                sb.append("    extra=").append(extra).append("\n")
            }
            file.appendText(sb.toString())
        } catch (e: Exception) {
            Log.w("FileErrorReporter", "report failed", e)
        }
    }
}

/**
 * 全局静态访问点。[XjtuApp.onCreate] 注入。
 *
 * 调用前必须先 [install]，否则走 [NoopErrorReporter] 静默忽略。
 */
object ErrorReporting {
    @Volatile
    private var reporter: ErrorReporter = NoopErrorReporter()

    fun install(reporter: ErrorReporter) {
        this.reporter = reporter
    }

    fun report(tag: String, throwable: Throwable, extra: Map<String, Any?> = emptyMap()) {
        reporter.report(tag, throwable, extra)
    }
}

private class NoopErrorReporter : ErrorReporter {
    override fun report(tag: String, throwable: Throwable, extra: Map<String, Any?>) {}
}