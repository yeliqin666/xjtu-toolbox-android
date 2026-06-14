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
import com.xjtu.toolbox.emptyroom.EmptyRoomCache
import com.xjtu.toolbox.emptyroom.EmptyRoomDirectQuery
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

    /**
     * 从缓存读取用户身份（姓名 / 学号 / 院系），注入 system prompt，让 Agent 知道「我」是谁。
     * 姓名与院系取自校园卡快照缓存（用户打开过校园卡即有）；学号兜底用当前登录账号。
     */
    fun userIdentity(): String {
        val card = runCatching { com.xjtu.toolbox.card.CampusCardCache.load(context)?.cardInfo }.getOrNull()
        val parts = mutableListOf<String>()
        card?.name?.takeIf { it.isNotBlank() }?.let { parts.add("姓名$it") }
        (card?.studentNo?.takeIf { it.isNotBlank() } ?: loginState.activeUsername.takeIf { it.isNotBlank() })
            ?.let { parts.add("学号$it") }
        card?.department?.takeIf { it.isNotBlank() }?.let { parts.add("院系$it") }
        return parts.joinToString("，")
    }

    /** 把毫秒年龄转成人话，供回退缓存时如实标注新鲜度。 */
    private fun humanAge(ms: Long): String = when {
        ms < 60_000L      -> "刚刚"
        ms < 3_600_000L   -> "约${ms / 60_000L}分钟前"
        ms < 86_400_000L  -> "约${ms / 3_600_000L}小时前"
        else              -> "约${ms / 86_400_000L}天前"
    }

    /**
     * 实时获取失败时的兜底：若有缓存则返回带「约 X 前」时间戳的缓存内容，否则返回实时错误文案。
     * 让 Agent 能如实告诉用户「这是几点的缓存」，而不是干脆报错。
     */
    private fun staleOr(cacheKey: String, liveError: String): String {
        val cached = dataCache.getStale(cacheKey) ?: return liveError
        val age = dataCache.ageMs(cacheKey)?.let { humanAge(it) } ?: "较早"
        return "⚠️ 实时获取失败，以下为$age 的缓存数据：\n$cached"
    }

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
        arr.add(tool("get_card_transactions",
            "查询校园卡最近若干天的消费流水（商户、金额、余额）。需要校园卡系统登录。",
            params("days" to intProp("最近几天，默认7，最多30。"))))
        arr.add(tool("get_notifications",
            "查询校内最新通知公告（教务处/研究生院/各学院等官网通知），含标题、来源、日期、链接。无需登录。",
            params("limit" to intProp("返回条数，默认10，最多20。"))))
        arr.add(tool("web_search",
            "联网搜索互联网。用于校历、政策、报名通知、通用知识等本地工具无法回答的问题。返回标题、链接、摘要。",
            params(
                "query" to strProp("搜索关键词。"),
                "limit" to intProp("返回条数，默认5，最多8。")
            )))
        arr.add(tool("web_fetch",
            "抓取并阅读一个网页的正文文本，常配合 web_search 或 get_notifications 返回的链接使用，以了解详情。",
            params("url" to strProp("网页 URL，必须以 http/https 开头。"))))
        arr.add(tool("get_library_booking",
            "查询我当前的图书馆座位预约（座位号、区域、状态）。需要图书馆系统登录。"))
        arr.add(tool("get_library_seats",
            "查询图书馆某区域的空闲座位数。需要图书馆系统登录。area 传区域名（模糊匹配，如「北楼二层外文库」），不填则列出所有可选区域名称。",
            params("area" to strProp("区域名称，不填返回区域列表。"))))
        arr.add(tool("get_textbooks",
            "搜索教材中心的教材（按书名或课程名关键词）。需要教材中心登录。",
            params("keyword" to strProp("搜索关键词，如课程名或书名。"))))
        arr.add(tool("get_coupons",
            "查询我的加餐券（电子券）：名称、余额、有效期。需要加餐券系统登录。"))
        arr.add(tool("get_lms_courses",
            "列出我在思源学堂（LMS）的课程。需要思源学堂登录。"))
        arr.add(tool("get_lms_activities",
            "查询思源学堂某门课的作业、课件、回放等活动。course 传课程名（模糊匹配）。需要思源学堂登录。",
            params("course" to strProp("课程名称，模糊匹配；不填会提示先查课程列表。"))))
        arr.add(tool("get_lms_assignments",
            "汇总思源学堂所有课程的作业（最新作业一览）。需要思源学堂登录，会逐课查询，稍慢。"))
        arr.add(tool("get_app_settings",
            "读取本应用可调设置（主题 / 启动页 / 网络模式 / 自动更新 / 更新通道）当前值与可选项。无需登录。"))
        arr.add(tool("set_app_setting",
            "修改本应用一项非敏感设置（账号密码等敏感项不可改）。无需登录。",
            params(
                "key" to strProp("设置键：dark_mode / default_tab / network_mode / auto_check_update / update_channel。"),
                "value" to strProp("取值，可先用 get_app_settings 查看每项的可选值。")
            )))
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
            "get_card_transactions" -> getCardTransactions((args["days"] as? Double)?.toInt() ?: 7)
            "get_notifications" -> getNotifications((args["limit"] as? Double)?.toInt() ?: 10)
            "web_search" -> webSearch(args["query"] as? String ?: "", (args["limit"] as? Double)?.toInt() ?: 5)
            "web_fetch" -> webFetch(args["url"] as? String ?: "")
            "get_library_booking" -> getLibraryBooking()
            "get_library_seats" -> getLibrarySeats(args["area"] as? String)
            "get_textbooks" -> getTextbooks(args["keyword"] as? String ?: "")
            "get_coupons" -> getCoupons()
            "get_lms_courses" -> getLmsCourses()
            "get_lms_activities" -> getLmsActivities(args["course"] as? String)
            "get_lms_assignments" -> getLmsAssignments()
            "get_app_settings" -> getAppSettings()
            "set_app_setting" -> setAppSetting(args["key"] as? String ?: "", args["value"] as? String ?: "")
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

    private fun cachedTermCode(): String? = runCatching {
        gson.fromJson(dataCache.get("schedule_term_list", Long.MAX_VALUE), Array<String>::class.java)?.firstOrNull()
    }.getOrNull()

    private fun cachedStartDate(term: String): String? = runCatching {
        gson.fromJson(dataCache.get("start_date_$term", Long.MAX_VALUE), String::class.java)
    }.getOrNull()

    /**
     * 确保课表（学期 / 课程 / 起始日）就绪：缓存缺失时**直接在线拉取并写缓存**，
     * 而不是让用户「先去打开课表页同步」。需教务系统登录。返回 null 表示就绪，否则为错误提示。
     */
    private suspend fun ensureScheduleLoaded(): String? {
        val term0 = cachedTermCode()
        val coursesCached = term0 != null && (
            ScheduleCache.readOptimizedCourses(dataCache, gson, term0, Long.MAX_VALUE)
                ?: ScheduleCache.readRawCourses(dataCache, gson, term0, Long.MAX_VALUE)) != null
        if (coursesCached && term0 != null && cachedStartDate(term0) != null) return null

        if (loginState.jwxtLogin == null && !tryAutoLogin(LoginType.JWXT))
            return "课表未缓存，且当前无法登录教务系统（可能在校外或网络异常）。请联网后重试。"
        val login = loginState.jwxtLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val api = ScheduleApi(login)
            val term = term0 ?: api.getCurrentTerm()
            if (term0 == null) dataCache.put("schedule_term_list", gson.toJson(listOf(term)))
            if ((ScheduleCache.readOptimizedCourses(dataCache, gson, term, Long.MAX_VALUE)
                    ?: ScheduleCache.readRawCourses(dataCache, gson, term, Long.MAX_VALUE)) == null) {
                ScheduleCache.writeOptimizedCourses(dataCache, gson, term, api.getSchedule(term))
            }
            if (cachedStartDate(term) == null) {
                dataCache.put("start_date_$term", gson.toJson(api.getStartOfTerm(term).toString()))
            }
            null
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "在线获取课表失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getSchedule(dateStr: String?): String {
        ensureScheduleLoaded()?.let { return it }

        val termCode = cachedTermCode() ?: return "课表数据异常，请稍后重试。"

        val courses = ScheduleCache.readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
            ?: ScheduleCache.readRawCourses(dataCache, gson, termCode, Long.MAX_VALUE)
            ?: return "课表数据异常，请稍后重试。"

        val startDate = runCatching {
            cachedStartDate(termCode)?.let { LocalDate.parse(it) }
        }.getOrNull() ?: return "学期起始日期未知，请稍后重试。"

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
            val text = buildString {
                append("考试安排（${exams.size}场）：\n")
                exams.forEach { e ->
                    append("• ${e.courseName}，${e.examDate} ${e.examTime}，${e.location}，座位：${e.seatNumber.ifBlank { "待定" }}\n")
                }
            }
            dataCache.put("agent_exam", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_exam", "获取考试安排失败：${e.message ?: "网络异常"}")
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

            // 复用页面的「CDN / 直连教务」选择。直连较重（每楼逐节查询，约11次请求/楼），
            // 仅在用户已开启直连且指定了具体楼栋时启用，避免对全校区直连冲击学校服务器。
            val preferDirect = context.getSharedPreferences("empty_room", Context.MODE_PRIVATE)
                .getBoolean("empty_room_use_direct_query", false)
            val directClient = if (preferDirect && building != null) {
                loginState.getDirectClient()
                    ?: run { if (loginState.jwxtLogin == null) tryAutoLogin(LoginType.JWXT); loginState.jwxtLogin?.client }
            } else null
            val usingDirect = directClient != null

            // 并发请求各楼栋，避免整校区串行导致超时
            val allRooms = coroutineScope {
                targetBuildings.map { b ->
                    async(Dispatchers.IO) {
                        runCatching {
                            if (directClient != null)
                                EmptyRoomDirectQuery(directClient, EmptyRoomCache(context)).queryDay(targetCampus, b, dateStr)
                            else api.getEmptyRooms(targetCampus, b, dateStr)
                        }.getOrDefault(emptyList())
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
                if (usingDirect) append(" · 直连")
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
            val text = buildString {
                append("最近${records.size}条考勤记录：\n")
                records.forEach { r ->
                    append("• ${r.courseName}（${r.date} 第${r.startTime}-${r.endTime}节）：${r.status.displayName}")
                    if (r.location.isNotBlank()) append("，${r.location}")
                    append("\n")
                }
            }
            dataCache.put("agent_attendance", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_attendance", "获取考勤记录失败：${e.message ?: "网络异常"}")
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
            val text = buildString {
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
            dataCache.put("agent_grades_${term ?: "all"}", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_grades_${term ?: "all"}", "获取成绩失败：${e.message ?: "网络异常"}")
        }
    }

    private suspend fun getCardBalance(): String {
        if (loginState.campusCardLogin == null && !tryAutoLogin(LoginType.CAMPUS_CARD))
            return "需要校园卡系统登录，请先打开校园卡功能完成认证。"
        val login = loginState.campusCardLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val info = CampusCardApi(login).getCardInfo()
            pendingWidgets.add(CardWidget(info))
            val text = buildString {
                append("校园卡余额：¥%.2f".format(info.balance))
                if (info.pendingAmount > 0) append("，待入账¥%.2f".format(info.pendingAmount))
                if (info.lostFlag) append("（已挂失）")
                if (info.frozenFlag) append("（已冻结）")
            }
            dataCache.put("agent_card", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_card", "获取校园卡余额失败：${e.message ?: "网络异常"}")
        }
    }

    private suspend fun getCardTransactions(days: Int): String {
        if (loginState.campusCardLogin == null && !tryAutoLogin(LoginType.CAMPUS_CARD))
            return "需要校园卡系统登录，请先打开校园卡功能完成认证。"
        val login = loginState.campusCardLogin ?: return "登录未就绪，请稍后再试。"
        val d = days.coerceIn(1, 30)
        return try {
            val (_, txs) = CampusCardApi(login).getTransactions(
                startDate = LocalDate.now().minusDays(d.toLong()),
                endDate = LocalDate.now()
            )
            if (txs.isEmpty()) return "最近${d}天无校园卡消费记录。"
            val text = buildString {
                append("校园卡最近${d}天流水（${txs.size}笔）：\n")
                txs.take(25).forEach { t ->
                    append("• ${t.time} ${t.merchant} ${"%+.2f".format(t.amount)}元，余额${"%.2f".format(t.balance)}\n")
                }
                if (txs.size > 25) append("…还有${txs.size - 25}笔\n")
            }
            dataCache.put("agent_card_tx", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_card_tx", "获取校园卡流水失败：${e.message ?: "网络异常"}")
        }
    }

    private suspend fun getNotifications(limit: Int): String {
        return try {
            val list = com.xjtu.toolbox.notification.NotificationApi()
                .getAllNotifications(1)
                .sortedByDescending { it.date }
                .take(limit.coerceIn(1, 20))
            if (list.isEmpty()) return "暂无通知。"
            val text = buildString {
                append("校内最新通知（${list.size}条）：\n")
                list.forEach { n ->
                    append("• [${n.source.displayName}] ${n.title}（${n.date}）\n${n.link}\n")
                }
            }
            dataCache.put("agent_notifications", text)
            text
        } catch (e: Exception) {
            staleOr("agent_notifications", "获取通知失败：${e.message ?: "网络异常"}")
        }
    }

    // ── 联网搜索/网页阅读（无需登录） ─────────────────────────────────────

    private val webClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private val webUa =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun decodeDdgLink(href: String): String {
        Regex("uddg=([^&]+)").find(href)?.let {
            return runCatching { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrDefault(href)
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    private fun webSearch(query: String, limit: Int): String {
        if (query.isBlank()) return "搜索词为空。"
        return try {
            val url = "https://html.duckduckgo.com/html/?q=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val resp = webClient.newCall(
                okhttp3.Request.Builder().url(url).header("User-Agent", webUa).get().build()
            ).execute()
            val body = resp.body?.string() ?: return "搜索失败：空响应。"
            val doc = org.jsoup.Jsoup.parse(body)
            val results = doc.select("div.result").asSequence()
                .mapNotNull { el ->
                    val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
                    val title = a.text().ifBlank { return@mapNotNull null }
                    val link = decodeDdgLink(a.attr("href"))
                    val snippet = el.selectFirst(".result__snippet")?.text().orEmpty()
                    Triple(title, link, snippet)
                }
                .take(limit.coerceIn(1, 8))
                .toList()
            if (results.isEmpty()) return "未搜到「$query」的结果。"
            buildString {
                append("搜索「$query」结果：\n")
                results.forEachIndexed { i, (t, l, s) ->
                    append("${i + 1}. $t\n$l\n")
                    if (s.isNotBlank()) append("${s.take(140)}\n")
                }
                append("\n如需详情可用 web_fetch 抓取对应链接。")
            }
        } catch (e: Exception) {
            "搜索失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getLibraryBooking(): String {
        if (loginState.libraryLogin == null && !tryAutoLogin(LoginType.LIBRARY))
            return "需要图书馆系统登录，请先打开图书馆功能完成认证。"
        val login = loginState.libraryLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val b = com.xjtu.toolbox.library.LibraryApi(login).getMyBooking()
                ?: return "你当前没有图书馆座位预约。"
            buildString {
                append("当前图书馆预约：座位 ${b.seatId ?: "?"}")
                b.area?.let { append("，$it") }
                b.statusText?.let { append("，状态：$it") }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取图书馆预约失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getLibrarySeats(area: String?): String {
        if (loginState.libraryLogin == null && !tryAutoLogin(LoginType.LIBRARY))
            return "需要图书馆系统登录，请先打开图书馆功能完成认证。"
        val login = loginState.libraryLogin ?: return "登录未就绪，请稍后再试。"
        val areaMap = com.xjtu.toolbox.library.LibraryApi.AREA_MAP
        if (area.isNullOrBlank()) {
            return "可查询的图书馆区域：\n" + areaMap.keys.joinToString("、")
        }
        val entry = areaMap.entries.firstOrNull {
            it.key == area || it.key.contains(area) || area.contains(it.key)
        } ?: return "未找到区域「$area」。可选：${areaMap.keys.joinToString("、")}"
        return try {
            when (val r = com.xjtu.toolbox.library.LibraryApi(login).getSeats(entry.value)) {
                is com.xjtu.toolbox.library.SeatResult.Success -> {
                    val free = r.seats.count { it.available }
                    "${entry.key}：空闲 $free / 共 ${r.seats.size} 座"
                }
                is com.xjtu.toolbox.library.SeatResult.AuthError ->
                    "图书馆登录失效，请重新进入图书馆页面。"
                is com.xjtu.toolbox.library.SeatResult.Error -> "查询失败：${r.message}"
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "查询图书馆座位失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getTextbooks(keyword: String): String {
        if (keyword.isBlank()) return "请提供教材或课程名关键词。"
        if (loginState.jiaocaiLogin == null && !tryAutoLogin(LoginType.JIAOCAI))
            return "需要教材中心登录，请先打开教材功能完成认证。"
        val login = loginState.jiaocaiLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val books = com.xjtu.toolbox.jiaocai.JiaocaiApi(login).search(keyword)
            if (books.isEmpty()) return "未找到与「$keyword」相关的教材。"
            buildString {
                append("教材搜索「$keyword」（${books.size}本）：\n")
                books.take(15).forEach { b ->
                    append("• ${b.title}")
                    if (b.author.isNotBlank()) append(" / ${b.author}")
                    if (b.hasFullText) append("（有全文）")
                    append("\n")
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "搜索教材失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getCoupons(): String {
        if (loginState.couponLogin == null && !tryAutoLogin(LoginType.COUPON))
            return "需要加餐券系统登录，请先打开加餐券功能完成认证。"
        val login = loginState.couponLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val page = com.xjtu.toolbox.coupon.CouponApi(login)
                .queryCoupons(com.xjtu.toolbox.coupon.CouponFilter.USABLE)
            if (page.records.isEmpty()) return "你当前没有可用的加餐券。"
            val text = buildString {
                append("我的可用加餐券（${page.records.size}张）：\n")
                page.records.take(20).forEach { c ->
                    append("• ${c.voucherName}（${c.typeName}）：余额¥${"%.2f".format(c.leftAmountFen / 100.0)}，有效期 ${c.startDate}~${c.endDate}\n")
                }
            }
            dataCache.put("agent_coupons", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_coupons", "获取加餐券失败：${e.message ?: "网络异常"}")
        }
    }

    private fun lmsTypeName(t: com.xjtu.toolbox.lms.LmsActivityType): String = when (t) {
        com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK -> "作业"
        com.xjtu.toolbox.lms.LmsActivityType.MATERIAL -> "课件"
        com.xjtu.toolbox.lms.LmsActivityType.LESSON -> "课程/回放"
        com.xjtu.toolbox.lms.LmsActivityType.LECTURE_LIVE -> "直播/回放"
        else -> "其他"
    }

    private suspend fun getLmsCourses(): String {
        if (loginState.lmsLogin == null && !tryAutoLogin(LoginType.LMS))
            return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        val login = loginState.lmsLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val courses = com.xjtu.toolbox.lms.LmsApi(login).getMyCourses()
            if (courses.isEmpty()) return "思源学堂暂无课程。"
            "思源学堂课程（${courses.size}门）：\n" + courses.joinToString("\n") { "• ${it.name}" }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取思源学堂课程失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getLmsActivities(course: String?): String {
        if (course.isNullOrBlank()) return "请指定课程名，或先用 get_lms_courses 查看课程列表。"
        if (loginState.lmsLogin == null && !tryAutoLogin(LoginType.LMS))
            return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        val login = loginState.lmsLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(login)
            val c = api.getMyCourses().firstOrNull {
                it.name == course || it.name.contains(course) || course.contains(it.name)
            } ?: return "未找到课程「$course」。可先用 get_lms_courses 查看课程列表。"
            val acts = api.getCourseActivities(c.id)
            if (acts.isEmpty()) return "「${c.name}」暂无活动。"
            buildString {
                append("「${c.name}」活动（${acts.size}项）：\n")
                acts.groupBy { it.type }.forEach { (t, list) ->
                    append("【${lmsTypeName(t)}】\n")
                    list.take(15).forEach { a ->
                        append("• ${a.title}")
                        a.endTime?.let { append("（截止 $it）") }
                        append("\n")
                    }
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取课程活动失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getLmsAssignments(): String {
        if (loginState.lmsLogin == null && !tryAutoLogin(LoginType.LMS))
            return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        val login = loginState.lmsLogin ?: return "登录未就绪，请稍后再试。"
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(login)
            val homeworks = mutableListOf<Pair<String, com.xjtu.toolbox.lms.LmsActivity>>()
            for (c in api.getMyCourses()) {
                runCatching {
                    api.getCourseActivities(c.id)
                        .filter { it.type == com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK }
                        .forEach { homeworks.add(c.name to it) }
                }
            }
            if (homeworks.isEmpty()) return "思源学堂暂无作业。"
            val sorted = homeworks.sortedBy { it.second.endTime ?: "9999" }
            buildString {
                append("思源学堂作业（${sorted.size}项）：\n")
                sorted.take(25).forEach { (cn, a) ->
                    append("• [$cn] ${a.title}")
                    a.endTime?.let { append("，截止 $it") }
                    append("\n")
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "获取作业失败：${e.message ?: "网络异常"}"
        }
    }

    // ── 应用设置读写（仅非敏感项白名单） ──────────────────────────────────

    private fun getAppSettings(): String {
        val cs = com.xjtu.toolbox.util.CredentialStore(context)
        return buildString {
            append("当前应用设置：\n")
            append("• dark_mode（主题）：${cs.darkMode}　可选 system/light/dark\n")
            append("• default_tab（启动页）：${cs.defaultTab}　可选 HOME/COURSES/TOOLS/PROFILE\n")
            append("• network_mode（网络模式）：${cs.networkMode}\n")
            append("• auto_check_update（自动检查更新）：${cs.autoCheckUpdate}　可选 true/false\n")
            append("• update_channel（更新通道）：${cs.updateChannel}\n")
            append("（账号、密码等敏感项不开放修改）")
        }
    }

    private fun setAppSetting(key: String, value: String): String {
        val cs = com.xjtu.toolbox.util.CredentialStore(context)
        return when (key.trim()) {
            "dark_mode" -> {
                val v = value.trim().lowercase()
                if (v !in listOf("system", "light", "dark")) "dark_mode 只能是 system/light/dark。"
                else { cs.darkMode = v; "已将主题设为 $v。" }
            }
            "default_tab" -> {
                val v = value.trim().uppercase()
                if (v !in listOf("HOME", "COURSES", "TOOLS", "PROFILE")) "default_tab 只能是 HOME/COURSES/TOOLS/PROFILE。"
                else { cs.defaultTab = v; "已将启动页设为 $v。" }
            }
            "network_mode" -> { cs.networkMode = value.trim(); "已将网络模式设为 ${value.trim()}。" }
            "auto_check_update" -> {
                val b = value.trim().toBooleanStrictOrNull() ?: return "auto_check_update 只能是 true/false。"
                cs.autoCheckUpdate = b; "已${if (b) "开启" else "关闭"}自动检查更新。"
            }
            "update_channel" -> { cs.updateChannel = value.trim(); "已将更新通道设为 ${value.trim()}。" }
            else -> "不支持修改「$key」。仅允许：dark_mode / default_tab / network_mode / auto_check_update / update_channel；账号密码等敏感项不可改。"
        }
    }

    private fun webFetch(url: String): String {
        if (!url.startsWith("http")) return "URL 无效，需以 http/https 开头。"
        return try {
            val resp = webClient.newCall(
                okhttp3.Request.Builder().url(url).header("User-Agent", webUa).get().build()
            ).execute()
            if (!resp.isSuccessful) return "抓取失败：HTTP ${resp.code}"
            val html = resp.body?.string() ?: return "抓取失败：空响应。"
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("script, style, nav, header, footer, noscript, form").remove()
            val title = doc.title()
            val text = (doc.selectFirst("article") ?: doc.body() ?: doc).text()
            val trimmed = if (text.length > 3000) text.take(3000) + "…（正文过长已截断）" else text
            if (trimmed.isBlank()) "页面无可提取正文。" else "【$title】\n$trimmed"
        } catch (e: Exception) {
            "抓取失败：${e.message ?: "网络异常"}"
        }
    }
}
