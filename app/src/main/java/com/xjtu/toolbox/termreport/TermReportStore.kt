package com.xjtu.toolbox.termreport

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import java.io.File

/**
 * 生成过的学期总结，存成 HTML 放在 filesDir（cacheDir 换包会清空），按账号分目录。
 * 存的是成品页面而不是数据，以后改了模板或数据结构，旧总结照样能打开。
 */
object TermReportStore {
    private fun dir(context: Context) =
        File(context.filesDir, "term_reports${AccountContext.suffixFor(AccountContext.activeAccountId)}")

    /** [agentSnapshot] 另存一份纯文本，回顾时还能交给小岱点评。 */
    fun save(context: Context, code: String, html: String, agentSnapshot: String) {
        val d = dir(context).apply { mkdirs() }
        File(d, "$code.html").writeText(html)
        File(d, "$code.txt").writeText(agentSnapshot)
    }

    fun load(context: Context, code: String): String? =
        File(dir(context), "$code.html").takeIf { it.isFile }?.readText()

    fun agentSnapshot(context: Context, code: String): String? =
        File(dir(context), "$code.txt").takeIf { it.isFile }?.readText()

    /** 存过的学期码，新的在前。 */
    fun codes(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension == "html" }?.map { it.nameWithoutExtension }?.sortedDescending().orEmpty()
}

/** 「2025-2026-1」→「2025-2026 第一学期」。 */
fun termLabel(code: String): String {
    val year = code.substringBeforeLast('-')
    return when (code.substringAfterLast('-')) {
        "1" -> "$year 第一学期"
        "2" -> "$year 第二学期"
        "3" -> "$year 小学期"
        else -> code
    }
}
