package com.xjtu.toolbox.widget.smart

import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.library.LibrarySeatFact
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.widget.WidgetCourse

/**
 * 这一屏到底画什么：**纯函数**，输入是已经读好的事实，输出是一个块 + 若干钩子 + 三个键。
 *
 * 以前这是一串 if/else，写在 `SmartWidgetUpdater.sceneOf` 里，谁也说不清「为什么这一刻显示的是
 * 余额而不是待办」。现在顺序就是 `docs/smart-widget.md` §3.1 那张仲裁表，逐条短路：
 * **第一个命中的就是主要信息**；命中不了才往下走，最后兜底到余额。
 *
 * 两条写死的取舍（§3.1）：
 * - **课 > 饭点**：缺勤改不了，饭可以晚十分钟吃；
 * - **签到 > 找座**：「待入馆签到」是硬窗口，丢了就是丢了。
 */
internal object SmartArbiter {

    /** 课前多少分钟开始把「下一节」当成「这一堂」。更早的时候卡片回答的是别的问题。 */
    const val CLASS_LEAD_MIN = 20

    /**
     * 把一次刷卡算进这节课的时间窗时，往前放宽多少分钟。
     *
     * 人在上课前几分钟就在楼里刷了，这条刷卡当然属于这堂课 —— 卡得太死会把「提前刷的」
     * 判成没刷，那正是这张卡片最不该犯的错。
     */
    const val SWIPE_WINDOW_BEFORE_MIN = 25

    /**
     * 结论「还是新鲜的」的分界线（分钟）。
     *
     * 打卡窗口关的那一刻去问过，说「迟到」是站得住的；过了十分钟还没再问，中间完全可能补刷了，
     * 那就只能降级成「可能迟到」并触发一次补查。**屏幕上永远有一句说得清的话**，
     * 但不许把一句可能已经过期的断言说成确定的事（§10.10/11）。
     */
    const val VERIFY_WINDOW_MIN = 10

    /** 待办离截止不到这么久就是 P0（过期即作废）。 */
    const val TODO_P0_MIN = 120

    /** 待办离截止不到这么久算「此刻该知道」。 */
    const val TODO_P1_MIN = 360

    /** 下一节课在这个范围内就值得占主要信息。 */
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

    /** 座位动作的紧急程度（越靠前越该占主要信息）。 */
    private val SEAT_ACTION_ORDER = listOf("入馆签到", "中途返回", "中途离开", "取消预约")

    /**
     * 按仲裁表判出这一屏。
     *
     * 顺序即优先级，每一段前面的注释就是 §3.1 里那条规则；想改优先级就改这里的顺序，
     * 不要再往别处塞特例——单测 [com.xjtu.toolbox.widget.SmartWidgetBoundaryTest] 按这张表断言。
     */
    fun card(facts: SmartFacts): SmartCard {
        val now = facts.nowMinute
        val current = facts.courses.firstOrNull { now >= it.startMinute - CLASS_LEAD_MIN && now < it.endMinute }
        val next = facts.courses.firstOrNull { it.startMinute > now }
        val todo = facts.todos.minByOrNull { it.expiresAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE }
        val outcome = current?.let { attendanceOf(facts, it) }

        // 1 / 1b 打卡窗口已关，而且本机没有记录：迟到是**已查证的事实**还是**还没查过的推断**，
        //        由依据决定措辞（«迟到» / «可能迟到»），不由礼貌程度决定。
        if (current != null && outcome != null && outcome.closed && !outcome.recorded && !outcome.swiped) {
            return cardOf(classBlock(facts, current, outcome).copy(rank = SmartRank.P0), facts)
        }

        // 2 待办两小时内截止：过期即作废，压过一切可缓的。
        todo?.let { t ->
            val left = minutesLeft(facts, t)
            if (left in 0..TODO_P0_MIN) return cardOf(todoBlock(facts, t).copy(rank = SmartRank.P0), facts)
        }

        // 3 座位「待入馆签到」：硬窗口，丢了就是丢了。
        //   ⚠️ 卡片算不出那 30 分钟窗口（`MyBookingInfo` 不带预约时刻，见 §11 前置 2），
        //   所以只按 P1 排、不敢按 P0 插到课前面去——宁可少喊一句，也不虚报紧急。
        facts.library?.let { lib: LibrarySeatFact ->
            if (lib.active && lib.has("入馆签到")) {
                return cardOf(libraryBlock(facts, lib), facts)
            }
        }

        // 4 当前课进行中（含课前二十分钟）。
        if (current != null && outcome != null) return cardOf(classBlock(facts, current, outcome), facts)

        // 5 待办六小时内截止。
        todo?.let { t ->
            val left = minutesLeft(facts, t)
            if (left in 0..TODO_P1_MIN) return cardOf(todoBlock(facts, t), facts)
        }

        // 6 座位「中途返回」：人已经约上了，只差回馆。
        facts.library?.let { lib ->
            if (lib.active && lib.has("中途返回")) return cardOf(libraryBlock(facts, lib), facts)
        }

        // 7 / 8 下一节课：到了该出发的时刻就染黄，两小时内就占主要信息。
        if (next != null) {
            val lead = next.startMinute - now
            val walk = facts.walkMinutesTo(next)
            if (walk != null && lead <= walk) return cardOf(nextClassBlock(facts, next, walk, leaving = true), facts)
            if (lead in 0..NEXT_CLASS_HORIZON_MIN) return cardOf(nextClassBlock(facts, next, walk, leaving = false), facts)
        }

        // 9 饭点：课优先（上面已经短路），到这里说明没课。
        MEALS.firstOrNull { (_, start, end) -> now in start until end }?.let { (label, _, _) ->
            return cardOf(mealBlock(facts, label).copy(rank = SmartRank.P1), facts)
        }

        // 10 课表变动：学校改课不通知，白跑一趟。
        facts.scheduleDiff?.takeIf { it.isNotBlank() }?.let { diff ->
            return cardOf(
                SmartBlock(
                    module = SmartModule.SCHEDULE_DIFF,
                    label = "课表有变",
                    title = diff,
                    rank = SmartRank.P1,
                    anchor = now,
                    route = AppRoute.Schedule,
                ),
                facts,
            )
        }

        // 11 空档期 + 有座位事实：只有真的有预约才画，不画「没有座位」。
        facts.library?.let { lib ->
            val idle = next == null || next.startMinute - now >= GAP_MIN
            if (idle && lib.active) return cardOf(libraryBlock(facts, lib), facts)
        }

        // 12 三天内有考试。
        facts.exam?.let { e ->
            if (e.daysLeft <= 3) {
                return cardOf(
                    SmartBlock(
                        module = SmartModule.EXAM,
                        label = "考试",
                        // 教务偶尔给空课名：这一行是标题，空着就等于这个块没有主语
                        title = e.exam.courseName.ifBlank { "考试" },
                        detail = examDetail(e),
                        status = e.label,
                        rank = SmartRank.P2,
                        anchor = e.daysLeft,
                        route = AppRoute.Schedule,
                    ),
                    facts,
                )
            }
        }

        // 13 放假 / 今天没课。
        val holiday = facts.holiday
        if (holiday != null || facts.courses.isEmpty()) {
            return cardOf(holidayBlock(facts, holiday), facts)
        }

        // 14 兜底：今天课上完了，报余额。
        return cardOf(holidayBlock(facts, null), facts)
    }

    // ══════ 各模块的块 ══════

    /**
     * M1 这一堂课 + 考勤。
     *
     * 细节**随情境变**（§2.4 规则②）：课前要地点（得赶过去），上课中不要（人已经在里面了），
     * 换成「还剩多久」。省下来的这一行正好够多写一条钩子。
     */
    private fun classBlock(facts: SmartFacts, course: WidgetCourse, outcome: AttendanceOutcome): SmartBlock {
        val started = facts.nowMinute >= course.startMinute
        val label = if (started) "上课中" else "${course.startMinute - facts.nowMinute} 分钟后上课"
        val detail = if (started) {
            "还剩 ${(course.endMinute - facts.nowMinute).coerceAtLeast(1)} 分钟"
        } else {
            course.location.ifBlank { timeRange(course) }
        }
        return SmartBlock(
            module = SmartModule.ATTENDANCE,
            label = label,
            title = course.name,
            detail = detail,
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

    /** M3 校园卡：饭点报余额并给付款码入口；余额那份缓存带更新时刻，照实标。 */
    private fun mealBlock(facts: SmartFacts, label: String): SmartBlock = SmartBlock(
        module = SmartModule.CAMPUS_CARD,
        label = label,
        title = facts.balance ?: "--",
        detail = listOfNotNull(
            facts.todaySpend?.let { "今日消费 $it" },
            facts.balanceClock?.let { "更新于 $it" },
        ).joinToString(" · ").ifBlank { null },
        rank = SmartRank.P2,
        anchor = facts.nowMinute,
        route = AppRoute.PaymentCode,
    )

    /** M4 待办：截止时间本身就是时效，不用再标「查于几点」。 */
    private fun todoBlock(facts: SmartFacts, todo: InboxItem): SmartBlock {
        val left = minutesLeft(facts, todo)
        return SmartBlock(
            module = SmartModule.TODO,
            label = if (left <= TODO_P0_MIN) "快截止" else "待办",
            // 学校那一条没有标题时给一个能读的主语，别在桌面上留一行空标题
            title = todo.title.ifBlank { todo.source.ifBlank { "待办" } },
            detail = todo.source.ifBlank { null },
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
        val (label, title, detail) = when {
            holiday != null -> Triple("放假", holiday, "今天不上课")
            facts.courses.isEmpty() -> Triple("今天没课", facts.balance?.let { "校园卡 $it" } ?: "校园卡余额未同步", "饭点会自动换成付款码入口")
            else -> Triple("今天的课上完了", facts.balance?.let { "校园卡 $it" } ?: "校园卡余额未同步", "明天的课已经排好")
        }
        return SmartBlock(
            module = if (holiday != null || facts.courses.isEmpty()) SmartModule.HOLIDAY else SmartModule.CAMPUS_CARD,
            label = label,
            title = title,
            detail = detail,
            rank = SmartRank.P2,
            anchor = facts.nowMinute,
            route = if (holiday != null) AppRoute.SchoolCalendar else AppRoute.CampusCard,
        )
    }

    // ══════ 钩子 ══════

    /**
     * 次要信息：只写钩子（地点 + 数字），不写句子；**不许和主要信息同源**（§2.6.4）。
     *
     * 排法：先按级、级内按时间早的；同一条文字只出现一次。真正能放几条由布局预算决定
     * （见 [SmartLayout.plan]），这里按「最该被看到」的顺序给全。
     */
    fun hooks(facts: SmartFacts, block: SmartBlock): List<SmartHook> {
        val out = mutableListOf<SmartHook>()
        val now = facts.nowMinute

        facts.todos.minByOrNull { it.expiresAt.takeIf { e -> e > 0 } ?: Long.MAX_VALUE }?.let { t ->
            if (block.module != SmartModule.TODO) {
                out += SmartHook(SmartModule.TODO, deadlineText(facts, t), SmartRank.P1, deadlineMinute(facts, t))
            }
        }
        facts.library?.takeIf { it.active && block.module != SmartModule.LIBRARY }?.let {
            out += SmartHook(SmartModule.LIBRARY, "座位 ${it.seat}", SmartRank.P2, it.seat.length)
        }
        facts.exam?.takeIf { it.daysLeft <= 7 && block.module != SmartModule.EXAM }?.let {
            val text = if (it.daysLeft == 0) "今天考试" else "考试 ${it.daysLeft} 天"
            out += SmartHook(SmartModule.EXAM, text, SmartRank.P2, it.daysLeft + 1000)
        }
        facts.balance?.takeIf { block.module != SmartModule.CAMPUS_CARD }?.let {
            out += SmartHook(SmartModule.CAMPUS_CARD, "余额 $it", SmartRank.P2, 2000 + now / 1000)
        }
        facts.notice?.takeIf { block.module != SmartModule.NOTICE }?.let {
            out += SmartHook(SmartModule.NOTICE, it, SmartRank.P2, 3000)
        }
        return out
            .distinctBy { it.module }
            .sortedWith(compareBy({ it.rank.ordinal }, { it.anchor }))
    }

    // ══════ 底栏快捷键 ══════

    /**
     * 底栏放什么键。
     *
     * 一条铁律先立住：**点那块内容能到的地方，底栏就不许再放一个图标。**
     * 以前上课那一屏同时有「点标题进考勤页」和一颗「考勤」图标、下一节那一屏同时有
     * 「点标题进日程」和一颗「日程」图标 —— 一个目的地两个入口，纯属占地方。
     * 所以这里每一个键都必须是**内容块到不了**的那个地方，或者是一个**动作**：
     *
     * | 主要信息 | 点它去哪 | 底栏还缺什么 |
     * |---|---|---|
     * | 这一堂课 | 考勤页 | 扫码、刷新（结论已是定论时刷新没意义） |
     * | 下一节 | 日程 | 查考勤 |
     * | 饭点余额 | 付款码 | 校园卡 |
     * | 课上完了 | 校园卡 | 付款码 |
     * | 待办 | 那条待办自己 | 收纳 |
     * | 图书馆 | 图书馆 | 座位动作 / 扫码 |
     * | 考试、课表变动 | 日程 | 只有刷新 |
     * | 放假 | 校历 | 日程 |
     *
     * 「刷新」固定占最右，它是每个场景都可能要的动作，位置不能变。
     */
    fun keysOf(facts: SmartFacts, block: SmartBlock): List<SmartKey> = when (block.module) {
        // 点块就进考勤页，所以只补「扫码」这个到不了的地方；结论已经是学校写下的定论时，
        // 刷新也刷不出新东西，那个位置干脆空着
        SmartModule.ATTENDANCE -> if (block.evidence == SmartEvidence.RECORDED) {
            listOf(NavKey(SmartKeyKind.QR, "扫码", AppRoute.QrScan))
        } else {
            listOf(NavKey(SmartKeyKind.QR, "扫码", AppRoute.QrScan), RefreshKey())
        }
        SmartModule.NEXT_CLASS -> listOf(
            NavKey(SmartKeyKind.ATTENDANCE, "考勤", AppRoute.Attendance),
            RefreshKey(),
        )
        // 点块进的是那条待办自己的页面；收纳是另一件事
        SmartModule.TODO -> if (block.route == AppRoute.Inbox) {
            listOf(RefreshKey())
        } else {
            listOf(NavKey(SmartKeyKind.INBOX, "收纳", AppRoute.Inbox), RefreshKey())
        }
        SmartModule.LIBRARY -> libraryKeys(facts.library)
        // 饭点那一屏点块就打开付款码，所以补的是校园卡；反过来兜底那一屏补付款码
        SmartModule.CAMPUS_CARD -> if (block.route == AppRoute.PaymentCode) {
            listOf(NavKey(SmartKeyKind.CARD, "校园卡", AppRoute.CampusCard), RefreshKey())
        } else {
            listOf(NavKey(SmartKeyKind.PAYMENT, "付款码", AppRoute.PaymentCode), RefreshKey())
        }
        // 考试、课表变动：点块进日程，日程那一栏就是它自己，没有别的去处
        SmartModule.EXAM, SmartModule.SCHEDULE_DIFF -> listOf(RefreshKey())
        SmartModule.HOLIDAY -> listOf(
            NavKey(SmartKeyKind.CALENDAR, "日程", AppRoute.Schedule),
            RefreshKey(),
        )
        SmartModule.NOTICE -> listOf(
            NavKey(SmartKeyKind.INBOX, "通知", AppRoute.Notification),
            RefreshKey(),
        )
    }

    /**
     * 图书馆那一屏的键：**动作键只能执行「预约当前真的有这个按钮」的那个**。
     *
     * 按钮集合来自最近一次查到的 `actionUrls.keys`（落盘见 `LibrarySeatStore`），
     * 查不到就不出现 —— 绝不写死一个学校那边已经没了的按钮。
     *
     * 没约座（空档期）时只补「扫码」：选座就是点这块本身去的地方（图书馆页），不必再来一个图标。
     */
    private fun libraryKeys(lib: LibrarySeatFact?): List<SmartKey> {
        if (lib == null || !lib.active) {
            return listOf(NavKey(SmartKeyKind.QR, "扫码", AppRoute.QrScan), RefreshKey())
        }
        val first = SEAT_ACTION_ORDER.firstOrNull { lib.has(it) }
        val keys = mutableListOf<SmartKey>()
        if (first != null) keys += seatKey(first)
        if (first != "取消预约" && lib.has("取消预约")) keys += seatKey("取消预约")
        keys += RefreshKey()
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

    // ══════ 考勤结论（§1.4 / §10） ══════

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
     * 2. 本机见到的刷卡（[SmartEvidence.OBSERVED]）——「已刷卡 · 待结算」，标**刷卡时刻**
     *    （这是最要紧的一个状态：它把「明明刷了却显示待考勤」消掉了，用户不必再白跑一趟）；
     * 3. 窗口还没关（[SmartEvidence.INFERRED]）——「待考勤」，标查证时刻；
     * 4. 窗口已关且没记录——在临界点查过就是 [SmartEvidence.VERIFIED]（可以直说「迟到」），
     *    没查过 / 太久没查就是 [SmartEvidence.INFERRED] / [SmartEvidence.STALE]（「可能迟到」）。
     *
     * 找记录用日期 + 节次 + 课程号，**不用周次**：两套系统的周次起始日未必同一天，
     * 差一天就整体错一周，于是明明记了考勤却查不到（见 `SlotAttendanceMarks.recordAt`）。
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
                // 未识别的状态码不冒充正常，也不吓人，原样说「有记录」
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
                // 标的是**刷卡**时刻，不是「刚刚查过」：两者含义不同（§10.3）
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
            // 还没问过：措辞必须降级，不能硬下结论
            ago == null -> AttendanceOutcome("可能迟到", COLOR_LATE, SmartEvidence.INFERRED, "还未查询过", closed = true)
            // 临界点上刚查过：这一刻它掌握的事实和学校掌握的是同一件事，可以直说
            ago <= VERIFY_WINDOW_MIN -> AttendanceOutcome("迟到", COLOR_LATE, SmartEvidence.VERIFIED, "$at 查过", closed = true)
            // 查过但已经太久了：中间完全可能补刷了
            else -> AttendanceOutcome("可能迟到", COLOR_LATE, SmartEvidence.STALE, "上次查于 $at", closed = true)
        }
    }

    /**
     * 官方打卡窗口关掉的时刻（距 00:00 的分钟）。
     *
     * 取值来自 [XjtuTime] 的 `attendanceEnd`，不是自己写死的 `start + 5`：核对过全部 11 节 ×
     * 冬夏两套表，`attendanceEnd` 恒等于上课时刻 + 5 分钟，正好对上旧的魔数 ——
     * 但学校真改了作息，卡片应当跟着那张表走，而不是跟着一个魔数走（§11 第 12 条）。
     */
    fun attendanceEndMinute(course: WidgetCourse): Int {
        val t = XjtuTime.getClassTime(course.startSection)?.attendanceEnd
        return if (t != null) t.hour * 60 + t.minute else course.startMinute + 5
    }

    // ══════ 什么时候该联网（§6.3） ══════

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
     * 只有一节课两个点（上课时刻 Q1、打卡窗口关 Q2），**一节课最多两次，没课一次都没有**。
     * 这里的关键不是「隔多久查一次」，而是「这个点该问的东西，我手上有没有」：
     *
     * - Q2 到了但 `lastCheckAt` 比它早 ⇒ 手上那份是窗口关之前的事实，**必须补**；
     * - 闹钟会失约（关机、省电推迟），所以这个判断要在**每次重画**时都做一遍 ——
     *   即使一个闹钟都没响过，用户看一眼卡片也会把结论查清楚。
     *
     * 判据（§6.3「一条通用判据」）：这个点查回来的东西会比上一次多吗？不会多就不排。
     * 窗口刚开不排（学校还没开始记考勤，查回来必然是空的）；下课前不排（结算时机不确定）。
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
        // 保鲜：结论是推断/查证/过期的那一类，而且已经超过阈值 ⇒ 用户此刻在看，值得为他多问一次
        val outcome = attendanceOf(facts, current)
        val age = facts.checkedAgoMin ?: Int.MAX_VALUE
        val inferred = outcome.evidence == SmartEvidence.VERIFIED ||
            outcome.evidence == SmartEvidence.STALE ||
            (outcome.evidence == SmartEvidence.INFERRED && outcome.status != "待考勤")
        return if (inferred && age > VERIFY_WINDOW_MIN) CheckKind.REFRESH else CheckKind.NONE
    }

    // ══════ 小工具 ══════

    /** 把块 + 钩子 + 键凑成一屏。 */
    private fun cardOf(block: SmartBlock, facts: SmartFacts): SmartCard =
        SmartCard(block = block, hooks = hooks(facts, block), keys = keysOf(facts, block))

    private fun minutesLeft(facts: SmartFacts, todo: InboxItem): Int =
        if (todo.expiresAt <= 0L) Int.MAX_VALUE else ((todo.expiresAt - facts.nowMs) / 60_000L).toInt()

    private fun deadlineMinute(facts: SmartFacts, todo: InboxItem): Int =
        if (todo.expiresAt <= 0L) 24 * 60 else facts.minuteOf(todo.expiresAt)

    /**
     * 待办的截止距离，写成一句能被立刻看懂的话。
     *
     * 一小时以内给分钟（「还有 20 分钟」比「21:00 截止」更催人），当天给时刻，跨天给日期。
     */
    internal fun deadlineText(facts: SmartFacts, todo: InboxItem): String {
        if (todo.expiresAt <= 0L) return "不限时"
        val left = minutesLeft(facts, todo)
        return when {
            left <= 0 -> "已截止"
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

    /** 状态那一行的时效说明：哪一刻的事实。 */
    private fun freshness(facts: SmartFacts): String {
        val ago = facts.checkedAgoMin ?: return "还未查询过"
        return when {
            ago <= 0 -> "刚刚查过"
            else -> "$ago 分钟前查过"
        }
    }

    private fun clockAgo(facts: SmartFacts, ago: Int): String =
        clock(((facts.nowMinute - ago) % 1440 + 1440) % 1440)

    internal fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun timeRange(c: WidgetCourse): String =
        "${clock(c.startMinute)}–${clock(c.endMinute)}".takeIf { c.startMinute != c.endMinute }.orEmpty()
}

/**
 * 「从上一节课的楼走到下一节课的楼要几分钟」，用打包在应用里的 OSM 简图（[CampusMaps]）本地算，
 * 零联网、零请求。算不出来（楼名对不上 OSM）返回 null —— 那就**不写步行时间**，
 * 绝不编一个数字上去（§10.8）。
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

/** 「刷新」键：只打考勤这一枪，不打开 App（`ACTION_REFRESH_NOW`）。 */
data class RefreshKey(override val label: String = "刷新") : SmartKey {
    override val kind: SmartKeyKind get() = SmartKeyKind.REFRESH
}
