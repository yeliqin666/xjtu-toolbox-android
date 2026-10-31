package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.campusDateTimeOf
import kotlinx.datetime.daysUntil

/** 会话列表按最近更新日期分的几组（北京时间的自然日）。 */
internal enum class SessionGroup(val label: String) {
    TODAY("今天"), YESTERDAY("昨天"), WEEK("7 天内"), EARLIER("更早"),
}

internal class SessionSection(val group: SessionGroup, val sessions: List<AgentSession>)

/** 抽屉 / 左栏要画的内容：置顶的推送记录（没有则 null）+ 其余会话的分组。 */
internal class SessionListModel(val pinned: AgentSession?, val sections: List<SessionSection>) {
    val isEmpty get() = pinned == null && sections.isEmpty()
}

internal fun sessionGroupOf(updatedAt: Long, now: Long): SessionGroup {
    val days = campusDateTimeOf(updatedAt).date.daysUntil(campusDateTimeOf(now).date)
    return when {
        days <= 0 -> SessionGroup.TODAY
        days == 1 -> SessionGroup.YESTERDAY
        days < 7 -> SessionGroup.WEEK
        else -> SessionGroup.EARLIER
    }
}

/** 按标题过滤（忽略大小写），推送记录单独置顶，其余按 [SessionGroup] 分组、组内保持原顺序，空组不出现。 */
internal fun buildSessionList(sessions: List<AgentSession>, query: String, now: Long): SessionListModel {
    val q = query.trim()
    val matched = if (q.isEmpty()) sessions else sessions.filter { it.title.contains(q, ignoreCase = true) }
    val (pinned, rest) = matched.partition { it.id == AgentPushFocus.SESSION_ID }
    val sections = rest.groupBy { sessionGroupOf(it.updatedAt, now) }
        .toList()
        .sortedBy { it.first.ordinal }
        .map { SessionSection(it.first, it.second) }
    return SessionListModel(pinned.firstOrNull(), sections)
}
