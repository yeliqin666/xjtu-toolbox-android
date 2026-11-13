package com.xjtu.toolbox.report

import android.content.Context
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.lms.LmsDueStore
import com.xjtu.toolbox.lms.lmsInstant
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 小岱周报的数据部分：本周作业、未来一周 DDL、校园卡消费。只读本机缓存，缺的模块直接不写。
 * 发送走小岱的后台任务（`PidaiJobWorker`），和早报、晚报同一条路：收纳箱、通知、推送记录。
 */
internal object WeeklyReport {
    private const val PREFS = "weekly_report"
    private const val DAY_MS = 86_400_000L

    /** [lines] 是数据行，[fallbackComment] 是模型不可用时的点评。 */
    class Facts(val lines: List<String>, val fallbackComment: String)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
    }

    /** 没有可说的内容返回 null。 */
    fun facts(context: Context, account: String, nowMs: Long): Facts? {
        val lines = mutableListOf<String>()
        val dues = LmsDueStore.load(context, account)
        fun deadlineMs(d: LmsDue): Long? = lmsInstant(d.deadline)?.toEpochMilliseconds()

        val thisWeek = dues.filter { d -> deadlineMs(d)?.let { it in nowMs - 7 * DAY_MS..nowMs } == true }
        val overdue = thisWeek.count { !it.submitted }
        if (thisWeek.isNotEmpty()) {
            lines += "作业：本周截止 ${thisWeek.size} 份，已交 ${thisWeek.size - overdue} 份" +
                if (overdue > 0) "，没交 $overdue 份" else ""
        }

        val ahead = dues.filter { d -> !d.submitted && deadlineMs(d)?.let { it in nowMs..nowMs + 7 * DAY_MS } == true }
            .sortedBy { deadlineMs(it) }
        if (ahead.isNotEmpty()) {
            val names = ahead.take(3).joinToString("、") { it.title.ifBlank { it.courseName } }
            lines += "下周 DDL：$names" + if (ahead.size > 3) " 等 ${ahead.size} 项" else ""
        }

        val tx = CampusCardCache.load(context, account)?.transactions.orEmpty()
        val today = todayOnCampus()
        fun spent(fromDaysAgo: Int, toDaysAgo: Int): Double {
            val from = today.minus(fromDaysAgo, DateTimeUnit.DAY).toString()
            val to = today.minus(toDaysAgo, DateTimeUnit.DAY).toString()
            return tx.filter { it.amount < 0 && it.time.take(10) in from..to }.sumOf { -it.amount }
        }
        val thisSpent = spent(6, 0)
        val prevSpent = spent(13, 7)
        if (thisSpent > 0) {
            val compare = if (prevSpent > 0) {
                val pct = ((thisSpent - prevSpent) / prevSpent * 100).roundToInt()
                "，上周 ${money(prevSpent)} 元（${if (pct >= 0) "多" else "少"} ${abs(pct)}%）"
            } else ""
            lines += "校园卡：本周花了 ${money(thisSpent)} 元$compare"
        }

        if (lines.isEmpty()) return null
        val fallback = when {
            overdue > 0 -> "有作业已经过了 DDL，先把最近那份补上。"
            ahead.isNotEmpty() -> "下周有 DDL，别拖到最后一晚。"
            else -> "这周没什么压力，放松一下。"
        }
        return Facts(lines, fallback)
    }

    /** 两位小数，去掉末尾的 0：12.0 → 12，12.50 → 12.5。 */
    private fun money(v: Double): String = "%.2f".format(v).trimEnd('0').trimEnd('.')
}
