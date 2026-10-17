package com.xjtu.toolbox.server

import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentTools
import com.xjtu.toolbox.agent.AgentWidget
import com.xjtu.toolbox.agent.ExamWidget
import com.xjtu.toolbox.agent.LiveRoomWidget
import com.xjtu.toolbox.agent.RoomWidget
import com.xjtu.toolbox.agent.ScheduleWidget
import com.xjtu.toolbox.agent.ToolReply
import com.xjtu.toolbox.agent.agentCalculate
import com.xjtu.toolbox.agent.agentCourseLine
import com.xjtu.toolbox.agent.agentGuideText
import com.xjtu.toolbox.agent.agentStaleFallback
import com.xjtu.toolbox.agent.agentToolDefinitions
import com.xjtu.toolbox.agent.agentWeekRange
import com.xjtu.toolbox.agent.argInt
import com.xjtu.toolbox.agent.argStr
import com.xjtu.toolbox.agent.withAgentChangeNote
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS
import com.xjtu.toolbox.emptyroom.EmptyRoomApi
import com.xjtu.toolbox.emptyroom.EmptyRoomDirectQuery
import com.xjtu.toolbox.emptyroom.LIVE_CAMPUSES
import com.xjtu.toolbox.emptyroom.LiveRoomApi
import com.xjtu.toolbox.calendar.SchoolCalendarApi
import com.xjtu.toolbox.calendar.SchoolCalendarSource
import com.xjtu.toolbox.faculty.FacultyApi
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.CustomCourseStore
import com.xjtu.toolbox.schedule.JwxtScheduleApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.schedule.XjtuTime
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.yellowpage.YellowPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
/** serve 侧的白名单工具层（`docs/agent-web-spec.md` §6） */
internal class ServeAgentTools(
    private val session: ServeSession,
    private val memory: AgentMemory,
    private val dataCache: ScheduleCacheStore,
    private val customCourses: CustomCourseStore,
    private val calendar: SchoolCalendarSource,
    private val yellowPage: YellowPageSource,
    private val faculty: FacultyApi,
) : AgentTools {

    /** 这一端真给模型的九件（顺序与 `:core` 那份目录里的顺序一致 —— 那一份是唯一一份顺序定义，这里只是筛子）。 */
    companion object {
        val NAMES: List<String> = listOf(
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
    }

    /** 最近一次 [ensureSite] 失败的原因。 */
    private var lastSiteError: Throwable? = null

    /** 这一轮的工具执行产出的卡片（课表 / 空教室 / 考试）；[ServeAgent] 在一轮结束时 drain 走。 */
    private val pendingWidgets = mutableListOf<AgentWidget>()

    override val toolDefinitions: String = serveAgentToolDefinitions()

    override fun drainWidgets(): List<AgentWidget> = pendingWidgets.toList().also { pendingWidgets.clear() }

    /** 记住的用户偏好，拼进系统提示。走工具层是因为 `ServeAgent` 手上只有这一格缝。 */
    override fun memoryBlock(): String = memory.promptBlock()

    /** 限流分组键：同一个后端系统的两次调用之间要隔开一点，别连着猛打学校服务器。 */
    override fun rateLimitKeyOf(toolName: String): String? = when (toolName) {
        "get_calendar", "get_schedule", "get_exam_schedule", "get_empty_rooms" -> "schedule"
        "search_yellow_page" -> "yellow_page"
        "find_faculty" -> "faculty"
        else -> null
    }

    override suspend fun execute(name: String, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching { AppJson.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

        val widgetsBefore = pendingWidgets.size
        val result = when (name) {
            // 当前时间、节次、教学周和校历本是同一件事，合成一个工具
            "get_calendar" -> getCurrentTime() + "\n\n" + getSchoolCalendar(args.argStr("term"))
            "get_schedule" -> getSchedule(args.argStr("date"), args.argStr("term"))
            "get_exam_schedule" -> getExamSchedule()
            "get_empty_rooms" -> getEmptyRooms(
                campus = args.argStr("campus"),
                building = args.argStr("building"),
                section = args.argInt("section"),
                date = args.argStr("date"),
            )
            "search_yellow_page" -> searchYellowPage(
                query = args.argStr("query"),
                category = args.argStr("category"),
                limit = args.argInt("limit") ?: 10,
            )
            // 给 value 是记下，不给是删掉
            "preference" -> {
                val key = args.argStr("key") ?: ""
                val value = args.argStr("value").orEmpty()
                if (value.isBlank()) memory.forget(key) else memory.remember(key, value)
            }
            "find_faculty" -> findFaculty(
                name = args.argStr("name") ?: "",
                college = args.argStr("college"),
                limit = args.argInt("limit") ?: 3,
            )
            "app_guide" -> agentGuideText(args.argStr("topic"))
            "calculate" -> agentCalculate(args.argStr("expression") ?: "")
            // 工具表里没画的档回 not_found：模型可能凭旧对话里的记忆调过来（那段历史是在别的端上生成的）
            else -> ToolReply.notFound("tool", name)
        }
        if (pendingWidgets.size > widgetsBefore) result + "\n" + ToolReply.CARD_SHOWN else result
    }

    // ── 取数（这一端自己的那几件：黄页 / 教师检索，与 DesktopAgentTools 同形）────────────

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
            faculty.searchAll(
                query = com.xjtu.toolbox.faculty.FacultySearchQuery(name = name),
                limit = 60,
            )
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

    private fun currentPeriodIndex(): Int {
        val now = java.time.LocalDateTime.now()
        val isSummer = XjtuTime.isSummerTime(now.monthValue)
        val nowMinute = now.hour * 60 + now.minute
        val section = (1..11).firstOrNull { s ->
            val ct = XjtuTime.getClassTime(s, isSummer) ?: return@firstOrNull false
            nowMinute in (ct.start.hour * 60 + ct.start.minute)..(ct.end.hour * 60 + ct.end.minute)
        }
        return (section ?: 0) - 1
    }
    private suspend fun ensureSite(type: LoginType): SiteSession? = try {
        lastSiteError = null
        session.sessionManager.ensureSite(type, silent = true)
    } catch (e: Throwable) {
        lastSiteError = e
        null
    }
    private suspend fun ensureSiteKey(siteKey: String): SiteSession? = try {
        lastSiteError = null
        session.sessionManager.ensureSite(siteKey, silent = true)
    } catch (e: Throwable) {
        lastSiteError = e
        null
    }
    private fun normalizeCampus(campus: String?): String {
        val c = campus?.trim().orEmpty()
        if (c.isEmpty()) return "兴庆校区"
        if (c in CAMPUS_BUILDINGS) return c
        return CAMPUS_BUILDINGS.keys.firstOrNull { it.startsWith(c.removeSuffix("校区")) } ?: c
    }
    private suspend fun liveRoomsReply(site: SiteSession, campus: String, building: String?): String? {
        val snap = LiveRoomApi(site, null).fetchCampus(campus)
        val key = building?.trim().orEmpty()
        val wanted = when {
            key.isEmpty() -> null
            key.all { it.isDigit() } -> "${key}号巨构"
            else -> key
        }
        val scope = if (wanted == null) snap.rooms else snap.rooms.filter {
            it.building == wanted || it.building.startsWith(wanted) ||
                (wanted.length > 2 && wanted.startsWith(it.building))
        }
        if (scope.isEmpty()) return null

        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(snap.fetchedAt)
        val free = scope.filter { it.isFree }
        val inUse = scope.filter { it.isInUse }.sortedBy { it.people }
        val inClass = scope.count { it.isInClass }
        val cond = campus + (wanted?.let { " $it" } ?: "")

        pendingWidgets.add(LiveRoomWidget("$cond · $time 实时", (free + inUse).take(40), snap.fetchedAt))
        return buildString {
            append("实时状态（$cond，$time）：空闲 ${free.size} 间｜其它使用 ${inUse.size} 间｜上课中 $inClass 间。\n")
            append("其它使用 = 课表上没课但平台统计到有人，可能是自习，也可能是社团借用、活动，平台不区分，别替用户断定能进；人数是此刻在场人数。上课中的教室不能进。\n")
            if (free.isEmpty() && inUse.isEmpty()) append("此刻没有空闲或其它使用的教室。\n")
            if (free.isNotEmpty()) {
                append("空闲：\n")
                free.take(15).forEach { append("${it.name}｜${it.seats} 座\n") }
                if (free.size > 15) append("…还有${free.size - 15}间空闲，可指定楼栋缩小范围。\n")
            }
            if (inUse.isNotEmpty()) {
                append("其它使用（人少在前）：\n")
                inUse.take(8).forEach { append("${it.name}｜${it.people} 人/${it.seats} 座\n") }
                if (inUse.size > 8) append("…还有${inUse.size - 8}间。\n")
            }
        }
    }
    private fun loginHint(type: LoginType): String = when (val e = lastSiteError) {
        is com.xjtu.toolbox.auth.MfaRequiredException ->
            ToolReply.loginFailed(type.label, "sms_verification_required_in_app")
        is com.xjtu.toolbox.auth.CasGate.ThrottledException ->
            ToolReply.loginFailed(type.label, "cas_throttled", e.message)
        is com.xjtu.toolbox.auth.PasswordInvalidatedException ->
            ToolReply.loginFailed(type.label, "cas_password_invalid; auto_login: off")
        is com.xjtu.toolbox.auth.LoginCooldownException ->
            ToolReply.loginFailed(type.label, "cooldown; retry_after_s: ${e.retryAfterSeconds}")
        is com.xjtu.toolbox.auth.AuthExpiredException ->
            ToolReply.loginFailed(type.label, "no_saved_credentials")
        null -> ToolReply.notLoggedIn(type.label)
        else -> ToolReply.loginFailed(type.label, "connection", e.message?.take(60))
    }
    private fun getCurrentTime(): String {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val isSummer = XjtuTime.isSummerTime(today.monthValue)
        val dayNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val nowMinute = now.hour * 60 + now.minute

        val currentSection = (1..11).firstOrNull { s ->
            val ct = XjtuTime.getClassTime(s, isSummer) ?: return@firstOrNull false
            nowMinute in (ct.start.hour * 60 + ct.start.minute)..(ct.end.hour * 60 + ct.end.minute)
        }
        val nextSection = (1..11).firstOrNull { s ->
            val ct = XjtuTime.getClassTime(s, isSummer) ?: return@firstOrNull false
            ct.start.hour * 60 + ct.start.minute > nowMinute
        }

        val termCode = cachedTermCode()
        val termLabel = termCode?.let { code ->
            com.xjtu.toolbox.schedule.ScheduleTermStore.officialName(
                code,
                live = emptyMap(),
                disk = com.xjtu.toolbox.schedule.ScheduleTermStore.read(dataCache),
            )
        }
        val weekInfo = termCode?.let {
            cachedStartDate(it)?.let { sd ->
                val startStr = sd.toString()
                val daysSince = java.time.temporal.ChronoUnit.DAYS.between(sd, today).toInt()
                val w = com.xjtu.toolbox.schedule.TermWeeks.weekOf(sd.toKx(), today.toKx())
                // 含起始日与已过天数，便于推算"整学期"区间（如校园卡整学期账单天数）
                if (w in 1..25) "第${w}周（起始 $startStr，开学至今 $daysSince 天）" else null
            }
        }

        return buildString {
            append("当前：${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}，${dayNames[today.dayOfWeek.value]}")
            if (termCode != null) {
                append("，学期 $termCode")
                termLabel?.takeIf { it != termCode }?.let { append("（$it）") }
            }
            weekInfo?.let { append("，$it") }
            when {
                currentSection != null -> append("，第${currentSection}节上课中（${XjtuTime.getClassStartStr(currentSection, isSummer)}）")
                nextSection != null -> append("，下一节：第${nextSection}节（${XjtuTime.getClassStartStr(nextSection, isSummer)}）")
                else -> append("，今日课程已结束")
            }
            // 今天适用的作息表：用户问「第 9 节几点」时不必再猜冬季还是夏季
            append("\n作息（${if (isSummer) "夏季，5–9 月" else "冬季，10–4 月"}）：")
            append((1..11).mapNotNull { s ->
                XjtuTime.getClassTime(s, isSummer)?.let { "$s ${it.start}–${it.end}" }
            }.joinToString("｜"))
        }
    }
    private fun cachedTermCode(): String? = ScheduleCache.readCurrentTerm(dataCache)
    private fun cachedStartDate(term: String): LocalDate? = ScheduleCache.readStartDate(dataCache, term)?.toJavaTime()
    private suspend fun getSchoolCalendar(term: String?): String {
        return try {
            val terms = calendar.terms()
            if (terms.isEmpty()) return ToolReply.empty("school_calendar")
            val today = com.xjtu.toolbox.util.todayInSystemZone()
            val selected = if (term.isNullOrBlank()) {
                terms.firstOrNull { today in it.startDate..it.endDate }
                    ?: terms.lastOrNull { it.startDate <= today }
                    ?: terms.last()
            } else {
                terms.firstOrNull {
                    it.id.contains(term, ignoreCase = true) ||
                        it.termName.contains(term, ignoreCase = true) ||
                        it.yearName.contains(term, ignoreCase = true)
                } ?: return ToolReply.notFound("term", term, terms.takeLast(6).map { it.termName })
            }
            val week = selected.currentWeek(today)
            buildString {
                append("${selected.termName}校历：\n")
                append("• 学期：${selected.startDate} 至 ${selected.endDate}，共${selected.totalWeeks}周")
                if (week > 0) append("；今天是第${week}周")
                append("\n")
                if (selected.events.isEmpty()) {
                    append("重要事件：无")
                } else {
                    append("重要事件：\n")
                    selected.events.forEach { event ->
                        append("• ${event.name}：${event.startDate}")
                        if (event.endDate != event.startDate) append(" 至 ${event.endDate}")
                        if (event.days > 0) append("（${event.days}天）")
                        if (event.remark.isNotBlank()) append("；${event.remark}")
                        append("\n")
                    }
                }
            }.trimEnd()
        } catch (e: Exception) {
            ToolReply.failed("get_calendar", e.message)
        }
    }
    private suspend fun ensureScheduleLoaded(targetTerm: String? = null): String? {
        val term = targetTerm?.takeIf { it.isNotBlank() }
        if (ScheduleCache.isReady(dataCache, term)) return null

        val site = ensureSite(LoginType.JWXT)
            ?: return ToolReply.noCache(ToolReply.loginFailed("教务系统", "unreachable"))
        return try {
            com.xjtu.toolbox.schedule.ScheduleSourceRouter.ensureCached(
                dataCache, JwxtScheduleApi(site), session.sessionManager, term,
            )
            null
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed("get_schedule", e.message)
        }
    }
    private suspend fun getSchedule(dateStr: String?, term: String? = null): String {
        val requested = term?.takeIf { it.isNotBlank() }
        ensureScheduleLoaded(requested)?.let { return it }

        // 单次读取学期代码，避免重复打开缓存文件
        val currentTerm = cachedTermCode() ?: return ToolReply.failed("get_schedule", "no_term_code")
        val termCode = requested ?: currentTerm
        val isHistorical = termCode != currentTerm

        val cachedCourses = ScheduleCache.readCourses(dataCache, termCode)
            ?: return ToolReply.failed("get_schedule", "bad_cache")
        // 合并该学期用户手动添加的日程（历史学期同样按该学期读取）
        val customCourses = runCatching {
            customCourses.getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: "", termCode)
                .map { it.toCourseItem() }
        }.getOrDefault(emptyList())
        val courses = cachedCourses + customCourses
        val changeNote = scheduleChangeNote(termCode, courses)

        val dayNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

        // 指定历史学期且未指定日期：给整学期总览（"本周"对历史学期无意义）
        if (isHistorical && dateStr == null) {
            if (courses.isEmpty()) return "${termCode}学期没有课程记录。"
            pendingWidgets.add(ScheduleWidget("${termCode}学期课表", courses))
            return buildString {
                // 一门课一周上几次、临时换过教室，都会是好几条；"共几门"按课程数，不按条数
                val courseCount = courses.map { it.courseCode.ifBlank { it.courseName } }.distinct().size
                append("${termCode}学期课表（共${courseCount}门，含手动添加${customCourses.size}）：\n")
                // 历史学期跨冬夏两套作息，只给节次不给钟点
                courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection })).forEach {
                    append("${it.courseName}｜${dayNames.getOrElse(it.dayOfWeek) { "" }} ${it.startSection}–${it.endSection}节｜${agentWeekRange(it).trim()}｜${it.location}\n")
                }
            }.withAgentChangeNote(changeNote)
        }

        val startDate = runCatching {
            cachedStartDate(termCode)
        }.getOrNull() ?: return ToolReply.failed("get_schedule", "no_term_start_date")

        val holidays = runCatching { com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(dataCache) }
            .getOrDefault(emptyMap())
            .mapKeys { it.key.toJavaTime() }

        if (dateStr != null) {
            val targetDate = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now() }
            val weekNum = com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate.toKx(), targetDate.toKx())
            val targetSummer = XjtuTime.isSummerTime(targetDate.monthValue)
            val dayCourses = courses.filter { it.dayOfWeek == targetDate.dayOfWeek.value && it.isInWeek(weekNum) }
                .sortedBy { it.clockMinutes(targetSummer).first }
            val holiday = holidays[targetDate]
            if (dayCourses.isEmpty()) {
                return "${targetDate} 第${weekNum}周${dayNames[targetDate.dayOfWeek.value]}｜无课" +
                    (holiday?.let { "｜法定假日 $it" } ?: "")
            }
            pendingWidgets.add(ScheduleWidget("${targetDate} 第${weekNum}周${dayNames[targetDate.dayOfWeek.value]}", dayCourses))
            return buildString {
                append("${targetDate} 第${weekNum}周${dayNames[targetDate.dayOfWeek.value]}")
                holiday?.let { h ->
                    append("｜法定假日 $h")
                    if (dayCourses.any { !it.isUserCreated }) append("，教务课程停课，自建日程照常")
                }
                append("\n")
                dayCourses.forEach { c -> append(agentCourseLine(c, targetSummer, withTeacher = true)).append('\n') }
            }.withAgentChangeNote(changeNote)
        } else {
            val today = LocalDate.now()
            val weekNum = com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate.toKx(), today.toKx())
            if (weekNum <= 0) return "当前不在学期内。"
            // 每门课按它那天的日期算作息，跨月那周也不会错
            val monday = startDate.plusWeeks((weekNum - 1).toLong())
            val weekCourses = courses.filter { it.isInWeek(weekNum) }
                .sortedWith(compareBy({ it.dayOfWeek }, {
                    it.clockMinutes(XjtuTime.isSummerTime(monday.plusDays((it.dayOfWeek - 1).toLong()).monthValue)).first
                }))
            if (weekCourses.isEmpty()) return "第${weekNum}周没有课。"
            pendingWidgets.add(ScheduleWidget("第${weekNum}周课表", weekCourses))
            return buildString {
                append("第${weekNum}周：\n")
                weekCourses.forEach { c ->
                    val day = monday.plusDays((c.dayOfWeek - 1).toLong())
                    append(agentCourseLine(c, XjtuTime.isSummerTime(day.monthValue)))
                    if (!c.isUserCreated) holidays[day]?.let { append("｜停课（$it）") }
                    append('\n')
                }
            }.withAgentChangeNote(changeNote)
        }
    }
    private fun scheduleChangeNote(termCode: String, courses: List<CourseItem>): String? {
        val codes = courses.map { it.courseCode }.toSet()
        val relevant = com.xjtu.toolbox.schedule.ScheduleSourceRouter.changeEvents(termCode)
            .filter { it.courseCode.isBlank() || it.courseCode in codes }
            .takeLast(3)
        if (relevant.isEmpty()) return null
        return "调课记录：" + relevant.joinToString("；") { "${it.courseName}${it.describe()}" }
    }
    private suspend fun getExamSchedule(): String {
        val site = ensureSite(LoginType.JWXT)
            ?: return loginHint(LoginType.JWXT)
        return try {
            val exams = JwxtScheduleApi(site).examSchedule()
            if (exams.isEmpty()) return ToolReply.empty("exams")
            pendingWidgets.add(ExamWidget(exams))
            val text = buildString {
                append("考试安排（${exams.size}场）：\n")
                exams.forEach { e ->
                    append("${e.courseName}｜${e.examDate} ${e.examTime}｜${e.location}｜座位 ${e.seatNumber.ifBlank { "待定" }}\n")
                }
            }
            dataCache.writeJson("agent_exam", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            agentStaleFallback(dataCache, "agent_exam", ToolReply.failed("get_exam_schedule", e.message))
        }
    }
    private suspend fun getEmptyRooms(campus: String?, building: String?, section: Int?, date: String?): String {
        return try {
            val api = EmptyRoomApi(null)
            val targetDate = parseToolDate(date)
            val dateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            val targetCampus = normalizeCampus(campus)
            val buildings = CAMPUS_BUILDINGS[targetCampus]
                ?: return ToolReply.notFound("campus", targetCampus, CAMPUS_BUILDINGS.keys)

            // serve 没有 App 那一屏的数据源偏好（`empty_room` prefs 在 :app）⇒ 一律走默认那一档
            val sourcePref: String? = null
            val nowSection = currentPeriodIndex() + 1
            val asksNow = targetDate == LocalDate.now() && (section == null || section == nowSection)
            var liveNote: String? = null
            if (asksNow && (sourcePref == null || sourcePref == "live") && targetCampus in LIVE_CAMPUSES) {
                val site = ensureSiteKey(com.xjtu.toolbox.auth.JsSession.SITE_KEY)
                if (site != null) {
                    val live = runCatching { liveRoomsReply(site, targetCampus, building) }
                    live.getOrNull()?.let { return it }
                    liveNote = if (live.isSuccess) {
                        // 楼不在智慧教室平台上（仲英楼、中1 等），课表里有
                        "实时状态里没有「$building」，以下按课表"
                    } else {
                        "实时状态查询失败（${live.exceptionOrNull()?.message?.take(60) ?: "未知原因"}），以下按课表"
                    }
                } else {
                    liveNote = "实时状态登录不上（${lastSiteError?.javaClass?.simpleName ?: "未登录"}），以下按课表"
                }
            }

            val targetBuildings = if (building != null) {
                buildings.filter { it == building || it.startsWith(building) }
                    .ifEmpty { return ToolReply.notFound("building", "$targetCampus $building") }
            } else buildings

            // 复用页面的「CDN / 直连教务」选择。直连较重（每楼逐节查询，约11次请求/楼），
            // 仅在用户已开启直连且指定了具体楼栋时启用，避免对全校区直连冲击学校服务器。
            val preferDirect = sourcePref == "direct"
            val directClient = if (preferDirect && building != null) {
                ensureSite(LoginType.JWXT)?.client
            } else null
            val usingDirect = directClient != null

            // 并发请求各楼栋，避免整校区串行导致超时
            val allRooms = coroutineScope {
                targetBuildings.map { b ->
                    async(Dispatchers.IO) {
                        runCatching {
                            if (directClient != null)
                                EmptyRoomDirectQuery(directClient, null).queryDay(targetCampus, b, dateStr)
                            else api.getEmptyRooms(targetCampus, b, dateStr)
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            if (allRooms.isEmpty()) return ToolReply.empty(
                "empty_rooms_data; date: $dateStr; campus: $targetCampus" + (liveNote?.let { "; note: $it" } ?: "")
            )

            val filtered = if (section != null && section in 1..11) {
                allRooms.filter { it.status.getOrElse(section - 1) { 1 } == 0 }
            } else {
                // 无指定节次，取全天任意节次空闲的教室
                allRooms.filter { r -> r.status.any { it == 0 } }
            }
            if (filtered.isEmpty()) return ToolReply.empty(
                "empty_rooms; date: $dateStr; campus: $targetCampus" +
                    (building?.let { "; building: $it" } ?: "") +
                    (section?.let { "; section: $it" } ?: "")
            )

            val cond = buildString {
                append(targetCampus)
                building?.let { append(" $it") }
                section?.let { append(" 第${it}节") }
                append(" $dateStr")
                if (usingDirect) append(" · 直连")
            }
            pendingWidgets.add(RoomWidget(cond, filtered, section?.minus(1) ?: currentPeriodIndex()))
            val shown = filtered.take(15)
            buildString {
                liveNote?.let { append(it).append("。\n") }
                append("空教室（$cond，共${filtered.size}间）：\n")
                shown.forEach { r ->
                    val freeSlots = r.status.mapIndexedNotNull { i, s -> if (s == 0) i + 1 else null }
                    append("${r.name}｜${r.size} 座｜空闲节次 ${freeSlots.joinToString(",")}\n")
                }
                if (filtered.size > shown.size) append("…还有${filtered.size - shown.size}间，可指定楼栋缩小范围。\n")
            }
        } catch (e: Exception) {
            ToolReply.failed("get_empty_rooms", e.message)
        }
    }
    private fun parseToolDate(date: String?): LocalDate {
        val today = LocalDate.now()
        return when (date?.trim()?.lowercase()) {
            null, "", "今天", "今日", "today" -> today
            "明天", "tomorrow" -> today.plusDays(1)
            else -> runCatching { LocalDate.parse(date.trim()) }.getOrElse { today }
        }
    }

    // ── 两处时间类型桥 ─────────────────────────────────────────────────────
    private fun java.time.LocalDate.toKx(): kotlinx.datetime.LocalDate =
        kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)

    private fun kotlinx.datetime.LocalDate.toJavaTime(): java.time.LocalDate =
        java.time.LocalDate.of(year, month.ordinal + 1, day)
}

/** 这一端送给模型的工具表：`:core` 那份描述表按 [ServeAgentTools.NAMES] 裁一刀。 */
internal fun serveAgentToolDefinitions(): String {
    val drawn = (AppJson.parseToJsonElement(agentToolDefinitions(emptySet())) as JsonArray)
        .filter { element ->
            element.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content in ServeAgentTools.NAMES
        }
    return JsonArray(drawn).toString()
}
