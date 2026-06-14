package com.xjtu.toolbox.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjtu.toolbox.AppLoginState
import com.xjtu.toolbox.attendance.AttendanceApi
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.card.CampusCardApi
import com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS
import com.xjtu.toolbox.emptyroom.EmptyRoomApi
import com.xjtu.toolbox.schedule.ScheduleApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.score.ScoreReportApi
import com.xjtu.toolbox.util.DataCache
import com.xjtu.toolbox.util.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 暴露给 Agent 的只读工具注册表。
 *
 * 安全约束：
 * - 全部只读，无写/预订/支付接口
 * - 优先复用 AppLoginState 已有登录实例；若对应子系统未登录，调用一次 autoLogin，
 *   60s 内不重试（防止反复触发导致服务端风控）
 * - Auth 异常直接上抛给 AgentRunner 终止循环（不重试）
 * - 优先命中 DataCache，减少对学校服务器的请求
 */
class AgentToolRegistry(
    private val loginState: AppLoginState,
    private val dataCache: DataCache,
    private val context: Context
) {
    private val gson = Gson()

    // 每个 LoginType 上次失败的时间戳；60s 内不重试，防止反复触发服务端风控
    private val loginFailedAt = mutableMapOf<LoginType, Long>()

    // 本轮工具执行产出的富控件（课表卡/成绩卡/教室卡…）；ViewModel 在 run 结束后 drain。
    private val pendingWidgets = mutableListOf<AgentWidget>()

    /** 取走并清空本轮收集的控件。 */
    fun drainWidgets(): List<AgentWidget> = pendingWidgets.toList().also { pendingWidgets.clear() }

    /** 当前节次的 0 基索引；不在上课时段返回 -1。 */
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

    /**
     * 尝试一次 autoLogin。若60s内已失败过则直接返回 false，不发出任何请求。
     * autoLogin 内部有 mutex 防并发，此处只加时间冷却。
     */
    private suspend fun tryAutoLogin(type: LoginType): Boolean {
        val now = System.currentTimeMillis()
        if (now - (loginFailedAt[type] ?: 0L) < 60_000L) return false
        return if (loginState.autoLogin(type) != null) {
            true
        } else {
            loginFailedAt[type] = now
            false
        }
    }

    // OpenAI function calling 格式的工具描述。
    // 用 Gson 构建（自动转义），避免手写 JSON 在 description 里出现引号导致整串被截断。
    // 新增/修改工具：只需在 buildToolDefinitions() 里加一行 tool(...)。
    val toolDefinitions: String = buildToolDefinitions()

    private fun buildToolDefinitions(): String {
        val arr = JsonArray()
        arr.add(tool("get_current_time",
            "获取当前日期、时间、星期、学期周数、当前/下一节次。无需登录。"))
        arr.add(tool("get_schedule",
            "查询课表。date填yyyy-MM-dd查当天，不填返回本周全部课程。需要本地缓存（用户曾打开过课表页面）。",
            params("date" to strProp("查询日期，格式yyyy-MM-dd，不填则返回本周。"))))
        arr.add(tool("get_exam_schedule",
            "查询考试安排，含日期、时间、地点、座位号。需要教务系统登录。"))
        arr.add(tool("get_empty_rooms",
            "查询空闲教室。从CDN获取数据，无需登录。campus可选：兴庆校区/雁塔校区/曲江校区/创新港校区；building为楼名如「主楼A」；section为节次1-11；date为yyyy-MM-dd。",
            params(
                "campus"   to strProp("校区名称，不填则默认兴庆校区。"),
                "building" to strProp("教学楼名称，不填则查该校区所有楼。"),
                "section"  to intProp("节次1-11，不填则查全天空教室。"),
                "date"     to strProp("日期yyyy-MM-dd，不填则查今天。")
            )))
        arr.add(tool("get_attendance",
            "查询最近考勤记录（正常/迟到/缺勤）。需要考勤系统登录。",
            params("limit" to intProp("返回条数，默认10，最多30。"))))
        arr.add(tool("get_grades",
            "查询本人课程成绩与加权平均学分绩点（GPA）。需要教务系统登录。term可选，传形如「2024-2025-1」只看该学期，不传返回全部成绩。",
            params("term" to strProp("学期代码，如2024-2025-1，不填返回全部。"))))
        arr.add(tool("get_card_balance",
            "查询校园卡（一卡通）电子钱包余额与状态（挂失/冻结）。需要校园卡系统登录。"))
        return arr.toString()
    }

    /** 构造单个 function-calling 工具对象。params 省略时为无参。 */
    private fun tool(name: String, description: String, params: JsonObject = emptyParams()): JsonObject =
        JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", params)
            })
        }

    private fun emptyParams(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
        add("required", JsonArray())
    }

    private fun params(vararg props: Pair<String, JsonObject>): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
        add("required", JsonArray())
    }

    private fun strProp(description: String): JsonObject = propOf("string", description)
    private fun intProp(description: String): JsonObject = propOf("integer", description)
    private fun propOf(type: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }

    suspend fun execute(name: String, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(argsJson, Map::class.java) as Map<String, Any>
        }.getOrDefault(emptyMap())

        when (name) {
            "get_current_time" -> getCurrentTime()
            "get_schedule" -> getSchedule(args["date"] as? String)
            "get_exam_schedule" -> getExamSchedule()
            "get_empty_rooms" -> getEmptyRooms(
                campus = args["campus"] as? String,
                building = args["building"] as? String,
                section = (args["section"] as? Double)?.toInt(),
                date = args["date"] as? String
            )  // suspend: parallel per-building fetches inside
            "get_attendance" -> getAttendance(
                limit = (args["limit"] as? Double)?.toInt() ?: 10
            )
            "get_grades" -> getGrades(args["term"] as? String)
            "get_card_balance" -> getCardBalance()
            else -> "未知工具：$name"
        }
    }

    // ── 实现 ──────────────────────────────────────────────────────────────

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

        val termCode = runCatching {
            gson.fromJson(dataCache.get("schedule_term_list", Long.MAX_VALUE), Array<String>::class.java)?.firstOrNull()
        }.getOrNull()
        val weekInfo = termCode?.let {
            val startStr = runCatching { gson.fromJson(dataCache.get("start_date_$it", Long.MAX_VALUE), String::class.java) }.getOrNull()
            val startDate = startStr?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() }
            startDate?.let { sd ->
                val w = ((java.time.temporal.ChronoUnit.DAYS.between(sd, today) / 7) + 1).toInt()
                if (w in 1..20) "第${w}周" else null
            }
        }

        return buildString {
            append("当前：${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}，${dayNames[today.dayOfWeek.value]}")
            weekInfo?.let { append("，学期$it") }
            when {
                currentSection != null -> append("，第${currentSection}节上课中（${XjtuTime.getClassStartStr(currentSection, isSummer)}）")
                nextSection != null -> append("，下一节：第${nextSection}节（${XjtuTime.getClassStartStr(nextSection, isSummer)}）")
                else -> append("，今日课程已结束")
            }
        }
    }

    private fun getSchedule(dateStr: String?): String {
        val termCode = runCatching {
            gson.fromJson(dataCache.get("schedule_term_list", Long.MAX_VALUE), Array<String>::class.java)?.firstOrNull()
        }.getOrNull() ?: return "课表未加载，请先打开课表页面同步数据。"

        val courses = ScheduleCache.readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
            ?: ScheduleCache.readRawCourses(dataCache, gson, termCode, Long.MAX_VALUE)
            ?: return "课表未加载，请先打开课表页面同步数据。"

        val startDate = runCatching {
            gson.fromJson(dataCache.get("start_date_$termCode", Long.MAX_VALUE), String::class.java)
                ?.let { LocalDate.parse(it) }
        }.getOrNull() ?: return "学期起始日期未知，请先打开课表页面。"

        val dayNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

        if (dateStr != null) {
            val targetDate = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now() }
            val weekNum = ((java.time.temporal.ChronoUnit.DAYS.between(startDate, targetDate) / 7) + 1).toInt()
            val dayCourses = courses.filter { it.dayOfWeek == targetDate.dayOfWeek.value && it.isInWeek(weekNum) }
                .sortedBy { it.startSection }
            if (dayCourses.isEmpty()) return "${targetDate}（第${weekNum}周 ${dayNames[targetDate.dayOfWeek.value]}）没有课。"
            pendingWidgets.add(ScheduleWidget("${targetDate} 第${weekNum}周${dayNames[targetDate.dayOfWeek.value]}", dayCourses))
            return buildString {
                append("${targetDate} 第${weekNum}周${dayNames[targetDate.dayOfWeek.value]}课程：\n")
                dayCourses.forEach { c ->
                    append("• ${c.courseName}，第${c.startSection}-${c.endSection}节（${XjtuTime.getClassStartStr(c.startSection)}起），${c.location}，${c.teacher}\n")
                }
            }
        } else {
            val today = LocalDate.now()
            val weekNum = ((java.time.temporal.ChronoUnit.DAYS.between(startDate, today) / 7) + 1).toInt()
            if (weekNum <= 0) return "当前不在学期内。"
            val weekCourses = courses.filter { it.isInWeek(weekNum) }
                .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))
            if (weekCourses.isEmpty()) return "第${weekNum}周没有课。"
            pendingWidgets.add(ScheduleWidget("第${weekNum}周课表", weekCourses))
            return buildString {
                append("第${weekNum}周课程：\n")
                weekCourses.groupBy { it.dayOfWeek }.forEach { (day, cs) ->
                    append("${dayNames[day]}：${cs.joinToString("；") { "${it.courseName}（${it.startSection}-${it.endSection}节，${it.location}）" }}\n")
                }
            }
        }
    }

    private suspend fun getExamSchedule(): String {
        if (loginState.jwxtLogin == null && !tryAutoLogin(LoginType.JWXT))
            return "需要教务系统登录，请先打开任意教务功能完成认证。"
        val login = loginState.jwxtLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val exams = ScheduleApi(login).getExamSchedule()
            if (exams.isEmpty()) return "暂无考试安排。"
            pendingWidgets.add(ExamWidget(exams))
            buildString {
                append("考试安排（${exams.size}场）：\n")
                exams.forEach { e ->
                    append("• ${e.courseName}，${e.examDate} ${e.examTime}，${e.location}，座位：${e.seatNumber.ifBlank { "待定" }}\n")
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取考试安排失败：${e.message}"
        }
    }

    private suspend fun getEmptyRooms(campus: String?, building: String?, section: Int?, date: String?): String {
        return try {
            val api = EmptyRoomApi(context)
            val targetDate = date?.let { runCatching { LocalDate.parse(it) }.getOrElse { LocalDate.now() } }
                ?: LocalDate.now()
            val dateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            val targetCampus = campus ?: "兴庆校区"
            val buildings = CAMPUS_BUILDINGS[targetCampus] ?: return "未知校区：$targetCampus"

            val targetBuildings = if (building != null) {
                buildings.filter { it == building || it.startsWith(building) }
                    .ifEmpty { return "在${targetCampus}未找到楼：$building" }
            } else buildings

            // 并发请求各楼栋，避免整校区串行导致超时
            val allRooms = coroutineScope {
                targetBuildings.map { b ->
                    async(Dispatchers.IO) {
                        runCatching { api.getEmptyRooms(targetCampus, b, dateStr) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            if (allRooms.isEmpty()) return "${dateStr} ${targetCampus}暂无数据。"

            val filtered = if (section != null && section in 1..11) {
                allRooms.filter { it.status.getOrElse(section - 1) { 1 } == 0 }
            } else {
                // 无指定节次，取全天任意节次空闲的教室
                allRooms.filter { r -> r.status.any { it == 0 } }
            }
            if (filtered.isEmpty()) return "${dateStr} ${targetCampus}${building ?: ""}${section?.let { " 第${it}节" } ?: ""}没有空教室。"

            val cond = buildString {
                append(targetCampus)
                building?.let { append(" $it") }
                section?.let { append(" 第${it}节") }
                append(" $dateStr")
            }
            pendingWidgets.add(RoomWidget(cond, filtered, section?.minus(1) ?: currentPeriodIndex()))
            val shown = filtered.take(15)
            buildString {
                append("空教室（$cond，共${filtered.size}间）：\n")
                shown.forEach { r ->
                    val freeSlots = r.status.mapIndexedNotNull { i, s -> if (s == 0) i + 1 else null }
                    append("• ${r.name}（${r.size}座），空闲节次：${freeSlots.joinToString("、") { "${it}节" }}\n")
                }
                if (filtered.size > shown.size) append("…还有${filtered.size - shown.size}间，可指定楼栋缩小范围。\n")
            }
        } catch (e: Exception) {
            "获取空教室失败：${e.message}"
        }
    }

    private suspend fun getAttendance(limit: Int): String {
        if (loginState.attendanceLogin == null && loginState.postgraduateAttendanceLogin == null) {
            // 本科和研究生考勤系统不同，两个都试一次（各自有60s冷却保护）
            if (!tryAutoLogin(LoginType.ATTENDANCE) && !tryAutoLogin(LoginType.POSTGRADUATE_ATTENDANCE)) {
                return "需要考勤系统登录，请先打开考勤功能完成认证。"
            }
        }
        val login = loginState.attendanceLogin ?: loginState.postgraduateAttendanceLogin
            ?: return "登录未就绪，请稍后再试。"
        return try {
            val records = AttendanceApi(login).getWaterRecords().take(limit.coerceIn(1, 30))
            if (records.isEmpty()) return "暂无考勤记录。"
            pendingWidgets.add(AttendanceWidget(records))
            buildString {
                append("最近${records.size}条考勤记录：\n")
                records.forEach { r ->
                    append("• ${r.courseName}（${r.date} 第${r.startTime}-${r.endTime}节）：${r.status.displayName}")
                    if (r.location.isNotBlank()) append("，${r.location}")
                    append("\n")
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取考勤记录失败：${e.message}"
        }
    }

    private suspend fun getGrades(term: String?): String {
        if (loginState.jwxtLogin == null && !tryAutoLogin(LoginType.JWXT))
            return "需要教务系统登录，请先打开任意教务功能完成认证。"
        val login = loginState.jwxtLogin ?: return "登录未就绪，请稍后再试。"
        val studentId = loginState.activeUsername
        if (studentId.isBlank()) return "未获取到学号，请重新登录后再试。"
        return try {
            val all = ScoreReportApi(login).getReportedGrade(studentId)
            val grades = if (term != null) all.filter { it.term == term } else all
            if (grades.isEmpty()) return if (term != null) "未找到${term}学期的成绩。" else "暂无成绩记录。"

            // 加权平均绩点：按学分加权，仅统计有绩点的课程
            val graded = grades.filter { it.gpa != null }
            val totalPoints = graded.sumOf { it.coursePoint }
            val gpa = if (totalPoints > 0) graded.sumOf { it.gpa!! * it.coursePoint } / totalPoints else null

            pendingWidgets.add(GradeWidget(grades, gpa, totalPoints))
            buildString {
                append("成绩（${grades.size}门")
                gpa?.let { append("，加权GPA %.2f".format(it)) }
                append("）：\n")
                grades.take(30).forEach { g ->
                    append("• ${g.courseName}：${g.score}，${g.coursePoint}学分")
                    g.gpa?.let { append("，绩点%.2f".format(it)) }
                    append("\n")
                }
                if (grades.size > 30) append("…还有${grades.size - 30}门\n")
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取成绩失败：${e.message}"
        }
    }

    private suspend fun getCardBalance(): String {
        if (loginState.campusCardLogin == null && !tryAutoLogin(LoginType.CAMPUS_CARD))
            return "需要校园卡系统登录，请先打开校园卡功能完成认证。"
        val login = loginState.campusCardLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val info = CampusCardApi(login).getCardInfo()
            pendingWidgets.add(CardWidget(info))
            buildString {
                append("校园卡余额：¥%.2f".format(info.balance))
                if (info.pendingAmount > 0) append("，待入账¥%.2f".format(info.pendingAmount))
                if (info.lostFlag) append("（已挂失）")
                if (info.frozenFlag) append("（已冻结）")
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取校园卡余额失败：${e.message}"
        }
    }
}
