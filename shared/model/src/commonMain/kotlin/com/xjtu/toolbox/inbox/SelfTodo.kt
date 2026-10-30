package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.synchronizedBlock
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.CAMPUS_TIME_ZONE_ID
import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 用户自建的待办。
 *
 * ### 和「自建日程」不是一回事
 * 自建日程（`CustomCourseEntity`）是课表里**占一段时间**的块（周几、第几节、哪几周），会和课程冲突检测，
 * 对应日历里的 VEVENT。这里是**有截止、不占时段**的事，没有「冲突」，办完就划掉，对应 VTODO。
 * 「周五 14:00 开组会」是日程，「周五前交报告」是待办。两边用的是两个存储、两个入口，别互相塞。
 *
 * ### 字段对齐滴答清单（TickTick / Dida）的任务模型
 * 命名、取值照搬它的开放接口，将来接它的 MCP 或同步时不用再做字段翻译：
 *
 * | 本类 | 滴答 | 说明 |
 * |---|---|---|
 * | `title` / `content` | `title` / `content` | 标题 / 备注 |
 * | `startDate` / `dueDate` | `startDate` / `dueDate` | ISO-8601，带时区偏移，如 `2026-10-16T23:59:00+0800` |
 * | `isAllDay` / `timeZone` | 同名 | |
 * | `priority` | `priority` | **0 无 / 1 低 / 3 中 / 5 高**，不是 0..3，别改成连续数 |
 * | `status` | `status` | **0 未完成 / 2 已完成** |
 * | `tags` | `tags` | |
 * | `projectId` | `projectId` | 滴答的「清单」，本地没有清单概念时为 null |
 * | `repeatFlag` | `repeatFlag` | RRULE 串，**现在只存不解释** |
 * | `reminders` | `reminders` | `TRIGGER:` 串，解释见 [SelfTodoRules.nextReminderAt]。新建待办**不再写它**（提醒时刻改由 [SelfTodoRules.reminderAt] 推算），留着只为对齐滴答和读老数据 |
 * | `items` | `items` | 子任务 |
 * | `externalId` | 滴答的任务 id | 同步时回填，用来去重 |
 *
 * 暂时没做的（留好了字段，没写逻辑）：重复规则展开、子任务界面、滴答同步。
 * 截止提醒由 [SelfTodoRules.reminderAt] 推算（截止前 48 小时，撞上 0–7 点顺延到 7 点），
 * 宿主经 [SelfTodoHooks] 接排期（Android 是 WorkManager 的一条待办一个任务）；屁岱的
 * `add_todo` / `get_todos` / `complete_todo` 走 [SelfTodoStore]（工具本体在 `:feature:notice`
 * 的 `SelfTodoAgentTools`）。
 */
@Serializable
data class SelfTodo(
    val id: String = SelfTodoRules.newId(),
    val title: String,
    val content: String = "",
    val startDate: String? = null,
    val dueDate: String? = null,
    val isAllDay: Boolean = false,
    val timeZone: String = CAMPUS_TIME_ZONE_ID,
    /**
     * 滴答的优先级字段，只为读老数据与将来接滴答同步保留。
     *
     * 界面和屁岱都不再写它：收纳里那条待办的「重要程度」是**用户对这条待办的标注**，
     * 存在 [InboxData.importance]（学校推来的待办也能标），两者别混。
     */
    val priority: Int = 0,
    val status: Int = 0,
    val completedTime: String? = null,
    val tags: List<String> = emptyList(),
    val projectId: String? = null,
    val repeatFlag: String? = null,
    val reminders: List<String> = emptyList(),
    val items: List<SelfTodoItem> = emptyList(),
    val createdTime: Long = 0L,
    val modifiedTime: Long = 0L,
    val externalId: String? = null,
    /** 谁建的：`local` 用户手建，`agent` 屁岱代建，`dida` 从滴答同步来。 */
    val source: String = "local",
    /**
     * 用户给这条待办挑的图标色（ARGB）。null = 用收纳里「我的待办」那一类的默认绿。
     *
     * 本端自己加的字段（滴答那边没有），只影响收纳里这条的图标底色，不参与任何规则。
     */
    val colorArgb: Int? = null,
) {
    val done: Boolean get() = status == SelfTodoRules.STATUS_DONE
}

@Serializable
data class SelfTodoItem(val title: String, val status: Int = 0)

/** 纯规则，不碰存储，便于单测。 */
object SelfTodoRules {
    const val STATUS_OPEN = 0
    const val STATUS_DONE = 2

    /**
     * 优先级：**界面和屁岱都不再生产它**（待办一律按截止时间排，见 [open]），
     * 常量与 [SelfTodo.priority] 字段只为对齐滴答的取值（0/1/3/5，不是连续数）与读老数据。
     */
    const val PRIORITY_NONE = 0
    const val PRIORITY_LOW = 1
    const val PRIORITY_MEDIUM = 3
    const val PRIORITY_HIGH = 5

    private const val ID_PREFIX = "self:"

    fun inboxId(todoId: String) = ID_PREFIX + todoId
    fun todoIdOf(inboxId: String): String? = inboxId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

    /** 随机 UUID（v4 形状）。commonMain 没有 `java.util.UUID`。 */
    fun newId(random: Random = Random.Default): String {
        val b = random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** 全天待办的默认提醒：当天早上 9 点（滴答的写法）。 */
    const val REMIND_ALL_DAY_9AM = "TRIGGER:P0DT9H0M0S"

    /** 有具体时刻的待办，到点提醒。 */
    const val REMIND_AT_DUE = "TRIGGER:PT0S"

    private val TRIGGER = Regex("""^TRIGGER:(-?)P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    /** `TRIGGER:` 串换成毫秒偏移（带符号）；认不出的返回 null。 */
    internal fun triggerOffsetMs(trigger: String): Long? {
        val m = TRIGGER.matchEntire(trigger.trim()) ?: return null
        val (sign, d, h, min, s) = m.destructured
        val seconds = (d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 +
            (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return (if (sign == "-") -seconds else seconds) * 1000
    }

    private fun zoneOf(id: String): TimeZone =
        if (id == CAMPUS_TIME_ZONE_ID) CampusTimeZone else runCatching { TimeZone.of(id) }.getOrElse { CampusTimeZone }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** 滴答的时间写法 `yyyy-MM-dd'T'HH:mm:ssZ`，如 `2026-10-16T23:59:00+0800`。 */
    internal fun format(at: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        val offsetSec = zone.offsetAt(at).totalSeconds
        val sign = if (offsetSec < 0) '-' else '+'
        val abs = kotlin.math.abs(offsetSec)
        return "${local.date}T${two(local.hour)}:${two(local.minute)}:${two(local.second)}$sign${two(abs / 3600)}${two(abs % 3600 / 60)}"
    }

    private fun format(local: LocalDateTime, zone: TimeZone): String = format(local.toInstant(zone), zone)

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})([+-])(\d{2}):?(\d{2})$""")

    internal fun parse(text: String): Instant? {
        val m = ISO.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        return runCatching {
            val local = LocalDateTime(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            val sign = if (g[7] == "-") -1 else 1
            local.toInstant(UtcOffset(hours = sign * g[8].toInt(), minutes = sign * g[9].toInt()))
        }.getOrNull()
    }

    /**
     * 老数据的 `reminders` 串（`TRIGGER:`）换算成提醒时刻（毫秒）；没有截止、没有提醒、都已过去的返回 null。
     * 全天待办的基准是截止那天的 0 点（所以 `P0DT9H0M0S` 是当天 9 点），有具体时刻的基准是截止时刻本身。
     *
     * **现在的排期不再走它**：提醒时刻由 [reminderAt] 按截止时间推算（宿主排期）。
     * 留着是因为老待办里还有这些串，将来接滴答同步也还认这个格式。
     */
    fun nextReminderAt(todo: SelfTodo, now: Long): Long? {
        if (todo.done || todo.reminders.isEmpty()) return null
        val due = dueMillis(todo).takeIf { it > 0 } ?: return null
        val base = if (todo.isAllDay) {
            val zone = zoneOf(todo.timeZone)
            Instant.fromEpochMilliseconds(due).toLocalDateTime(zone).date.atStartOfDayIn(zone).toEpochMilliseconds()
        } else due
        return todo.reminders.mapNotNull { triggerOffsetMs(it)?.let { off -> base + off } }.filter { it > now }.minOrNull()
    }

    /** 把「今天 / 明天 / 本周日」换成当天 23:59 的截止时间；[offsetDays] 为 null 表示不设截止。按学校时区算哪一天。 */
    fun dueAt(offsetDays: Int?, now: Instant, zone: TimeZone = CampusTimeZone): String? =
        offsetDays?.let {
            val day = now.toLocalDateTime(zone).date.plus(DatePeriod(days = it))
            format(LocalDateTime(day, LocalTime(23, 59)), zone)
        }

    /**
     * 用户在抽屉里挑的那一天、那个钟点（默认 23:59）换成的截止串。
     * 这是**有具体时刻**的截止（不是全天），所以当天就没有「9 点提醒」那套换算。
     */
    fun dueAt(date: LocalDate, hour: Int, minute: Int, zone: TimeZone = CampusTimeZone): String =
        format(LocalDateTime(date, LocalTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))), zone)

    /** 自建待办的提醒提前量：截止前 48 小时（和作业截止的那档窗口一样长）。 */
    const val REMIND_AHEAD_MS = 48L * 60 * 60 * 1000

    /** 静默时段到几点：算出来的提醒时刻落在 0 点到这个点之间，就顺延到这个点。 */
    const val QUIET_END_HOUR = 7

    /**
     * 这条待办该在什么时候提醒（毫秒）；没截止、已办完、已经过了截止的返回 null。
     *
     * 推算：截止前 [REMIND_AHEAD_MS]；算出来的时刻若落在 0:00–[QUIET_END_HOUR]:00 之间，
     * 顺延到当天 [QUIET_END_HOUR]:00 —— 半夜弹通知没人看，第二天早上再响也不迟。
     * 算出来的时刻已经过去（待办建得晚，比如今晚建、明天下午截止）就取此刻，同样避开静默时段；
     * 兜底再卡一次「不能晚于截止」。
     *
     * 只负责算，排期与去重交给宿主（Android 是 [SelfTodoHooks] 接的 WorkManager）。
     */
    fun reminderAt(todo: SelfTodo, now: Long): Long? {
        if (todo.done) return null
        val due = dueMillis(todo).takeIf { it > 0 } ?: return null
        if (due <= now) return null
        val zone = zoneOf(todo.timeZone)
        // 落在静默时段就顺延到当天早上；其余原样
        fun outOfQuiet(ms: Long): Long {
            val local = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
            if (local.hour >= QUIET_END_HOUR) return ms
            return LocalDateTime(local.date, LocalTime(QUIET_END_HOUR, 0)).toInstant(zone).toEpochMilliseconds()
        }
        return outOfQuiet(maxOf(due - REMIND_AHEAD_MS, now)).takeIf { it < due }
    }

    private val DUE_AT = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2}) (\d{1,2}):(\d{2})$""")

    /**
     * 解析屁岱给的截止：`yyyy-MM-dd`（只到天，记成当天 23:59 并算全天）或 `yyyy-MM-dd HH:mm`（也认 `T`）。
     * 返回 (滴答格式的截止串, 是否全天)；格式不对返回 null。
     */
    fun parseDue(raw: String, zone: TimeZone = CampusTimeZone): Pair<String, Boolean>? {
        val text = raw.trim().replace('T', ' ')
        runCatching { LocalDate.parse(text) }.getOrNull()?.let { day ->
            return format(LocalDateTime(day, LocalTime(23, 59)), zone) to true
        }
        val g = DUE_AT.matchEntire(text)?.groupValues ?: return null
        val at = runCatching { LocalDateTime(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt()) }
            .getOrNull() ?: return null
        return format(at, zone) to false
    }

    fun dueMillis(todo: SelfTodo): Long =
        todo.dueDate?.let { parse(it)?.toEpochMilliseconds() } ?: 0L

    /** 还没办的，按「有截止的在前、越近越前，没截止的按建立时间」排。 */
    fun open(todos: List<SelfTodo>): List<SelfTodo> =
        todos.filter { !it.done }.sortedWith(compareBy({ dueMillis(it).takeIf { d -> d > 0 } ?: Long.MAX_VALUE }, { it.createdTime }))

    /** 转成收纳里的一条：粘住（过了截止也不消失），截止时间放 expiresAt 供排序和分组。 */
    fun toInboxItem(todo: SelfTodo, now: Long): InboxItem = InboxItem(
        id = inboxId(todo.id),
        category = InboxCategories.SELF,
        source = todo.tags.firstOrNull()?.takeIf { it.isNotBlank() } ?: "我的待办",
        title = todo.title,
        body = todo.content,
        time = now,
        expiresAt = dueMillis(todo),
        sticky = true,
        colorArgb = todo.colorArgb,
    )

    /**
     * @param allDay 截止只精确到天（全天待办）：截止放当天 23:59。
     * @param remind 要不要提醒；没有截止就无从提醒，忽略。**界面不再问这一项**（新建的一律按 [reminderAt] 推算的时刻提醒），
     *   留着是给读老数据与别的调用方用。
     */
    fun newTodo(
        title: String,
        content: String = "",
        dueDate: String? = null,
        priority: Int = PRIORITY_NONE,
        source: String = "local",
        allDay: Boolean = false,
        remind: Boolean = false,
        colorArgb: Int? = null,
        now: Long,
    ): SelfTodo =
        SelfTodo(
            title = title.trim(), content = content.trim(), dueDate = dueDate, priority = priority,
            isAllDay = allDay && dueDate != null,
            reminders = if (remind && dueDate != null) listOf(if (allDay) REMIND_ALL_DAY_9AM else REMIND_AT_DUE) else emptyList(),
            createdTime = now, modifiedTime = now, source = source, colorArgb = colorArgb,
        )

    fun complete(todo: SelfTodo, now: Long): SelfTodo =
        if (todo.done) todo
        else todo.copy(
            status = STATUS_DONE, modifiedTime = now,
            completedTime = format(Instant.fromEpochMilliseconds(now), zoneOf(todo.timeZone)),
        )

    /** 「今天 / 明天 / 本周日」三个快捷项到 offsetDays 的换算；本周日在周日当天就是 0。 */
    fun daysToSunday(today: LocalDate): Int = (7 - today.dayOfWeek.isoDayNumber) % 7

    /** 截止的显示：全天的只到天，有时刻的到分钟，按待办自己的时区。 */
    fun dueText(todo: SelfTodo): String? {
        val ms = dueMillis(todo).takeIf { it > 0 } ?: return null
        val local = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zoneOf(todo.timeZone))
        return if (todo.isAllDay) local.date.toString() else "${local.date} ${two(local.hour)}:${two(local.minute)}"
    }

    /** 提醒时刻的显示（学校时区，到分钟）。 */
    fun instantText(ms: Long): String {
        val local = Instant.fromEpochMilliseconds(ms).toLocalDateTime(CampusTimeZone)
        return "${local.date} ${two(local.hour)}:${two(local.minute)}"
    }
}

/**
 * 宿主的截止提醒槽位：存储每次改动后对一遍（Android 按 [SelfTodoRules.reminderAt] 重排/取消那一条
 * WorkManager 任务；没接的端不提醒）。
 *
 * 给的是改动前后的两份，方便宿主知道哪条没了、哪条是新的。
 */
object SelfTodoHooks {
    var onChanged: ((account: String?, before: List<SelfTodo>, after: List<SelfTodo>) -> Unit)? = null
}

/**
 * 自建待办的存储：按账号一份 `keyValueStore("self_todos<账号后缀>")`，整份 JSON 读写。
 * 每次改动都把未办完的整块推给收纳（[InboxStore.setTodos]），办完的由收纳自己挪进「已完成」。
 *
 * **Agent 能力的接口**就是这里的 [create] / [open] / [complete] / [delete]：给屁岱加工具，
 * 直接调它们（`source = "agent"`），不用碰收纳。
 */
object SelfTodoStore {
    private val lock = Any()

    private fun prefs(account: String?) = keyValueStore("self_todos${AccountContext.suffixFor(account)}")

    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    fun all(account: String? = AccountContext.activeAccountId): List<SelfTodo> = synchronizedBlock(lock) {
        prefs(account).getString("data")
            ?.let { runCatching { AppJson.decodeFromString<List<SelfTodo>>(it) }.getOrNull() }
            .orEmpty()
    }

    private fun save(account: String?, todos: List<SelfTodo>) {
        val before = synchronizedBlock(lock) {
            val b = all(account)
            prefs(account).putString("data", AppJson.encodeToString(todos))
            b
        }
        publish(account, todos)
        runCatching { SelfTodoHooks.onChanged?.invoke(account, before, todos) }
    }

    fun open(account: String? = AccountContext.activeAccountId): List<SelfTodo> = SelfTodoRules.open(all(account))

    /** 把当前未办完的推给收纳。进收纳页时也调一次，顺带刷新时间戳。 */
    fun publish(account: String? = AccountContext.activeAccountId, todos: List<SelfTodo> = all(account)) {
        val now = nowMs()
        InboxStore.setTodos(InboxCategories.SELF, SelfTodoRules.open(todos).map { SelfTodoRules.toInboxItem(it, now) }, account)
    }

    fun create(
        title: String,
        content: String = "",
        dueDate: String? = null,
        priority: Int = SelfTodoRules.PRIORITY_NONE,
        source: String = "local",
        allDay: Boolean = false,
        remind: Boolean = false,
        colorArgb: Int? = null,
        account: String? = AccountContext.activeAccountId,
    ): SelfTodo? {
        if (title.isBlank()) return null
        val todo = SelfTodoRules.newTodo(title, content, dueDate, priority, source, allDay, remind, colorArgb, nowMs())
        save(account, all(account) + todo)
        return todo
    }

    fun complete(id: String, account: String? = AccountContext.activeAccountId) {
        val now = nowMs()
        save(account, all(account).map { if (it.id == id) SelfTodoRules.complete(it, now) else it })
    }

    /** 给一条自建待办挑颜色（ARGB；null = 回到「我的待办」那一类的默认绿）。 */
    fun setColor(id: String, colorArgb: Int?, account: String? = AccountContext.activeAccountId) {
        val now = nowMs()
        save(account, all(account).map { if (it.id == id) it.copy(colorArgb = colorArgb, modifiedTime = now) else it })
    }

    /** 删除：先按「忽略」记进收纳的已完成区（标已忽略），再从存储里去掉，免得显示成「已完成」。 */
    fun delete(id: String, account: String? = AccountContext.activeAccountId) {
        InboxStore.ignoreTodo(SelfTodoRules.inboxId(id))
        save(account, all(account).filter { it.id != id })
    }
}