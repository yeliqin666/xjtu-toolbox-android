package com.xjtu.toolbox.error

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.BuildConfig
import com.xjtu.toolbox.feedback.FeedbackApi
import com.xjtu.toolbox.feedback.FeedbackStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常 → 落盘 → 下次启动匿名上报到反馈表（类型「崩溃」）。
 *
 * 4.9.4 那轮闪退全靠用户在 issue 里描述现象，才知道哪一页崩了。有了这个，
 * 崩溃第一次发生就能在反馈表里看到堆栈。
 *
 * - 崩溃当下进程已经不可靠，只做一件事：同步写一个文件到 filesDir（不进 cacheDir，
 *   免得换包清缓存或系统清理时把还没报的日志一起清掉），然后交还给系统默认处理器。
 * - 上报放到下次启动，走 [FeedbackApi]；成功才删文件，失败留到再下次。
 *   未同意当前版本用户协议（v4 起写明了本功能）之前不上报，见 [uploadPending]。
 * - 堆栈里异常 message 可能带 URL 参数、学号、token，落盘前统一脱敏，见 [redact]。
 * - 堆栈是 R8 混淆后的，需要用 CI 上传的对应 run 的 mapping.txt 做 retrace。
 * - 设置里可关（[isEnabled]），关掉后既不落盘也不上报。
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val DIR = "crash_pending"
    private const val PREFS = "crash_reporter"
    private const val KEY_ENABLED = "enabled"
    /** 同一个崩溃循环一次启动最多报几条，别把反馈表刷屏。 */
    private const val MAX_UPLOAD_PER_LAUNCH = 3
    /** 待报文件最多留几个，崩溃循环时最旧的丢掉。 */
    private const val MAX_PENDING = 10
    private const val MAX_CONTENT_CHARS = 8000

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) runCatching { dir(context).deleteRecursively() }
    }

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** [com.xjtu.toolbox.XjtuApp.onCreate] 里尽早调用。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { if (isEnabled(app)) write(app, thread, throwable) }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val d = dir(context).apply { mkdirs() }
        d.listFiles()?.sortedBy { it.name }?.dropLast(MAX_PENDING - 1)?.forEach { it.delete() }
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("time=").append(ts).append('\n')
            append("build=").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(if (BuildConfig.IS_PREVIEW) " [preview]" else "").append(")\n")
            append("preview=").append(BuildConfig.IS_PREVIEW).append('\n')
            append("thread=").append(thread.name).append('\n')
            append(redact(sw.toString()))
        }.take(MAX_CONTENT_CHARS)
        File(d, "${System.currentTimeMillis()}.txt").writeText(text)
    }

    /**
     * 去掉异常 message 里可能的隐私。先走统一规则 [com.xjtu.toolbox.util.LogRedact]
     * （凭据参数、JSON 敏感字段、CAS 隐藏表单、学号类长数字、token 类长串），
     * 再把剩余的整段 URL 查询串折叠掉——崩溃报告会上传，宁可多删。
     * 堆栈帧本身只有类名方法名行号，不受影响。
     */
    internal fun redact(s: String): String =
        com.xjtu.toolbox.util.LogRedact.redact(s)
            .replace(Regex("""\?[^\s"')]*"""), "?…")

    /**
     * 启动后在后台线程调用：把上次留下的崩溃日志报掉。
     *
     * 必须先同意了**当前版本**的用户协议（含崩溃上报条款）才上传。还没同意时日志
     * 留在本地不动，同意后的下一次启动再报——不能在用户看到条款之前就把数据送出去。
     */
    suspend fun uploadPending(context: Context) {
        if (!FeedbackApi.isConfigured || !isEnabled(context)) return
        if (!com.xjtu.toolbox.data.CredentialStore(context).isEulaAccepted()) return
        val files = dir(context).listFiles()?.sortedBy { it.name } ?: return
        for (f in files.take(MAX_UPLOAD_PER_LAUNCH)) {
            val content = runCatching { f.readText() }.getOrNull()
            if (content.isNullOrBlank()) { f.delete(); continue }
            try {
                FeedbackApi.submit(
                    ticket = FeedbackStore.newTicket(),
                    category = "崩溃",
                    content = content,
                    contact = "",
                    anonId = FeedbackStore.anonId(context),
                )
                f.delete()
            } catch (e: Exception) {
                Log.w(TAG, "upload ${f.name} failed: ${e.message}")
                return
            }
        }
    }
}
