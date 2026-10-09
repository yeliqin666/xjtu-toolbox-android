package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.util.todayInSystemZone
import kotlin.time.Instant
import kotlin.time.Clock

/**
 * 我们自己的提醒转成收纳条目，调用方一行搞定。
 *
 * **留在 :app**（与 [InboxModels.kt] 的搬家相反）：它的参数里挂着 :app 的类型
 * （`bulletin.Bulletin`、`library.MyBookingInfo`），而 Web 端不产生这些提醒
 * （它只把 campus-api 拉来的学校消息/待办写进 [InboxStore]）。
 */
object OwnInbox {
    fun grade(newCount: Int, total: Int) = InboxItem(
        id = "grade:$total", category = InboxCategories.GRADE, source = "成绩",
        title = "出了 $newCount 门新成绩", body = "目前共 $total 门", time = Clock.System.now().toEpochMilliseconds(), route = "jwapp_score",
    )

    fun attendance(text: String) = InboxItem(
        id = "attendance:${todayInSystemZone()}:$text", category = InboxCategories.ATTENDANCE, source = "考勤",
        title = text, time = Clock.System.now().toEpochMilliseconds(), route = "new_attendance",
    )

    fun scheduleChange(text: String) = InboxItem(
        id = "schedule:${todayInSystemZone()}:${text.hashCode()}", category = InboxCategories.SCHEDULE, source = "课表",
        title = "课表有变动", body = text, time = Clock.System.now().toEpochMilliseconds(), route = "schedule",
    )

    fun notice(n: com.xjtu.toolbox.notification.Notification) = InboxItem(
        id = "notice:${n.link}", category = InboxCategories.NOTICE, source = n.source.displayName,
        title = n.title, time = Clock.System.now().toEpochMilliseconds(), route = com.xjtu.toolbox.nav.AppRoute.Browser(n.link).id,
    )

    /** 工具箱公告。时间取开始时间，没有就取 id 开头的日期（公告 id 都以发布日期开头），再没有就算现在。 */
    fun bulletin(b: com.xjtu.toolbox.bulletin.Bulletin): InboxItem {
        val dated = runCatching {
            java.time.LocalDate.parse(b.id.take(10)).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        }.getOrNull()
        return InboxItem(
            id = "bulletin:${b.id}", category = InboxCategories.BULLETIN, source = "工具箱",
            title = b.title, body = b.body, time = b.startsAt?.toEpochMilli() ?: dated ?: Clock.System.now().toEpochMilliseconds(),
            route = b.url?.let { com.xjtu.toolbox.nav.AppRoute.Browser(it).id },
        )
    }

    /** 图书馆座位要马上做的事（入馆签到 / 中途返回），随每次查到的预约整块替换。 */
    fun library(action: String, b: com.xjtu.toolbox.library.MyBookingInfo?) = InboxItem(
        id = "library:$action", category = InboxCategories.LIBRARY, source = "图书馆",
        title = listOfNotNull(b?.seatId?.takeIf { it.isNotBlank() }?.let { "座位 $it" }, "待$action").joinToString(" "),
        body = listOfNotNull(b?.area?.takeIf { it.isNotBlank() }, b?.statusText?.takeIf { it.isNotBlank() }).joinToString(" · "),
        time = Clock.System.now().toEpochMilliseconds(), route = com.xjtu.toolbox.nav.AppRoute.Library.id,
    )

    fun todo(category: String, id: String, source: String, title: String, route: String?, expiresAt: Long = 0L) =
        InboxItem(id = id, category = category, source = source, title = title, time = Clock.System.now().toEpochMilliseconds(), route = route, expiresAt = expiresAt)

    /** 思源学堂还没交、没过截止的作业。 */
    fun lmsTodos(items: List<com.xjtu.toolbox.lms.LmsDue>): List<InboxItem> = items
        .filter { !it.submitted }
        .mapNotNull { d ->
            val deadline = runCatching { Instant.parse(d.deadline).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            InboxItem(
                id = "lms:${d.courseId}:${d.activityId}", category = InboxCategories.LMS, source = d.courseName,
                title = d.title, time = d.fetchedAt, route = com.xjtu.toolbox.nav.AppRoute.Lms(d.courseId).id, expiresAt = deadline,
            )
        }
}
