package com.xjtu.toolbox.widget.smart

import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.widget.WidgetCourse

/**
 * 这一屏到底画什么：**纯函数**，输入是已经读好的事实，输出是 1–2 个块 + 底部一条提示。
 *
 * 两件事在这里定：
 * 1. **哪些事够格上屏、谁在前** —— 就是下面 [candidates] 那张有序清单，逐条按
 *    `docs/smart-widget.md` §3.1 的仲裁表取块，靠前的是「更重要」。
 * 2. **一屏放一个还是两个块** —— 只有前两件事**都算要紧**（都不是 P2 的背景信息）时才放两个，
 *    各占一半；其余情况一个块占满，把细节也给它（见 [SmartCard]）。
 *
 * 每条结论都带着它的「依据」（[SmartEvidence]）与时效，渲染时按依据决定措辞 ——
 * 按时钟推出来的话不许说成确定的事。
 */
internal object SmartArbiter {

    /** 课前多少分钟开始把「下一节」当成「这一堂」。 */
    const val CLASS_LEAD_MIN = 20

    /**
     * 把一次刷卡算进这节课的时间窗时往前放宽多少分钟。
     *
     * 人在上课前几分钟就在楼里刷了，这条刷卡当然属于这堂课 —— 卡得太死会把「提前刷的」
     * 判成没刷，那正是这张卡片最不该犯的错。
     */
    const val SWIPE_WINDOW_BEFORE_MIN = 25

    /**
     * 结论「还是新鲜的」的分界线（分钟）：打卡窗口关的那一刻问过，说「迟到」站得住；
     * 过了十分钟还没再问，中间完全可能补刷了，只能降级成「可能迟到」。
     */
    const val VERIFY_WINDOW_MIN = 10

    /** 待办离截止不到这么久就是 P0（过期即作废）。 */
    const val TODO_P0_MIN = 120

    /** 待办离截止不到这么久算「此刻该知道」。 */
    const val TODO_P1_MIN = 360

    /** 下一节课在这个范围内就值得占一块。 */
    const val NEXT_CLASS_HORIZON_MIN = 120

    /** 下一节还有这么久以上，才算「空档期」。 */
    const val GAP_MIN = 45

    // 考勤状态的语义色：和课表格子上的角标同一套（缺勤红、迟到黄、请假灰、正常蓝）
    private const val COLOR_NORMAL = 0xFF2E6BE6.toInt()
    private const val COLOR_LATE = 0xFFF5A524.toInt()
    private const val COLOR_ABSENT = 0xFFE5484D.toInt()
    private const val COLOR_LEAVE = 0xFF8A9099.toInt()
    private const val COLOR_PENDING = 0xFF8A9099.toInt()

    /** 早、午、晚饭的窗口（距 00:00 的分钟）。 */
    private val MEALS = listOf(
        Triple("早饭时间", 6 * 60 + 30, 9 * 60),
        Triple("午饭时间", 11 * 60, 13 * 60 + 30),
        Triple("晚饭时间", 17 * 60, 19 * 60 + 30),
    )

    /** 座位动作的紧急程度（越靠前越该占块）。 */
    private val SEAT_ACTION_ORDER = listOf("入馆签到", "中途返回", "中途离开", "取消预约")

    /**
     * 判出这一屏。
     *
     * `docs/smart-widget.md` 的仲裁表在这里落成一张**候选清单**（[candidates]，顺序即优先级），
     * 然后按「前两件事都算要紧吗」决定一个块还是两个块。
     */
    fun card(facts: SmartFacts): SmartCard {
        val cands = candidates(facts)
        val first = cands.first()
        val second = cands.getOrNull(1)
        // 两个块的条件：第二件的**重要程度和第一件相近**（级差不超过一档）。
        // 「远不如」的（已查证的迟到 vs 余额）就只占一个块，把细节给它，而不是把半张卡
        // 让给一件可有可无的事。
        val two = second != null && second.rank.ordinal - first.rank.ordinal <= 1
        val blocks = if (two) listOf(first, second) else listOf(first)
        return SmartCard(blocks = blocks, hints = hints(facts, blocks))
    }

    /**
     * 按仲裁表排出所有「够格上屏」的块，靠前的更重要。
     *
     * 顺序就是 `docs/smart-widget.md` §3.1：打卡窗口关了却没刷上 → 两小时内截止的待办 →
     * 待入馆签到 → 当前课 → 六小时内的待办 → 中途返回 → 该出发了 → 下一节 → 饭点 →
     * 课表变动 → 空档期的座位 → 三天内考试 → 放假/没课 → 余额兜底。
     *
     * 每个模块最多出现一次；同一件事不会既占块又当提示。
     */
    internal fun candidates(facts: SmartFacts): List<SmartBlock> {
        val now = facts.nowMinute
        val current = facts.courses.firstOrNull { now >= it.startMinute - CLASS_LEAD_MIN && now < it.endMinute }
        val next = facts.courses.firstOrNull { it.startMinute > now }
        val todo = facts.todos.minByOrNull { it.expiresAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE }
        val todoLeft = todo?.let { minutesLeft(facts, it) } ?: Int.MAX_VALUE
        val outcome = current?.let { attendanceOf(facts, it) }
        val out = mutableListOf<SmartBlock>()

        // 1 打卡窗口已关而且本机没记录：唯一一个 P0 的「此刻不做就出事」。
        if (current != null && outcome != null && outcome.closed && !outcome.recorded && !outcome.swiped) {
            out += classBlock(facts, current, outcome).copy(rank = SmartRank.P0)
        }

        // 2 两小时内截止的待办：过期即作废。
        if (todo != null && todoLeft in 0..TODO_P0_MIN) {
            out += todoBlock(facts, todo).copy(rank = SmartRank.P0)
        }

        // 3 座位「待入馆签到」：硬窗口，丢了就是丢了 —— 排在课前面。
        facts.library?.takeIf { it.active && it.has("入馆签到") }?.let { out += libraryBlock(facts, it) }

        // 4 当前课进行中（含课前二十分钟）。
        if (current != null && outcome != null) out += classBlock(facts, current, outcome)

        // 5 六小时内截止的待办。
        if (todo != null && todoLeft in 0..TODO_P1_MIN) out += todoBlock(facts, todo)

        // 6 其它座位状态（在馆 / 暂离 / 已约未签到之外的）。
        facts.library?.takeIf { it.active }?.let { out += libraryBlock(facts, it) }

        // 7 / 8 下一节课：到了该出发的时刻就染黄，两小时内就占一块。
        if (next != null) {
            val lead = next.startMinute - now
            val walk = facts.walkMinutesTo(next)
            if (walk != null && lead <= walk) {
                out += nextClassBlock(facts, next, walk, leaving = true)
            } else if (lead in 0..NEXT_CLASS_HORIZON_MIN) {
                out += nextClassBlock(facts, next, walk, leaving = false)
            }
        }

        // 9 饭点：有课时它只配当底部那条提示，所以按 P2 排（文档的「课 > 饭点」）。
        MEALS.firstOrNull { (_, start, end) -> now in start until end }?.let { (label, _, _) ->
            out += mealBlock(facts, label).copy(rank = SmartRank.P2)
        }

        // 10 课表变动：学校改课不通知，白跑一趟。
        facts.scheduleDiff?.takeIf { it.isNotBlank() }?.let { diff ->
            out += SmartBlock(
                module = SmartModule.SCHEDULE_DIFF,
                label = "课表有变",
                title = diff,
                rank = SmartRank.P1,
                anchor = now,
                route = AppRoute.Schedule,
            )
        }

        // 11 三天内有考试。
        facts.exam?.let { e ->
            if (e.daysLeft <= 3) {
                out += SmartBlock(
                    module = SmartModule.EXAM,
                    label = "考试",
                    title = e.exam.courseName.ifBlank { "考试" },
                    detail = examDetail(e),
                    status = e.label,
                    rank = SmartRank.P2,
                    anchor = e.daysLeft,
                    route = AppRoute.Schedule,
                )
            }
        }

        // 12 放假 / 今天没课 / 今天的课上完了。
        val holiday = facts.holiday
        if (holiday != null || facts.courses.isEmpty() || facts.courses.all { it.endMinute <= now }) {
            out += holidayBlock(facts, holiday)
        }

        // 13 兜底：余额。
        if (out.isEmpty()) out += balanceBlock(facts)

        // 一个模块只占一个块（保留最先出现、也就是更重要的那个）；顺手挂上它自己的快捷键。
        return out.distinctBy { it.module }.map { it.copy(keys = keysOf(it, facts)) }
    }

    // ══════ 各模块的块 ══════

    /**
     * M1 这一堂课 + 考勤。
     *
     * 细节**随情境变**（§2.4 规则②）：课前要地点（得赶过去），上课中不要（人已经在里面了），
     * 换成「还剩多久」。
     */
    private fun classBlock(facts: SmartFacts, course: WidgetCourse, outcome: AttendanceOutcome): SmartBlock {
        val started = facts.nowMinute >= course.startMinute
        val label = if (started) "上课中" else "${course.startMinute - facts.nowMinute} 分钟后上课"
        val detail = if (started) {
            "还剩 ${(course.endMinute - facts.nowMinute).coerceAtLeast(1)} 分钟"
        } else {
            course.location.ifBlank { timeRange(course) }
        }
        // 第二行细节：上课时间区间（课前那行已经给了地点，上课中那行已经给了还剩多久）
        val detail2 = timeRange(course).takeIf { it.isNotBlank() && it != detail }
            ?: course.location.takeIf { it.isNotBlank() && it != detail }
        return SmartBlock(
            module = SmartModule.ATTENDANCE,
            label = label,
            title = course.name,
            detail = detail,
            detail2 = detail2,
            status = outcome.status,
            statusColor = outcome.color,
            evidence = outcome.evidence,
            meta = outcome.meta,
            rank = SmartRank.P1,
            anchor = course.startMinute,
            route = AppRoute.Attendance,
        )
    }

    /** M2 下一节课 / 该出发了。课表是学期数据不用标时刻；步行是本地推算，写明「步行 X 分钟」。 */
    private fun nextClassBlock(
        facts: SmartFacts,
        course: WidgetCourse,
        walk: Int?,
        leaving: Boolean,
    ): SmartBlock = SmartBlock(
        module = SmartModule.NEXT_CLASS,
        label = "下一节 ${clock(course.startMinute)}",
        title = course.name,
        detail = course.location.ifBlank { timeRange(course) },
        status = if (leaving && walk != null) "该出发了 · 步行 $walk 分钟" else null,
        statusColor = if (leaving) COLOR_LATE else null,
        evidence = if (leaving) SmartEvidence.INFERRED else SmartEvidence.RECORDED,
        meta = if (leaving) "按校内路网估的" else null,
        rank = SmartRank.P1,
        anchor = course.startMinute,
        route = AppRoute.Schedule,
    )

    /**
     * M3 校园卡。饭点与兜底用的是同一个块，**route 一律是校园卡页**，键一律是「付款码」——
     * 块的文字点开就是校园卡，再放一颗「校园卡」图标就是重复入口（这条是真机反馈踩过的）。
     */
    private fun mealBlock(facts: SmartFacts, label: String): SmartBlock = SmartBlock(
        module = SmartModule.CAMPUS_CARD,
        label = label,
        title = facts.balance ?: "--",
        detail = facts.todaySpend?.let { "今日消费 $it" },
        detail2 = facts.balanceClock?.let { "更新于 $it" },
        rank = SmartRank.P1,
        anchor = facts.nowMinute,
        route = AppRoute.CampusCard,
    )

    /** 兜底：课上完了 / 没课可报时，报余额。 */
    private fun balanceBlock(facts: SmartFacts): SmartBlock = SmartBlock(
        module = SmartModule.CAMPUS_CARD,
        label = "校园卡",
        title = facts.balance ?: "余额未同步",
        detail = facts.balanceClock?.let { "更新于 $it" },
        detail2 = facts.todaySpend?.let { "今日消费 $it" },
        rank = SmartRank.P2,
        anchor = facts.nowMinute,
        route = AppRoute.CampusCard,
    )

    /** M4 待办。 */
    private fun todoBlock(facts: SmartFacts, todo: InboxItem): SmartBlock {
        val left = minutesLeft(facts, todo)
        return SmartBlock(
            module = SmartModule.TODO,
            label = if (left <= TODO_P0_MIN) "快截止" else "待办",
            title = todo.title.ifBlank { todo.source.ifBlank { "待办" } },
            detail = todo.source.ifBlank { null },
            // 状态已经写了「还有多久」，第二行给「截止时刻」这类补充事实
            detail2 = if (todo.expiresAt > 0L && left < 24 * 60) {
                "${clock(facts.minuteOf(todo.expiresAt))} 截止"
            } else {
                null
            },
            status = deadlineText(facts, todo),
            statusColor = if (left <= TODO_P0_MIN) COLOR_ABSENT else null,
            rank = if (left <= TODO_P0_MIN) SmartRank.P0 else SmartRank.P1,
            anchor = deadlineMinute(facts, todo),
            route = routeOf(todo),
        )
    }

    /** M5 图书馆：只报**查到过的事实**（座位号 + 区域 + 状态），并说清是何时查到的。 */
    private fun libraryBlock(facts: SmartFacts, lib: LibrarySeatFact): SmartBlock = SmartBlock(
        module = SmartModule.LIBRARY,
        label = "图书馆",
        title = "座位 ${lib.seat}",
        detail = lib.area.ifBlank { null },
        detail2 = lib.actions.takeIf { it.isNotEmpty() }?.let { "可做：${it.first()}" },
        status = lib.status.ifBlank { null },
        statusColor = when (lib.status) {
            "使用中" -> COLOR_NORMAL
            "已预约", "暂离" -> COLOR_LATE
            else -> COLOR_PENDING
        },
        evidence = SmartEvidence.OBSERVED,
        meta = lib.at.takeIf { it > 0L }?.let { "上次查到 ${clock(facts.minuteOf(it))}" },
        rank = SmartRank.P1,
        anchor = facts.minuteOf(lib.at),
        route = AppRoute.Library,
    )

    /** M8 放假 / 今天没课 / 今天的课上完了：三种都是「今天没事」，说法必须分开。 */
    private fun holidayBlock(facts: SmartFacts, holiday: String?): SmartBlock {
        val (label, title, detail, route) = when {
            holiday != null ->
                Four("放假", holiday, "今天不上课", AppRoute.SchoolCalendar)
            facts.courses.isEmpty() ->
                Four("今天没课", facts.balance ?: "余额未同步", "点开 App 同步课表", AppRoute.CampusCard)
            else ->
                Four("今天的课上完了", facts.balance ?: "余额未同步", "明天的课已经排好", AppRoute.CampusCard)
        }
        return SmartBlock(
            module = SmartModule.HOLIDAY,
            label = label,
            title = title,
            detail = detail,
            rank = SmartRank.P2,
            anchor = facts.nowMinute,
            route = route,
        )
    }

    private data class Four(
        val label: String,
        val title: String,
        val detail: String,
        val route: AppRoute,
    )

    // ══════ 底部的次要提示 ══════

    /**
     * 卡片最底下那一条：**只写最短的事实**（地点 + 数字），不与块同源。
     *
     * 位置是「底部条里、刷新图标左边」——那里本来就是空白，正好用来把卡片填满，
     * 而不是像以前那样在块和底栏之间留一大片空。
     *
     * 按级排、级内按时间早的排；真正能放几条由布局按宽度定（[SmartLayout.plan]）。
     */
    internal fun hints(facts: SmartFacts, blocks: List<SmartBlock>): List<SmartHint> {
        val used = blocks.map { it.module }.toSet()
        val out = mutableListOf<SmartHint>()
        val now = facts.nowMinute

        if (SmartModule.TODO !in used) {
            facts.todos.minByOrNull { it.expiresAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE }?.let {
                out += SmartHint(SmartModule.TODO, deadlineText(facts, it))
            }
        }
        if (SmartModule.LIBRARY !in used) {
            facts.library?.takeIf { it.active }?.let { out += SmartHint(SmartModule.LIBRARY, "座位 ${it.seat}") }
        }
        if (SmartModule.EXAM !in used) {
            facts.exam?.takeIf { it.daysLeft <= 7 }?.let {
                out += SmartHint(SmartModule.EXAM, if (it.daysLeft == 0) "今天考试" else "考试 ${it.daysLeft} 天")
            }
        }
        if (SmartModule.CAMPUS_CARD !in used) {
            facts.balance?.let { out += SmartHint(SmartModule.CAMPUS_CARD, "余额 $it") }
        }
        facts.notice?.takeIf { SmartModule.NOTICE !in used }?.let {
            out += SmartHint(SmartModule.NOTICE, it)
        }
        return out.sortedBy { it.module.ordinal }.take(3)
    }

    // ══════ 块里的快捷键 ══════

    /**
     * 一个块里的键：**只放这个块到不了的地方，或一个动作**。
     *
     * 一条结构性保证写在最后：**任何导航键都不许指向这个块自己打开的那个页面** ——
     * 那正是「点文字能到、还多摆一个图标」的重复入口。以前靠约定，现在靠这行过滤。
     *
     * 最多两个（块内那一行只排得下两个 18dp 图标 + 间距）。刷新不在这里 —— 它在底部条右侧。
     */
    fun keysOf(block: SmartBlock, facts: SmartFacts): List<SmartKey> =
        when (block.module) {
            // 点块进考勤页；扫码是它到不了的地方
            SmartModule.ATTENDANCE -> listOf(NavKey(SmartKeyKind.QR, "扫码", AppRoute.QrScan))
            // 点块进日程；查考勤是另一件事
            SmartModule.NEXT_CLASS -> listOf(NavKey(SmartKeyKind.ATTENDANCE, "考勤", AppRoute.Attendance))
            // 点块进那条待办自己；收纳是另一件事（块本身就落在收纳时就不用补了）
            SmartModule.TODO -> if (block.route == AppRoute.Inbox) emptyList()
            else listOf(NavKey(SmartKeyKind.INBOX, "收纳", AppRoute.Inbox))
            // 点块进图书馆页；座位动作要靠落盘的 actionUrls
            SmartModule.LIBRARY -> libraryKeys(facts.library)
            // 点块进校园卡页 ⇒ 唯一的动作键是「付款码」
            SmartModule.CAMPUS_CARD -> listOf(NavKey(SmartKeyKind.PAYMENT, "付款码", AppRoute.PaymentCode))
            // 放假 / 今天没课点块进校历 ⇒ 补日程；「今天的课上完了」点块进校园卡 ⇒ 补付款码
            SmartModule.HOLIDAY -> if (block.route == AppRoute.SchoolCalendar) {
                listOf(NavKey(SmartKeyKind.CALENDAR, "日程", AppRoute.Schedule))
            } else {
                listOf(NavKey(SmartKeyKind.PAYMENT, "付款码", AppRoute.PaymentCode))
            }
            SmartModule.EXAM, SmartModule.SCHEDULE_DIFF, SmartModule.NOTICE -> emptyList()
        }.filterNot { it is NavKey && it.route == block.route }

    /**
     * 图书馆那个块的键：**动作键只能执行「预约当前真的有这个按钮」的那个动作**。
     *
     * 按钮集合来自最近一次查到的 `actionUrls.keys`（落盘见 `LibrarySeatStore`），
     * 查不到就不出现 —— 绝不写死一个学校那边已经没了的按钮。
     */
    private fun libraryKeys(lib: LibrarySeatFact?): List<SmartKey> {
        val fact = lib ?: return emptyList()
        if (!fact.active) return listOf(NavKey(SmartKeyKind.QR, "扫码", AppRoute.QrScan))
        val first = SEAT_ACTION_ORDER.firstOrNull { fact.has(it) } ?: return emptyList()
        val keys = mutableListOf<SmartKey>(seatKey(first))
        if (first != "取消预约" && fact.has("取消预约")) keys += seatKey("取消预约")
        return keys
    }

    private fun seatKey(action: String): SmartKey = SeatKey(
        kind = when (action) {
            "入馆签到" -> SmartKeyKind.CHECKIN
            "中途离开" -> SmartKeyKind.LEAVE
            "中途返回" -> SmartKeyKind.RETURN
            else -> SmartKeyKind.CANCEL
        },
        label = action,
        action = action,
    )

    // ══════ 考勤结论 ══════

    /** 本堂考勤的结论 + 它是什么时候的事实。 */
    internal data class AttendanceOutcome(
        val status: String,
        val color: Int,
        val evidence: SmartEvidence,
        val meta: String?,
        /** 学校已经写下结论（正常/迟到/缺勤/请假）：这条不会再变了。 */
        val recorded: Boolean = false,
        /** 本机见到刷了卡，只是学校还没结算。 */
        val swiped: Boolean = false,
        /** 官方打卡窗口已经关了（`XjtuTime` 的 `attendanceEnd`）。 */
        val closed: Boolean = false,
    )

    /**
     * 这节课的考勤：一句话 + 该染的颜色 + **这条结论的依据**。
     *
     * 四条来源，优先级就是它们可信的程度：
     * 1. 学校写下的记录（[SmartEvidence.RECORDED]）——照实报，不标时刻；
     * 2. 本机见到的刷卡（[SmartEvidence.OBSERVED]）——「已刷卡 · 待结算」，标**刷卡时刻**；
     * 3. 窗口还没关（[SmartEvidence.INFERRED]）——「待考勤」，标查证时刻；
     * 4. 窗口已关且没记录——在临界点查过就是 [SmartEvidence.VERIFIED]（可以直说「迟到」），
     *    没查过 / 太久没查就是 [SmartEvidence.INFERRED] / [SmartEvidence.STALE]（「可能迟到」）。
     *
     * 找记录用日期 + 节次 + 课程号，**不用周次**：两套系统的周次起始日未必同一天，
     * 差一天就整体错一周（见 `SlotAttendanceMarks.recordAt`）。
     */
    internal fun attendanceOf(facts: SmartFacts, course: WidgetCourse): AttendanceOutcome {
        val record = facts.attendance
            ?.takeIf { course.startSection > 0 && facts.date.isNotBlank() }
            ?.recordAt(facts.date, course.startSection, course.endSection, course.name, course.courseCode)
        if (record != null) {
            val (text, color) = when (record.status) {
                WaterType.NORMAL -> "已打卡 · 正常" to COLOR_NORMAL
                WaterType.LATE -> "已打卡 · 迟到" to COLOR_LATE
                WaterType.ABSENCE -> "已打卡 · 缺勤" to COLOR_ABSENT
                WaterType.LEAVE -> "已请假" to COLOR_LEAVE
                WaterType.UNKNOWN -> "已打卡 · 状态未知" to COLOR_LEAVE
            }
            return AttendanceOutcome(text, color, SmartEvidence.RECORDED, meta = null, recorded = true)
        }

        val swipe = facts.swipe?.takeIf {
            it.minuteOfDay >= course.startMinute - SWIPE_WINDOW_BEFORE_MIN && it.minuteOfDay <= course.endMinute
        }
        if (swipe != null) {
            return AttendanceOutcome(
                status = "已刷卡 · 待结算",
                color = COLOR_NORMAL,
                evidence = SmartEvidence.OBSERVED,
                meta = "刷卡 ${swipe.clock}",
                swiped = true,
            )
        }

        val closed = facts.nowMinute >= attendanceEndMinute(course)
        if (!closed) {
            return AttendanceOutcome(
                status = "待考勤",
                color = COLOR_PENDING,
                evidence = SmartEvidence.INFERRED,
                meta = freshness(facts),
                closed = false,
            )
        }
        val ago = facts.checkedAgoMin
        val at = facts.checkedAtClock ?: clockAgo(facts, ago ?: 0)
        return when {
            ago == null -> AttendanceOutcome("可能迟到", COLOR_LATE, SmartEvidence.INFERRED, "还未查询过", closed = true)
            ago <= VERIFY_WINDOW_MIN -> AttendanceOutcome("迟到", COLOR_LATE, SmartEvidence.VERIFIED, "$at 查过", closed = true)
            else -> AttendanceOutcome("可能迟到", COLOR_LATE, SmartEvidence.STALE, "上次查于 $at", closed = true)
        }
    }

    /**
     * 官方打卡窗口关掉的时刻（距 00:00 的分钟）。
     *
     * 取值来自 [XjtuTime] 的 `attendanceEnd`，不是自己写死的 `start + 5`：核对过全部 11 节 ×
     * 冬夏两套表，`attendanceEnd` 恒等于上课时刻 + 5 分钟，正好对上旧的魔数 ——
     * 但学校真改了作息，卡片应当跟着那张表走。
     */
    fun attendanceEndMinute(course: WidgetCourse): Int {
        val t = XjtuTime.getClassTime(course.startSection)?.attendanceEnd
        return if (t != null) t.hour * 60 + t.minute else course.startMinute + 5
    }

    // ══════ 什么时候该联网 ══════

    /** 这一屏要不要在后台问一次学校。 */
    enum class CheckKind {
        /** 不用问：没有课，或者本堂该问的都问过了。 */
        NONE,

        /** **欠查**：本堂课的 Q1/Q2 已经到点，但上次问到的东西比它早 —— 立刻补一次。 */
        DUE,

        /** 保鲜：屏幕上写着一句「迟到」类的话，而它已经超过 [VERIFY_WINDOW_MIN] 分钟。 */
        REFRESH,
    }

    /**
     * 判「此刻这一屏还欠不欠一次查询」。
     *
     * 一节课两个点（上课时刻 Q1、打卡窗口关 Q2），**一节课最多两次，没课一次都没有**。
     * 判据不是「隔多久查一次」，而是「这个点该问的东西，我手上有没有」：
     *
     * - Q2 到了但 `lastCheckAt` 比它早 ⇒ 手上那份是窗口关之前的事实，**必须补**；
     * - 闹钟会失约（关机、省电推迟），所以这个判断要在**每次重画**时都做一遍 ——
     *   即使一个闹钟都没响过，用户看一眼卡片也会把结论查清楚。
     */
    fun checkNeeded(facts: SmartFacts): CheckKind {
        val now = facts.nowMinute
        val current = facts.courses.firstOrNull { now >= it.startMinute - CLASS_LEAD_MIN && now < it.endMinute }
            ?: return CheckKind.NONE
        val dueMinutes = listOf(current.startMinute, attendanceEndMinute(current)).filter { now >= it }
        val latestDue = dueMinutes.maxOrNull()
        if (latestDue != null) {
            val dayStartMs = facts.nowMs - now * 60_000L
            val dueMs = dayStartMs + latestDue * 60_000L
            val last = facts.lastCheckAtMs
            if (last == null || last < dueMs) return CheckKind.DUE
        }
        val outcome = attendanceOf(facts, current)
        val age = facts.checkedAgoMin ?: Int.MAX_VALUE
        val inferred = outcome.evidence == SmartEvidence.VERIFIED ||
            outcome.evidence == SmartEvidence.STALE ||
            (outcome.evidence == SmartEvidence.INFERRED && outcome.status != "待考勤")
        return if (inferred && age > VERIFY_WINDOW_MIN) CheckKind.REFRESH else CheckKind.NONE
    }

    // ══════ 小工具 ══════

    private fun minutesLeft(facts: SmartFacts, todo: InboxItem): Int =
        if (todo.expiresAt <= 0L) Int.MAX_VALUE else ((todo.expiresAt - facts.nowMs) / 60_000L).toInt()

    private fun deadlineMinute(facts: SmartFacts, todo: InboxItem): Int =
        if (todo.expiresAt <= 0L) 24 * 60 else facts.minuteOf(todo.expiresAt)

    /**
     * 待办的截止距离，写成一句能被立刻看懂的话。
     *
     * 一小时以内给分钟（「还有 20 分钟」比「21:00 截止」更催人），当天给时刻，跨天给日期；过了截止报逾期多久，和收纳列表同一套措辞。
     */
    internal fun deadlineText(facts: SmartFacts, todo: InboxItem): String {
        if (todo.expiresAt <= 0L) return "不限时"
        val left = minutesLeft(facts, todo)
        return when {
            left <= 0 -> InboxRules.overdueText(-left * 60_000L)
            left < 60 -> "还有 $left 分钟"
            left < 24 * 60 -> "${clock(facts.minuteOf(todo.expiresAt))} 截止"
            else -> "${(left / (24 * 60))} 天后截止"
        }
    }

    private fun examDetail(e: com.xjtu.toolbox.schedule.ExamCountdown.Next): String {
        val date = "${e.date.month.ordinal + 1}/${e.date.day}"
        val time = e.exam.examTime.substringBefore('-').substringBefore('~').substringBefore('至').trim()
        return listOf(date, time).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 待办的落点：学校那一条自己的路由；没有就退到收纳。 */
    private fun routeOf(todo: InboxItem?): AppRoute =
        todo?.route?.takeIf { it.isNotBlank() }?.let { com.xjtu.toolbox.nav.appRouteOf(it) } ?: AppRoute.Inbox

    private fun freshness(facts: SmartFacts): String {
        val ago = facts.checkedAgoMin ?: return "还未查询过"
        return if (ago <= 0) "刚刚查过" else "$ago 分钟前查过"
    }

    private fun clockAgo(facts: SmartFacts, ago: Int): String =
        clock(((facts.nowMinute - ago) % 1440 + 1440) % 1440)

    internal fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun timeRange(c: WidgetCourse): String =
        "${clock(c.startMinute)}–${clock(c.endMinute)}".takeIf { c.startMinute != c.endMinute }.orEmpty()
}

/**
 * 「从上一节课的楼走到下一节课的楼要几分钟」，用打包在应用里的 OSM 简图（`CampusMaps`）本地算，
 * 零联网、零请求。算不出来（楼名对不上 OSM）返回 null —— 那就**不写步行时间**，绝不编一个数字。
 */
internal fun SmartFacts.walkMinutesTo(course: WidgetCourse): Int? {
    if (course.location.isBlank()) return null
    val from = courses.filter { it.endMinute <= nowMinute }.maxByOrNull { it.endMinute } ?: return null
    if (from.location.isBlank()) return null
    if (from.location == course.location) return 0
    val a = com.xjtu.toolbox.campusmap.CampusMaps.find(from.location)?.second ?: return null
    val b = com.xjtu.toolbox.campusmap.CampusMaps.find(course.location)?.second ?: return null
    return com.xjtu.toolbox.campusmap.CampusMaps.walkMinutes(a, b)
}
