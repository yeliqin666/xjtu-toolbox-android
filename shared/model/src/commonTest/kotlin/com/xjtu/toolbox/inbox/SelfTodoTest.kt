package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfTodoTest {

    private val zone = CampusTimeZone
    private val now = LocalDateTime(2026, 10, 14, 10, 0).toInstant(zone)
    private val nowMs = now.toEpochMilliseconds()
    private val day = 24 * 60 * 60 * 1000L

    private fun todo(title: String, offset: Int?, created: Long = nowMs) =
        SelfTodoRules.newTodo(title, dueDate = SelfTodoRules.dueAt(offset, now), now = created)

    @Test
    fun `截止是当天深夜，带时区偏移，格式和滴答一致`() {
        assertEquals("2026-10-14T23:59:00+0800", SelfTodoRules.dueAt(0, now))
        assertEquals("2026-10-15T23:59:00+0800", SelfTodoRules.dueAt(1, now))
        assertNull(SelfTodoRules.dueAt(null, now))
    }

    @Test
    fun `滴答的取值，优先级 0 1 3 5，状态 0 和 2`() {
        assertEquals(listOf(0, 1, 3, 5), listOf(
            SelfTodoRules.PRIORITY_NONE, SelfTodoRules.PRIORITY_LOW, SelfTodoRules.PRIORITY_MEDIUM, SelfTodoRules.PRIORITY_HIGH,
        ))
        assertEquals(0, SelfTodoRules.STATUS_OPEN)
        assertEquals(2, SelfTodoRules.STATUS_DONE)
    }

    @Test
    fun `本周日，周日当天是 0，周一是 6`() {
        assertEquals(0, SelfTodoRules.daysToSunday(LocalDate(2026, 10, 18)))
        assertEquals(6, SelfTodoRules.daysToSunday(LocalDate(2026, 10, 12)))
        assertEquals(4, SelfTodoRules.daysToSunday(LocalDate(2026, 10, 14)))
    }

    @Test
    fun `未办完的按截止先后排，没截止的排后面`() {
        val a = todo("没截止", null, created = nowMs)
        val b = todo("明天", 1, created = nowMs + 1)
        val c = todo("今天", 0, created = nowMs + 2)
        val done = SelfTodoRules.complete(todo("已办", 0), nowMs)
        assertEquals(listOf("今天", "明天", "没截止"), SelfTodoRules.open(listOf(a, b, c, done)).map { it.title })
    }

    @Test
    fun `办完，状态 2 并记完成时间，重复办不改时间`() {
        val t = todo("交报告", 0)
        val done = SelfTodoRules.complete(t, nowMs + day)
        assertTrue(done.done)
        assertEquals(SelfTodoRules.STATUS_DONE, done.status)
        assertTrue(done.completedTime.orEmpty().startsWith("2026-10-15T"))
        assertEquals(done, SelfTodoRules.complete(done, nowMs + 5 * day))
    }

    @Test
    fun `收纳 id 能互相换算，别家的待办 id 不认`() {
        val t = todo("x", null)
        assertEquals(t.id, SelfTodoRules.todoIdOf(SelfTodoRules.inboxId(t.id)))
        assertNull(SelfTodoRules.todoIdOf("lms:1:2"))
    }

    @Test
    fun `自建待办过了截止还在，作业过了截止就没了`() {
        val self = SelfTodoRules.toInboxItem(todo("交报告", 0), nowMs)
        val homework = InboxItem("lms:1:1", InboxCategories.LMS, "高数", "作业", time = nowMs, expiresAt = self.expiresAt)
        val data = InboxData(todos = mapOf(InboxCategories.SELF to listOf(self), InboxCategories.LMS to listOf(homework)))
        val twoDaysLater = nowMs + 2 * day
        // 自建的 time 也得是「现在」，否则消息保留期会误伤——这里只测过期这一条规则
        assertEquals(listOf(self.id), InboxRules.todos(data, twoDaysLater).map { it.id })
    }

    @Test
    fun `办完的自建待办进已完成，过期未办的不算办完`() {
        val t = todo("交报告", 0)
        val item = SelfTodoRules.toInboxItem(t, nowMs)
        val before = InboxData(todos = mapOf(InboxCategories.SELF to listOf(item)))
        // 办完 = 下一次推送里没有它
        val after = InboxRules.replaceTodos(before, InboxCategories.SELF, emptyList(), nowMs + 2 * day)
        assertEquals(listOf(item.id), after.finished.map { it.item.id })
        assertFalse(after.finished.single().ignored)
    }

    @Test
    fun `删除，先忽略再去掉，标成已忽略而不是已完成`() {
        val item = SelfTodoRules.toInboxItem(todo("不做了", 1), nowMs)
        val before = InboxData(todos = mapOf(InboxCategories.SELF to listOf(item)))
        val ignored = InboxRules.ignore(before, item.id, nowMs)
        val after = InboxRules.replaceTodos(ignored, InboxCategories.SELF, emptyList(), nowMs)
        assertEquals(1, after.finished.size)
        assertTrue(after.finished.single().ignored)
    }

    @Test
    fun `提醒串解析，到点、提前、全天九点`() {
        assertEquals(0L, SelfTodoRules.triggerOffsetMs("TRIGGER:PT0S"))
        assertEquals(-30 * 60_000L, SelfTodoRules.triggerOffsetMs("TRIGGER:-PT30M"))
        assertEquals(9 * 3_600_000L, SelfTodoRules.triggerOffsetMs("TRIGGER:P0DT9H0M0S"))
        assertEquals(-(86_400_000L + 9 * 3_600_000L), SelfTodoRules.triggerOffsetMs("TRIGGER:-P1DT9H"))
        assertNull(SelfTodoRules.triggerOffsetMs("TRIGGER:garbage"))
    }

    @Test
    fun `全天待办当天九点提醒，已过就不再提醒`() {
        val t = SelfTodoRules.newTodo("交报告", dueDate = SelfTodoRules.dueAt(1, now), allDay = true, remind = true, now = nowMs)
        // 明天 09:00（上海）
        val tomorrow9 = LocalDateTime(2026, 10, 15, 9, 0).toInstant(zone).toEpochMilliseconds()
        assertEquals(tomorrow9, SelfTodoRules.nextReminderAt(t, nowMs))
        assertNull(SelfTodoRules.nextReminderAt(t, tomorrow9 + 1))
    }

    @Test
    fun `有具体时刻的待办到点提醒`() {
        val (due, allDay) = SelfTodoRules.parseDue("2026-10-16 14:30") ?: error("parse")
        assertFalse(allDay)
        val t = SelfTodoRules.newTodo("开题答辩材料", dueDate = due, remind = true, now = nowMs)
        val expect = LocalDateTime(2026, 10, 16, 14, 30).toInstant(zone).toEpochMilliseconds()
        assertEquals(expect, SelfTodoRules.nextReminderAt(t, nowMs))
    }

    @Test
    fun `没截止、没要求提醒、办完了，都不提醒`() {
        assertNull(SelfTodoRules.nextReminderAt(SelfTodoRules.newTodo("a", remind = true, now = nowMs), nowMs))
        assertNull(SelfTodoRules.nextReminderAt(SelfTodoRules.newTodo("b", dueDate = SelfTodoRules.dueAt(1, now), now = nowMs), nowMs))
        val done = SelfTodoRules.complete(SelfTodoRules.newTodo("c", dueDate = SelfTodoRules.dueAt(1, now), allDay = true, remind = true, now = nowMs), nowMs)
        assertNull(SelfTodoRules.nextReminderAt(done, nowMs))
    }

    @Test
    fun `屁岱给的截止，只到天按全天，带时刻按具体时间，格式不对返回空`() {
        assertEquals("2026-10-16T23:59:00+0800" to true, SelfTodoRules.parseDue("2026-10-16"))
        assertEquals("2026-10-16T14:30:00+0800" to false, SelfTodoRules.parseDue("2026-10-16 14:30"))
        assertEquals("2026-10-16T08:05:00+0800" to false, SelfTodoRules.parseDue("2026-10-16T8:05"))
        assertNull(SelfTodoRules.parseDue("下周五"))
        assertNull(SelfTodoRules.parseDue("2026-13-40"))
    }

    @Test
    fun `滴答兼容字段能序列化来回，新字段缺省不影响旧数据`() {
        val t = todo("交报告", 1).copy(
            priority = SelfTodoRules.PRIORITY_HIGH, tags = listOf("课程"), repeatFlag = "RRULE:FREQ=WEEKLY",
            reminders = listOf("TRIGGER:P0DT9H0M0S"), items = listOf(SelfTodoItem("写摘要")), externalId = "dida-1",
        )
        val json = com.xjtu.toolbox.util.AppJson.encodeToString(listOf(t))
        assertEquals(listOf(t), com.xjtu.toolbox.util.AppJson.decodeFromString<List<SelfTodo>>(json))
        val legacy = """[{"id":"1","title":"老数据"}]"""
        assertEquals("老数据", com.xjtu.toolbox.util.AppJson.decodeFromString<List<SelfTodo>>(legacy).single().title)
    }

    @Test
    fun `自选颜色跟着收纳条目走，缺省不影响旧数据`() {
        val withColor = todo("交报告", 0).copy(colorArgb = 0xFF1565C0.toInt())
        assertEquals(0xFF1565C0.toInt(), SelfTodoRules.toInboxItem(withColor, 1_000L).colorArgb)
        assertNull(SelfTodoRules.toInboxItem(todo("没挑色", 0), 1_000L).colorArgb, "没挑过色就是 null，走分类色")
        // 老数据里没有这个字段，读出来应当是 null 而不是报错
        val legacy = """[{"id":"1","title":"老数据"}]"""
        assertNull(com.xjtu.toolbox.util.AppJson.decodeFromString<List<SelfTodo>>(legacy).single().colorArgb)
    }

    @Test
    fun `新 id 是 UUID 形状，不重复`() {
        val a = SelfTodoRules.newId()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a), a)
        assertTrue(a != SelfTodoRules.newId())
    }

    @Test
    fun `抽屉里挑的日期和钟点，截止精确到分钟`() {
        assertEquals("2026-10-16T08:05:00+0800", SelfTodoRules.dueAt(LocalDate(2026, 10, 16), 8, 5))
        // 默认 23:59：选哪天就是那天的最后一分钟
        assertEquals("2026-10-16T23:59:00+0800", SelfTodoRules.dueAt(LocalDate(2026, 10, 16), 23, 59))
        // 越界的钟点夹到合法范围，不抛异常
        assertEquals("2026-10-16T23:59:00+0800", SelfTodoRules.dueAt(LocalDate(2026, 10, 16), 99, 99))
    }

    @Test
    fun `提醒时刻是截止前 48 小时，落在半夜就顺延到早上 7 点`() {
        // 16 日 23:59 截止 ⇒ 14 日 23:59 提醒（不在静默时段，原样）
        val evening = SelfTodoRules.newTodo(
            "交报告", dueDate = SelfTodoRules.dueAt(LocalDate(2026, 10, 16), 23, 59), now = nowMs,
        )
        assertEquals(
            LocalDateTime(2026, 10, 14, 23, 59).toInstant(zone).toEpochMilliseconds(),
            SelfTodoRules.reminderAt(evening, nowMs),
        )

        // 17 日 03:00 截止 ⇒ 15 日 03:00 落在 0–7 点 ⇒ 顺延到 15 日 07:00
        val night = SelfTodoRules.newTodo(
            "发邮件", dueDate = SelfTodoRules.dueAt(LocalDate(2026, 10, 17), 3, 0), now = nowMs,
        )
        assertEquals(
            LocalDateTime(2026, 10, 15, 7, 0).toInstant(zone).toEpochMilliseconds(),
            SelfTodoRules.reminderAt(night, nowMs),
        )

        // 17 日 08:00 截止 ⇒ 15 日 08:00，已经过了 7 点，原样
        val morning = SelfTodoRules.newTodo(
            "开会材料", dueDate = SelfTodoRules.dueAt(LocalDate(2026, 10, 17), 8, 0), now = nowMs,
        )
        assertEquals(
            LocalDateTime(2026, 10, 15, 8, 0).toInstant(zone).toEpochMilliseconds(),
            SelfTodoRules.reminderAt(morning, nowMs),
        )
    }

    @Test
    fun `建得晚就此刻提醒，半夜照样顺延，过了截止不提醒`() {
        val late = SelfTodoRules.newTodo("今晚才记的", dueDate = SelfTodoRules.dueAt(LocalDate(2026, 10, 15), 20, 0), now = nowMs)
        // 截止前 48 小时早已过去 ⇒ 取此刻（14 日 10:00，白天）
        assertEquals(nowMs, SelfTodoRules.reminderAt(late, nowMs))

        // 此刻是凌晨 3 点 ⇒ 顺延到当天 7 点
        val dawn = LocalDateTime(2026, 10, 14, 3, 0).toInstant(zone).toEpochMilliseconds()
        assertEquals(
            LocalDateTime(2026, 10, 14, 7, 0).toInstant(zone).toEpochMilliseconds(),
            SelfTodoRules.reminderAt(late, dawn),
        )

        // 没截止、已办完、已经过了截止，都不提醒
        assertNull(SelfTodoRules.reminderAt(SelfTodoRules.newTodo("没截止", now = nowMs), nowMs))
        assertNull(SelfTodoRules.reminderAt(SelfTodoRules.complete(late, nowMs), nowMs))
        val overdue = SelfTodoRules.newTodo(
            "过期了", dueDate = SelfTodoRules.dueAt(LocalDate(2026, 10, 13), 20, 0), now = nowMs,
        )
        assertNull(SelfTodoRules.reminderAt(overdue, nowMs))
    }
}