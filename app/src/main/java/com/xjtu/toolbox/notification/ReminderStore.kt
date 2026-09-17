package com.xjtu.toolbox.notification

import android.content.Context

/**
 * 三类后台提醒。互相独立，各自一个开关，也各自一份去重游标。
 */
enum class ReminderKind(val key: String, val title: String) {
    /** 图书馆预约后的签到 / 返座催办。 */
    LIBRARY("library", "图书馆签到/返座提醒"),

    /** 课表被改、考试临近。 */
    SCHEDULE("schedule", "课表变更与考试提醒"),

    /** 思源学堂作业快截止。 */
    LMS("lms", "作业截止提醒"),
}

/**
 * 后台提醒的开关与去重游标。
 *
 * 默认**全关**。这三项都要在后台悄悄登录学校系统（图书馆 / 教务 / 思源学堂），
 * 跟只抓公开页面的教务通知盯梢不是一回事——与「课表显示考勤」同理，
 * 有额外代价的功能由用户自己打开。
 *
 * 去重游标不进备份：重装后当第一次见到，宁可重复提醒一条，也别因为游标错位漏提醒。
 */
internal object ReminderStore {
    private const val PREFS = "reminder_watch"

    /** 已提醒过的条目上限，超出从最旧的丢掉，避免 prefs 无限涨。 */
    private const val MAX_SEEN = 200

    const val DEFAULT_ENABLED = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, kind: ReminderKind): Boolean =
        prefs(context).getBoolean("enabled_${kind.key}", DEFAULT_ENABLED)

    fun setEnabled(context: Context, kind: ReminderKind, value: Boolean) {
        prefs(context).edit().putBoolean("enabled_${kind.key}", value).apply()
    }

    fun anyEnabled(context: Context): Boolean = ReminderKind.entries.any { isEnabled(context, it) }

    /** 这条提醒过没有。id 要能长期稳定标识一件事（作业 id、考试课程+日期）。 */
    fun hasSeen(context: Context, kind: ReminderKind, id: String): Boolean =
        id in seen(context, kind)

    fun markSeen(context: Context, kind: ReminderKind, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val merged = (seen(context, kind) + ids).distinct().takeLast(MAX_SEEN)
        prefs(context).edit().putString("seen_${kind.key}", merged.joinToString("\n")).apply()
    }

    private fun seen(context: Context, kind: ReminderKind): List<String> =
        prefs(context).getString("seen_${kind.key}", "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
}
