package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinLevel
import com.xjtu.toolbox.library.MyBookingInfo
import com.xjtu.toolbox.lms.LmsDue
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.Notification
import com.xjtu.toolbox.notification.NotificationSource
import com.xjtu.toolbox.util.todayInSystemZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * [OwnInbox] 从 `:app` 搬进 `:core`（2026-10-14，批①收尾）之后的**行为不变**钉子。
 *
 * 为什么要有它：那次搬家的纪律是「`:app` 侧只许搬走声明 + 引用改名，行为逐字不变」，而 `:app`
 * 那份 `InboxTest` 只钉了其中几格（图书馆待办 / 公告时间 / 待办过期）。搬完在 `:core` 这一侧
 * 要钉的是两件**搬家本身会碰到**的事：
 *
 *  1. 每一个 `id` 的形状（`grade:<总数>` / `attendance:<日期>:<文本>` / `schedule:<日期>:<hash>` /
 *     `notice:<链接>` / `bulletin:<公告 id>` / `library:<动作>` / `lms:<课程>:<活动>`）——
 *     收纳的**合并与已读**全按 id 去重（`InboxRules.merge` / `markRead`），形状变了就等于用户
 *     所有旧条目第二天全部重来一遍；`:web` 与 `:app` 共用同一份 localStorage / prefs 时更是如此；
 *  2. [OwnInbox.bulletin] 的时间那一处是**搬家时唯一的改动**（`java.time` 进不了 commonMain，
 *     换成 `:core` 既有的 `BulletinRules.parseInstant`）—— 它必须仍等于「id 开头那个日期在北京
 *     时间的当天零点」，认不出日期时仍回「现在」而不是 0。
 *
 * 这一份放 `jvmTest`（不是 `commonTest`）与 `bulletin` 那一族测试同一条理由：`BulletinRules` 的
 * 北京时间用的是 `TimeZone.of("Asia/Shanghai")`，wasm 上没有那份时区库（见 `BeijingTime.kt` 的
 * KDoc）⇒ 它不适合在 wasm 上跑。
 */
class OwnInboxTest {

    /** 每一条提醒的 id 形状逐字对（见类 KDoc 第 1 条）。 */
    @Test
    fun everyIdKeepsItsExactShape() {
        val today = todayInSystemZone()

        assertEquals("grade:12", OwnInbox.grade(2, 12).id)
        assertEquals("出了 2 门新成绩", OwnInbox.grade(2, 12).title)
        assertEquals("目前共 12 门", OwnInbox.grade(2, 12).body)
        assertEquals("jwapp_score", OwnInbox.grade(2, 12).route)

        val text = "第 5 周高数换到主楼B-202"
        assertEquals("attendance:$today:缺席", OwnInbox.attendance("缺席").id)
        assertEquals("new_attendance", OwnInbox.attendance("缺席").route)
        // `schedule` 那条带的是**文本的 hashCode**（不是长度、不是自增），逐字沿用 `:app` 的拼法
        assertEquals("schedule:$today:${text.hashCode()}", OwnInbox.scheduleChange(text).id)
        assertEquals("课表有变动", OwnInbox.scheduleChange(text).title)
        assertEquals(text, OwnInbox.scheduleChange(text).body)
        assertEquals("schedule", OwnInbox.scheduleChange(text).route)

        val notice = Notification(
            title = "关于选课的通知",
            link = "https://dean.xjtu.edu.cn/info/1001/1.htm",
            source = NotificationSource.JWC,
        )
        assertEquals("notice:${notice.link}", OwnInbox.notice(notice).id)
        assertEquals(NotificationSource.JWC.displayName, OwnInbox.notice(notice).source)
        assertEquals(AppRoute.Browser(notice.link).id, OwnInbox.notice(notice).route)

        val booking = MyBookingInfo("A123", "三楼北区", "待入馆", mapOf("入馆签到" to "u"))
        assertEquals("library:入馆签到", OwnInbox.library("入馆签到", booking).id)
        assertEquals("座位 A123 待入馆签到", OwnInbox.library("入馆签到", booking).title)
        assertEquals("三楼北区 · 待入馆", OwnInbox.library("入馆签到", booking).body)
        assertEquals(AppRoute.Library.id, OwnInbox.library("入馆签到", booking).route)

        // 思源作业：id 是「课程 + 活动」两格，过期的（deadline 解析不出来）整条丢掉
        val due = LmsDue(
            courseId = 7,
            courseName = "高等数学（上）",
            activityId = 42,
            title = "第三章作业",
            deadline = "2026-10-20T23:59:00+08:00",
            fetchedAt = 1_760_000_000_000L,
        )
        val lms = OwnInbox.lmsTodos(listOf(due, due.copy(activityId = 43, submitted = true))).single()
        assertEquals("lms:7:42", lms.id, "已交的那条不进收纳")
        assertEquals(AppRoute.Lms(7).id, lms.route)
        assertEquals(due.fetchedAt, lms.time)
        assertTrue(lms.expiresAt > 0, "截止时间是解析出来的毫秒（不是 0）")
        assertEquals(emptyList(), OwnInbox.lmsTodos(listOf(due.copy(deadline = "不是时间"))), "解析不出来的整条丢掉")
    }

    /**
     * 公告的时间：**开始时间优先**，没有就取 id 开头的发布日期（按北京时间当天零点），
     * 再没有就算「现在」—— 三条都是 `:app` 搬迁前的口径（换了实现，值不变）。
     */
    @Test
    fun bulletinTimeStillPrefersStartsAtThenTheIdDate() {
        val beijing = TimeZone.of("Asia/Shanghai")
        val dated = Bulletin(
            id = "2026-09-24-tips", level = BulletinLevel.INFO,
            title = "社区上线了", body = "", mustAck = false, block = false,
        )
        val item = OwnInbox.bulletin(dated)
        assertEquals("bulletin:2026-09-24-tips", item.id)
        assertEquals(
            LocalDate.parse("2026-09-24").atStartOfDayIn(beijing).toEpochMilliseconds(),
            item.time,
            "id 开头那个日期的**北京时间零点**（换实现前后逐毫秒同值）",
        )
        assertEquals(
            "2026-09-24",
            Instant.fromEpochMilliseconds(item.time).toLocalDateTime(beijing).date.toString(),
            "反算回北京时区的日期还是那一天（不是被 UTC 平移成前一天）",
        )
        assertNull(item.route, "公告没有 url ⇒ 没有落点")

        // 有开始时间就用开始时间（哪怕 id 里还有一个更晚的日期）
        val startsAt = Instant.parse("2026-08-19T08:00:00Z")
        val scheduled = OwnInbox.bulletin(dated.copy(id = "2027-01-01-x", startsAt = startsAt))
        assertEquals(startsAt.toEpochMilliseconds(), scheduled.time)

        // id 不是日期：既不是 0（会被当成「很久以前」而整条不显示），也不是那一天
        val undated = OwnInbox.bulletin(dated.copy(id = "no-date-here"))
        assertTrue(undated.time > 0L, "认不出日期时取「现在」，不是 0")
        assertEquals(
            todayInSystemZone(),
            Instant.fromEpochMilliseconds(undated.time).toLocalDateTime(TimeZone.currentSystemDefault()).date,
            "取的就是「现在」那一档",
        )
    }
}
