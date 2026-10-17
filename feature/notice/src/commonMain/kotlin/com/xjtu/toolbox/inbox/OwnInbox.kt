package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.library.MyBookingInfo
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.Notification
import com.xjtu.toolbox.util.todayOnCampus
import kotlin.time.Clock
import kotlin.time.Instant

/** 我们自己的提醒转成收纳条目，调用方一行搞定。 */
object OwnInbox {
    fun grade(newCount: Int, total: Int) = InboxItem(
        id = "grade:$total", category = InboxCategories.GRADE, source = "成绩",
        title = "出了 $newCount 门新成绩", body = "目前共 $total 门", time = Clock.System.now().toEpochMilliseconds(), route = "jwapp_score",
    )

    fun attendance(text: String) = InboxItem(
        id = "attendance:${todayOnCampus()}:$text", category = InboxCategories.ATTENDANCE, source = "考勤",
        title = text, time = Clock.System.now().toEpochMilliseconds(), route = "new_attendance",
    )

    fun scheduleChange(text: String) = InboxItem(
        id = "schedule:${todayOnCampus()}:${text.hashCode()}", category = InboxCategories.SCHEDULE, source = "课表",
        title = "课表有变动", body = text, time = Clock.System.now().toEpochMilliseconds(), route = "schedule",
    )

    fun examChange(text: String) = InboxItem(
        id = "exam_change:${todayOnCampus()}:${text.hashCode()}", category = InboxCategories.SCHEDULE, source = "考试",
        title = "考试安排有变", body = text, time = Clock.System.now().toEpochMilliseconds(), route = "schedule",
    )

    fun notice(n: Notification) = InboxItem(
        id = "notice:${n.link}", category = InboxCategories.NOTICE, source = n.source.displayName,
        title = n.title, time = Clock.System.now().toEpochMilliseconds(), route = AppRoute.Browser(n.link).id,
    )

    /** 工具箱公告。时间取开始时间，没有就取 id 开头的日期（公告 id 都以发布日期开头），再没有就算现在。 */
    fun bulletin(b: Bulletin): InboxItem {
        val dated = BulletinRules.parseInstant(b.id.take(10))?.toEpochMilliseconds()
        return InboxItem(
            id = "bulletin:${b.id}", category = InboxCategories.BULLETIN, source = "工具箱",
            title = b.title, body = b.body, time = b.startsAt?.toEpochMilliseconds() ?: dated ?: Clock.System.now().toEpochMilliseconds(),
            route = b.url?.let { AppRoute.Browser(it).id },
        )
    }

    /** 图书馆座位要马上做的事（入馆签到 / 中途返回），随每次查到的预约整块替换。 */
    fun library(action: String, b: MyBookingInfo?) = InboxItem(
        id = "library:$action", category = InboxCategories.LIBRARY, source = "图书馆",
        title = listOfNotNull(b?.seatId?.takeIf { it.isNotBlank() }?.let { "座位 $it" }, "待$action").joinToString(" "),
        body = listOfNotNull(b?.area?.takeIf { it.isNotBlank() }, b?.statusText?.takeIf { it.isNotBlank() }).joinToString(" · "),
        time = Clock.System.now().toEpochMilliseconds(), route = AppRoute.Library.id,
    )

    fun todo(category: String, id: String, source: String, title: String, route: String?, expiresAt: Long = 0L) =
        InboxItem(id = id, category = category, source = source, title = title, time = Clock.System.now().toEpochMilliseconds(), route = route, expiresAt = expiresAt)

    /** 思源学堂还没交、没过截止的作业和测试。 */
    fun lmsTodos(items: List<LmsDue>): List<InboxItem> = items
        .filter { !it.submitted }
        .mapNotNull { d ->
            val deadline = runCatching { Instant.parse(d.deadline).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            InboxItem(
                id = (if (d.exam) "lms:exam:" else "lms:") + "${d.courseId}:${d.activityId}",
                category = InboxCategories.LMS, source = d.courseName,
                title = if (d.exam) "测试 · ${d.title}" else d.title,
                time = d.fetchedAt, route = AppRoute.Lms(d.courseId).id, expiresAt = deadline,
            )
        }
}
