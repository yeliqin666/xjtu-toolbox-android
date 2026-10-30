package com.xjtu.toolbox.notification

import android.content.Context

/**
 * 后台提醒。学校那几项互相独立，各自一个开关，也各自一份去重游标。
 *
 * [SELF_TODO] 是例外：它只用得到去重游标，**没有开关**（扫一遍本机待办是零代价，
 * 不值得再让用户去设置里打开一次），所以设置页不列它。
 */
enum class ReminderKind(val key: String, val title: String) {
    /** 图书馆预约后的签到 / 返座催办。 */
    LIBRARY("library", "图书馆签到/返座提醒"),

    /** 课表被改、考试临近。 */
    SCHEDULE("schedule", "课表变更与考试提醒"),

    /** 思源学堂作业快截止。 */
    LMS("lms", "作业截止提醒"),

    /** 社区里自己的帖子被别人回复。 */
    COMMUNITY_REPLY("community_reply", "帖子被回复"),

    /** 社区里任意帖子 @ 了自己。 */
    COMMUNITY_MENTION("community_mention", "被 @ 提及"),

    /** 自建待办快截止。没有开关，只有去重游标（见类注释）。 */
    SELF_TODO("self_todo", "自建待办截止提醒"),
}

/**
 * 后台提醒的开关与去重游标。
 *
 * 开关默认**全关**。学校的三项要在后台悄悄登录学校系统（图书馆 / 教务 / 思源学堂），
 * 社区两项要用 GitHub 令牌定时查询，都跟只抓公开页面的教务通知盯梢不是一回事——
 * 与「课表显示考勤」同理，有额外代价的功能由用户自己打开。
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

    /** 关掉时连基线一起清掉：再打开重新建基线，关着期间的旧事不补推。 */
    fun setEnabled(context: Context, kind: ReminderKind, value: Boolean) {
        val editor = prefs(context).edit().putBoolean("enabled_${kind.key}", value)
        if (!value) editor.remove("baselined_${kind.key}")
        editor.apply()
    }

    /** 是否已把当前能看到的条目记为基线（基线之前的不提醒）。只有按「首次全量」去重的项用得上。 */
    fun isBaselined(context: Context, kind: ReminderKind): Boolean =
        prefs(context).getBoolean("baselined_${kind.key}", false)

    fun setBaselined(context: Context, kind: ReminderKind) {
        prefs(context).edit().putBoolean("baselined_${kind.key}", true).apply()
    }

    /** 这条提醒过没有。id 要能长期稳定标识一件事（作业 id、考试课程+日期）。 */
    fun hasSeen(context: Context, kind: ReminderKind, id: String): Boolean =
        id in seen(context, kind)

    /** 再次见到的挪到最新，超上限时先丢的是早已看不到的旧条目。 */
    fun markSeen(context: Context, kind: ReminderKind, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val again = ids.toSet()
        val merged = (seen(context, kind).filterNot { it in again } + again).takeLast(MAX_SEEN)
        prefs(context).edit().putString("seen_${kind.key}", merged.joinToString("\n")).apply()
    }

    fun seen(context: Context, kind: ReminderKind): List<String> =
        prefs(context).getString("seen_${kind.key}", "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
}
