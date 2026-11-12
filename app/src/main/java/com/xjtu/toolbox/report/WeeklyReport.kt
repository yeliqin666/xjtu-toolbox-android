package com.xjtu.toolbox.report

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.lms.LmsDueStore
import com.xjtu.toolbox.lms.lmsInstant
import com.xjtu.toolbox.util.CampusZoneId
import com.xjtu.toolbox.util.RequestCodes
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 小岱周报：每周日晚 20:00 把本周作业、下周 DDL 和校园卡消费小结放进收纳箱「小岱简报」。
 * 只读本机缓存，没有可说的数据就不发；整段不联网，也不调用模型。
 */
internal object WeeklyReport {
    private const val PREFS = "weekly_report"
    private const val DAY_MS = 86_400_000L
    const val ACTION = "com.xjtu.toolbox.WEEKLY_REPORT"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        if (on) arm(context) else cancel(context)
    }

    /** 开机、换包、刷新课表时调用：开着就保证下一次周日闹钟在排。 */
    fun syncAlarm(context: Context) {
        if (isEnabled(context)) arm(context)
    }

    /** 本周（近 7 天）、未来一周 DDL 与校园卡消费的小结；没有可说的内容返回 null。 */
    fun summarize(context: Context, account: String, nowMs: Long): String? {
        val lines = mutableListOf<String>()
        val dues = LmsDueStore.load(context, account)
        val weekAgo = nowMs - 7 * DAY_MS
        val weekAhead = nowMs + 7 * DAY_MS
        fun deadlineMs(d: LmsDue): Long? = lmsInstant(d.deadline)?.toEpochMilliseconds()

        val thisWeek = dues.filter { d -> deadlineMs(d)?.let { it in weekAgo..nowMs } == true }
        var overdue = 0
        if (thisWeek.isNotEmpty()) {
            val done = thisWeek.count { it.submitted }
            overdue = thisWeek.size - done
            lines += "作业：本周截止 ${thisWeek.size} 份，已交 $done 份" + if (overdue > 0) "，未交 $overdue 份" else ""
        }

        val ahead = dues.filter { d -> !d.submitted && deadlineMs(d)?.let { it in nowMs..weekAhead } == true }
            .sortedBy { deadlineMs(it) }
        if (ahead.isNotEmpty()) {
            val names = ahead.take(3).joinToString("、") { it.title.ifBlank { it.courseName } }
            lines += "未来一周 DDL：$names" + if (ahead.size > 3) " 等 ${ahead.size} 项" else ""
        }

        val tx = CampusCardCache.load(context, account)?.transactions.orEmpty()
        val today = todayOnCampus()
        val thisFrom = today.minus(6, DateTimeUnit.DAY).toString()
        val thisTo = today.toString()
        val prevFrom = today.minus(13, DateTimeUnit.DAY).toString()
        val prevTo = today.minus(7, DateTimeUnit.DAY).toString()
        val thisSpent = tx.filter { it.amount < 0 && it.time.take(10) in thisFrom..thisTo }.sumOf { -it.amount }
        val prevSpent = tx.filter { it.amount < 0 && it.time.take(10) in prevFrom..prevTo }.sumOf { -it.amount }
        if (thisSpent > 0) {
            val change = if (prevSpent > 0) {
                val pct = ((thisSpent - prevSpent) / prevSpent * 100).roundToInt()
                "（${if (pct >= 0) "+" else "-"}${abs(pct)}%）"
            } else ""
            lines += "校园卡：本周花了 ${money(thisSpent)} 元，上周 ${money(prevSpent)} 元$change"
        }

        if (lines.isEmpty()) return null
        val comment = when {
            overdue > 0 -> "有作业已经过了 DDL，先把最近那份补上。"
            ahead.isNotEmpty() -> "下周有 DDL，别拖到最后一晚。"
            else -> "这周没什么压力，放松一下。"
        }
        return (lines + "小岱：$comment").joinToString("\n")
    }

    /** 生成并放进收纳箱；没有可说的内容就不放。 */
    fun post(context: Context, account: String, nowMs: Long) {
        val body = summarize(context, account, nowMs) ?: return
        InboxStore.post(
            InboxItem(
                id = "weekly-report:${todayOnCampus()}",
                category = InboxCategories.PIDAI,
                source = "小岱周报",
                title = "本周小结",
                body = body,
                time = nowMs,
                route = null,
            ),
            account,
        )
    }

    private fun money(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()

    /** 下一个周日 20:00（校园时区）。 */
    private fun nextSundayEight(): ZonedDateTime {
        val now = ZonedDateTime.now(CampusZoneId)
        var t = now.with(DayOfWeek.SUNDAY).withHour(20).withMinute(0).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusWeeks(1)
        return t
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.WEEKLY_REPORT_ALARM,
        Intent(context, WeeklyReportReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextSundayEight().toInstant().toEpochMilli()
        val pending = alarmIntent(context)
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            }
        } catch (_: SecurityException) {
            // 精确闹钟权限刚被撤回：退回系统批次调度
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }
}

/** 周日 20:00 的闹钟到点写周报；开机后重新排闹钟。 */
class WeeklyReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!WeeklyReport.isEnabled(context)) return
        val pending = goAsync()
        try {
            if (intent.action == WeeklyReport.ACTION) {
                AccountStore().activeAccountId()?.takeIf { it.isNotBlank() }?.let { account ->
                    WeeklyReport.post(context, account, System.currentTimeMillis())
                }
            }
            WeeklyReport.syncAlarm(context)
        } catch (_: Exception) {
            // 写周报失败不影响下一次排程
            WeeklyReport.syncAlarm(context)
        } finally {
            pending.finish()
        }
    }
}
