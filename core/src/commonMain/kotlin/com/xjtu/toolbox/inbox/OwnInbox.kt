package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.library.MyBookingInfo
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.Notification
import com.xjtu.toolbox.util.todayInSystemZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 我们自己的提醒转成收纳条目，调用方一行搞定。
 *
 * **2026-10-14 从 `:app` 搬进 `:core`**（批①收尾；决策与理由见 `docs/desktop-port-plan.md` §16.5
 * 的第 2 条）。搬家前它留在 `:app` 的理由写在旧 KDoc 里：「它的参数里挂着 `:app` 的类型」——
 * 那两件（`bulletin.Bulletin` / `library.MyBookingInfo`）与它同样用到的 `Notification` /
 * `AppRoute` / `LmsDue` 后来都进了 `:core`，所以那个理由已经不成立：现在三端写的是同一份条目，
 * `:web` 的 `ScheduleCapability.SCHEDULE_CHANGE_BUBBLE` 也才点得亮（它要
 * `InboxStore.post(OwnInbox.scheduleChange(…))`，而 `InboxStore` 早就在 `:core`）。
 *
 * 搬家纪律（与 `:core`/`:data` 那几次搬迁同一条）：**行为逐字不变** —— 每一个 `id` 的形状、
 * 每一条文案、每一次 `Clock.System.now()` 的读法都原样；`:app` 那边的引用连 import 都不用改
 * （包名仍是 `com.xjtu.toolbox.inbox`），`InboxTest` 一句没动。
 *
 * 唯一的一处改动是 [bulletin] 里的时间：`java.time.LocalDate.parse(…).atStartOfDay(ZoneId.of("Asia/Shanghai"))`
 * 换成了 `:core` 既有的 [BulletinRules.parseInstant]（commonMain 进不了 `java.time`）——
 * 换法与 `BulletinRules.parseInstant` 自己那一处**同一条**：不带时区的日期按**北京时间的当天零点**
 * 解释，逐毫秒同值（大陆 1991 年后无夏令时），且认不出来时同样给 `null`（不编一个「现在」）。
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

    fun notice(n: Notification) = InboxItem(
        id = "notice:${n.link}", category = InboxCategories.NOTICE, source = n.source.displayName,
        title = n.title, time = Clock.System.now().toEpochMilliseconds(), route = AppRoute.Browser(n.link).id,
    )

    /** 工具箱公告。时间取开始时间，没有就取 id 开头的日期（公告 id 都以发布日期开头），再没有就算现在。 */
    fun bulletin(b: Bulletin): InboxItem {
        val dated = BulletinRules.parseInstant(b.id.take(10))?.toEpochMilliseconds()
        return InboxItem(
            id = "bulletin:${b.id}", category = InboxCategories.BULLETIN, source = "工具箱",
            // `Bulletin` 与它的 `Instant` 都搬进了 `:core`（kotlinx-datetime）⇒ 取毫秒的那一档
            // 叫 `toEpochMilliseconds()`（`java.time` 的 `toEpochMilli()` 只差一个 s）。
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

    /** 思源学堂还没交、没过截止的作业。 */
    fun lmsTodos(items: List<LmsDue>): List<InboxItem> = items
        .filter { !it.submitted }
        .mapNotNull { d ->
            val deadline = runCatching { Instant.parse(d.deadline).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            InboxItem(
                id = "lms:${d.courseId}:${d.activityId}", category = InboxCategories.LMS, source = d.courseName,
                title = d.title, time = d.fetchedAt, route = AppRoute.Lms(d.courseId).id, expiresAt = deadline,
            )
        }
}
