package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.agent.AgentCapability
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.agent.agentCalculate
import com.xjtu.toolbox.agent.agentCourseLine
import com.xjtu.toolbox.agent.agentGuideText
import com.xjtu.toolbox.agent.agentToolDefinitions
import com.xjtu.toolbox.agent.argInt
import com.xjtu.toolbox.agent.argStr
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarSource
import com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS
import com.xjtu.toolbox.emptyroom.EmptyRoomApi
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.faculty.FacultySearchQuery
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.todayOnCampus
import com.xjtu.toolbox.yellowpage.YellowPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桌面这一端真会照着办的工具名单 —— 工具表就是按它裁出来的。
 *
 * B-8 之前只有五件（黄页、偏好、教师、引导、算一算），比 serve 少四件且都是「这一端没有数据」那四件。
 * 现在补齐到与 serve **同一档同一顺序**（`ServeAgentTools.NAMES`）：多出来的四件读的都是手边现成的源——
 * 校历（`SchoolCalendarApi`，免登录）、课表与考试（本机 `ScheduleCache`，日程屏刷卡时写进去的同一份）、
 * 空教室（`EmptyRoomApi`，CDN 每日预生成，免登录）。一件也没有新造取数通路。
 */
internal val DESKTOP_AGENT_TOOLS = listOf(
    // 顺序与 `:core` 目录里的那几档一致（`:core` 那份是**唯一**一份顺序定义，这里只是筛子）：
    // 裁完送给模型的顺序就与 `:app` / serve 那边逐档相同。
    "get_calendar",
    "get_schedule",
    "get_exam_schedule",
    "get_empty_rooms",
    "search_yellow_page",
    "preference",
    "find_faculty",
    "app_guide",
    "calculate",
)

/** 桌面这一端送给模型的工具表：`:core` 那份描述表按 [DESKTOP_AGENT_TOOLS] 裁一刀。 */
internal fun desktopAgentToolDefinitions(honored: Set<AgentCapability>): String {
    val drawn = (AppJson.parseToJsonElement(agentToolDefinitions(honored)) as JsonArray)
        .filter { element ->
            element.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content in DESKTOP_AGENT_TOOLS
        }
    return JsonArray(drawn).toString()
}

/** 桌面端的工具层（`:core` 的 [AgentTools] 在这一端的实现）。 */
internal class DesktopAgentTools(
    honored: Set<AgentCapability>,
    private val memory: AgentMemory,
    private val yellowPage: YellowPageSource,
    private val faculty: FacultyApi,
    /**
     * 课表族那三件读的那格缓存：与日程屏 / 首页**同一个账号命名空间**（`DesktopScheduleHost.cache`）。
     * 桌面没有教务取数通路（那是共享日程屏在刷），所以这里只读已经落地的那一份。
     */
    private val cache: ScheduleCacheStore,
    /** 校历（免登录，与校历屏同一个源）。 */
    private val calendar: SchoolCalendarSource = SchoolCalendarApi(),
    /** 空教室（CDN 每日预生成，免登录）。 */
    private val emptyRoom: EmptyRoomApi = EmptyRoomApi(),
) : AgentTools {

    override val toolDefinitions: String = desktopAgentToolDefinitions(honored)

    /** 限流分组键：同一个后端系统的两次调用之间要隔开一点，别连着猛打学校服务器。 */
    override fun rateLimitKeyOf(toolName: String): String? = when (toolName) {
        "search_yellow_page" -> "yellow_page"
        "find_faculty" -> "faculty"
        // 校历与空教室都是校外 CDN，不带站点会话；仍旧归一组，别连着猛拉同一家。
        "get_calendar", "get_empty_rooms" -> "cdn"
        else -> null
    }

    /** 记住的用户偏好，拼进系统提示。走工具层是因为 ViewModel 手上只有这一格缝。 */
    override fun memoryBlock(): String = memory.promptBlock()

    override suspend fun execute(name: String, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching { AppJson.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        when (name) {
            "get_calendar" -> getSchoolCalendarText(args.argStr("term"))
            "get_schedule" -> scheduleReply(args.argStr("date"), args.argStr("term"))
            "get_exam_schedule" -> examReply(args.argStr("term"))
            "get_empty_rooms" -> emptyRoomsReply(
                campus = args.argStr("campus"),
                building = args.argStr("building"),
                section = args.argInt("section"),
                date = args.argStr("date"),
            )
            "calculate" -> agentCalculate(args.argStr("expression").orEmpty())
            "app_guide" -> agentGuideText(args.argStr("topic"))
            // 给 value 是记下，不给是删掉（与 `:app` 同一条判据）
            "preference" -> {
                val key = args.argStr("key") ?: ""
                val value = args.argStr("value").orEmpty()
                if (value.isBlank()) memory.forget(key) else memory.remember(key, value)
            }
            "search_yellow_page" -> searchYellowPage(
                query = args.argStr("query"),
                category = args.argStr("category"),
                limit = args.argInt("limit") ?: 10,
            )
            "find_faculty" -> findFaculty(
                name = args.argStr("name") ?: "",
                college = args.argStr("college"),
                limit = args.argInt("limit") ?: 3,
            )
            // 工具表里没画的档回 not_found：模型可能凭旧对话里的记忆调过来（那段历史是在别的端上生成的）。
            else -> ToolReply.notFound("tool", name)
        }
    }

    /** 校园黄页：机构总机电话，不含个人号码。 */
    private suspend fun searchYellowPage(query: String?, category: String?, limit: Int): String {
        return try {
            val data = yellowPage.getData()
            val categoryId = category?.takeIf { it.isNotBlank() }?.let { name ->
                data.categories.firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.id ?: return ToolReply.notFound("category", name, data.categories.map { it.name })
            }
            val keyword = query.orEmpty().trim()
            val result = data.departments.asSequence()
                .filter { categoryId == null || it.categoryId == categoryId }
                .filter {
                    keyword.isBlank() ||
                        it.name.contains(keyword, ignoreCase = true) ||
                        it.phone.contains(keyword, ignoreCase = true)
                }
                .take(limit.coerceIn(1, 20))
                .toList()
            if (result.isEmpty()) return ToolReply.empty("yellow_page")
            buildString {
                append("校园黄页")
                if (data.updateTime.isNotBlank()) append("（更新于${data.updateTime}）")
                append("：\n")
                result.forEach { department ->
                    val categoryName = data.categories.firstOrNull { it.id == department.categoryId }?.name
                    append("${department.name}｜${department.phone}")
                    categoryName?.let { append("｜$it") }
                    append("\n")
                }
                if (result.size == limit.coerceIn(1, 20)) append("（已按 limit 截断）")
            }.trimEnd()
        } catch (e: Exception) {
            ToolReply.failed("search_yellow_page", e.message)
        }
    }

    /** 教师主页检索：只给本校在职教师，不含学生 / 行政人员 / 校外人士。 */
    private suspend fun findFaculty(name: String, college: String?, limit: Int): String {
        if (name.isBlank()) return ToolReply.missing("name")
        val n = limit.coerceIn(1, 8)
        val all = try {
            faculty.searchAll(query = FacultySearchQuery(name = name), limit = 60)
        } catch (e: Exception) {
            return ToolReply.failed("find_faculty", e.message)
        }
        val members = college?.takeIf { it.isNotBlank() }
            ?.let { c -> all.filter { it.collegeName.contains(c) }.ifEmpty { all } }
            ?: all
        if (members.isEmpty()) return ToolReply.empty("name: $name")

        val exact = members.filter { it.name == name }
        val picked = (if (exact.isNotEmpty()) exact else members)
            .sortedByDescending { it.clickTimes }
            .take(n)

        return buildString {
            if (exact.isEmpty()) {
                append("无同名，相近结果：\n")
            } else if (exact.size > 1) {
                append("有 ${exact.size} 位同名老师：\n")
            }
            picked.forEachIndexed { i, m ->
                if (i > 0) append("\n")
                append("【${m.name}】")
                listOfNotNull(
                    m.collegeName.takeIf { it.isNotBlank() },
                    m.proRank.takeIf { it.isNotBlank() },
                    m.tutorLabel.takeIf { it.isNotBlank() },
                ).takeIf { it.isNotEmpty() }?.let { append(" " + it.joinToString(" · ")) }
                append("\n")
                m.researchDirections.takeIf { it.isNotEmpty() }?.let {
                    append("研究方向：${it.joinToString("、")}\n")
                }
                m.officeLocation.takeIf { it.isNotBlank() }?.let { append("办公地点：$it\n") }
                m.email.takeIf { it.isNotBlank() }?.let { append("邮箱：$it\n") }
                listOfNotNull(
                    m.phone.takeIf { it.isNotBlank() },
                    m.mobilePhone.takeIf { it.isNotBlank() },
                    m.contact.takeIf { it.isNotBlank() },
                ).distinct().takeIf { it.isNotEmpty() }?.let {
                    append("电话：${it.joinToString(" / ")}\n")
                }
                m.homepageUrl.takeIf { it.isNotBlank() }?.let { append("主页：$it\n") }
                m.profile.takeIf { it.isNotBlank() }?.let {
                    append("简介：${it.replace(Regex("\\s+"), " ").take(200)}\n")
                }
            }
        }.trim()
    }

    // ── 课表族四件（B-8：与 serve 同档。前两件读校外免登录源，后两件读本机缓存）─────────────

    /** 校历：与校历屏同一个源（免登录 CDN），不验登录、不碰缓存。 */
    private suspend fun getSchoolCalendarText(term: String?): String = try {
        val terms = calendar.terms()
        if (terms.isEmpty()) return ToolReply.empty("school_calendar")
        val today = todayOnCampus()
        val picked = if (term.isNullOrBlank()) {
            terms.firstOrNull { today >= it.startDate && today <= it.endDate }
                ?: terms.lastOrNull { it.startDate <= today }
                ?: terms.last()
        } else {
            terms.firstOrNull {
                it.id.contains(term, ignoreCase = true) ||
                    it.termName.contains(term, ignoreCase = true) ||
                    it.yearName.contains(term, ignoreCase = true)
            } ?: return ToolReply.notFound("term", term, terms.takeLast(6).map { it.termName })
        }
        val week = picked.currentWeek(today)
        buildString {
            append("${picked.termName}：${picked.startDate} 至 ${picked.endDate}，共${picked.totalWeeks}周")
            if (week > 0) append("；今天是第${week}周（第${picked.currentDay(today)}天）")
            append("\n")
            val event = picked.todayEvent(today)
            if (event != null) append("今天：${event.name}${if (event.remark.isNotBlank()) "（${event.remark}）" else ""}\n")
            if (picked.events.isEmpty()) {
                append("没有登记假期/节点")
            } else {
                append("假期与节点：\n")
                picked.events.forEach { e ->
                    append("• ${e.name}：${e.startDate}")
                    if (e.endDate != e.startDate) append(" 至 ${e.endDate}")
                    if (e.days > 0) append("（${e.days}天）")
                    if (e.remark.isNotBlank()) append("；${e.remark}")
                    append("\n")
                }
            }
        }.trimEnd()
    } catch (e: Exception) {
        ToolReply.failed("get_calendar", e.message)
    }

    /**
     * 课表（本机缓存）。
     *
     * 桌面这一端**没有教务取数通路**（刷卡是共享日程屏干的），所以这里不联网：
     * 缓存里没有就把「去哪儿刷一次」说清楚，而不是假装能拉。
     */
    private fun scheduleReply(date: String?, term: String?): String {
        val termCode = term?.takeIf { it.isNotBlank() } ?: ScheduleCache.readCurrentTerm(cache)
            ?: return CACHE_MISS_SCHEDULE
        val courses = ScheduleCache.readCourses(cache, termCode) ?: return CACHE_MISS_SCHEDULE
        if (courses.isEmpty()) return ToolReply.empty("schedule; term: $termCode")
        val start = ScheduleCache.readStartDate(cache, termCode)
            ?: return "缓存里没有 $termCode 的开学日期（日程页再刷一次就有），算不出第几周。"
        val today = todayOnCampus()
        val dayNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

        if (!date.isNullOrBlank()) {
            val target = runCatching { LocalDate.parse(date.trim()) }.getOrElse { today }
            val week = TermWeeks.weekOf(start, target)
            val summer = XjtuTime.isSummerTime(target.monthNumber)
            val day = target.dayOfWeek.isoDayNumber
            val todayCourses = courses.filter { it.dayOfWeek == day && it.isInWeek(week) }
                .sortedBy { it.clockMinutes(summer).first }
            val head = "$target ${dayNames.getOrElse(day) { "" }}｜第${week}周"
            if (todayCourses.isEmpty()) return "$head｜无课"
            return buildString {
                append(head).append("\n")
                todayCourses.forEach { append(agentCourseLine(it, summer, withTeacher = true)).append('\n') }
            }.trimEnd()
        }

        val week = TermWeeks.weekOf(start, today)
        if (week < 1) return "当前不在学期内（$termCode，开学 $start）。"
        val monday = LocalDate.fromEpochDays(
            start.toEpochDays() + 7L * (week - 1) - (start.dayOfWeek.isoDayNumber - 1),
        )
        return buildString {
            append("$termCode｜第${week}周（$monday 起）：\n")
            var any = false
            for (d in 1..7) {
                val dayDate = LocalDate.fromEpochDays(monday.toEpochDays() + (d - 1))
                val summer = XjtuTime.isSummerTime(dayDate.monthNumber)
                val list = courses.filter { it.dayOfWeek == d && it.isInWeek(week) }
                    .sortedBy { it.clockMinutes(summer).first }
                if (list.isEmpty()) continue
                any = true
                append("${dayNames[d]}：\n")
                list.forEach { append(agentCourseLine(it, summer, withTeacher = true)).append('\n') }
            }
            if (!any) append("这周没有课")
        }.trimEnd()
    }

    /** 考试安排（本机缓存：日程屏里考试那一块刷出来之后写的就是这一份）。 */
    private fun examReply(term: String?): String {
        val termCode = term?.takeIf { it.isNotBlank() } ?: ScheduleCache.readCurrentTerm(cache)
            ?: return CACHE_MISS_SCHEDULE
        val exams = ScheduleCache.readExams(cache, termCode)
            ?: return "本机还没有 $termCode 的考试安排（日程页里考试那一块刷出来之后这里就有）。"
        if (exams.isEmpty()) return ToolReply.empty("exams; term: $termCode")
        return buildString {
            append("$termCode 考试安排（${exams.size}场）：\n")
            exams.forEach { e ->
                append(listOfNotNull(
                    e.courseName,
                    e.examDate.takeIf { it.isNotBlank() },
                    e.examTime.takeIf { it.isNotBlank() },
                    e.location.takeIf { it.isNotBlank() },
                    e.seatNumber.takeIf { it.isNotBlank() }?.let { "座位 $it" },
                ).joinToString("｜"))
                append("\n")
            }
        }.trimEnd()
    }

    /**
     * 空教室（CDN 每日预生成，免登录）。
     *
     * 只有逐节空闲这一份：智慧教室那套「此刻几个人在」的实时状态桌面没接（与 `DesktopAgentHost.gapNote`
     * 同一条纪律），所以回答末尾直说这一层没有，而不是让模型把空闲说成"现在能自习"。
     */
    private fun emptyRoomsReply(campus: String?, building: String?, section: Int?, date: String?): String =
        try {
            val today = todayOnCampus()
            val targetDate = when (val key = date?.trim()?.lowercase().orEmpty()) {
                "", "今天", "今日", "today" -> today
                "明天", "明日", "tomorrow" -> LocalDate.fromEpochDays(today.toEpochDays() + 1)
                else -> runCatching { LocalDate.parse(key) }.getOrElse { today }
            }
            val campusName = normalizeCampus(campus)
            val buildings = CAMPUS_BUILDINGS[campusName]
                ?: return ToolReply.notFound("campus", campusName, CAMPUS_BUILDINGS.keys)
            val one = building?.takeIf { it.isNotBlank() }?.let { b ->
                buildings.firstOrNull { it == b || it.contains(b) || b.contains(it) }
                    ?: return ToolReply.notFound("building", b, buildings)
            }
            val rooms = emptyRoom.getEmptyRoomsMulti(campusName, one?.let { setOf(it) } ?: buildings.toSet(), targetDate.toString())
            val at = section?.takeIf { it in 1..11 }
            val free = if (at == null) {
                rooms.filter { r -> r.status.any { it == 0 } }
            } else {
                rooms.filter { r -> r.status.getOrElse(at - 1) { 1 } == 0 }
            }
            val where = buildString {
                append(campusName)
                one?.let { append(" $it") }
                at?.let { append(" 第${it}节") }
                append(" $targetDate")
            }
            if (free.isEmpty()) return ToolReply.empty("empty_rooms; $where")
            val shown = free.sortedBy { it.name }.take(15)
            buildString {
                append("空教室（$where，共${free.size}间）：\n")
                shown.forEach { r ->
                    append(r.name).append("｜${r.size} 座")
                    val slots = r.status.mapIndexedNotNull { i, s -> (i + 1).takeIf { s == 0 } }
                    if (slots.isNotEmpty()) append("｜空闲 ${slots.joinToString(",")} 节")
                    append("\n")
                }
                if (free.size > shown.size) append("…还有 ${free.size - shown.size} 间，说下楼栋能缩范围\n")
                append("（桌面这档只有 CDN 的逐节空闲表，没有智慧教室那种「此刻几个人在」的实时状态。）")
            }.trimEnd()
        } catch (e: Exception) {
            ToolReply.failed("get_empty_rooms", e.message)
        }

    /** 模型常写「兴庆」「创新港」，补成 [CAMPUS_BUILDINGS] 的键（与 serve 同一条判据）。 */
    private fun normalizeCampus(raw: String?): String {
        val c = raw?.trim()?.takeIf { it.isNotBlank() } ?: CAMPUS_BUILDINGS.keys.first()
        if (c in CAMPUS_BUILDINGS) return c
        return CAMPUS_BUILDINGS.keys.firstOrNull { it.startsWith(c.removeSuffix("校区")) } ?: c
    }

    /** 给测试也能引用的一句（见 `DesktopAgentJvmTest` 的“缓存空”那条）。 */
    companion object {
        /** 桌面没有教务取数通路时的那一句：说清"去哪儿刷一次"，别让它看起来像能自己拉。 */
        const val CACHE_MISS_SCHEDULE =
            "桌面这一端不直连教务：先在「日程」页登录教务刷一次课表，之后这里读的就是那一份缓存。"
    }
}
