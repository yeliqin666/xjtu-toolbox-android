package com.xjtu.toolbox.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjtu.toolbox.AppLoginState
import com.xjtu.toolbox.attendance.AttendanceApi
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
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
 * - 优先复用 SessionManager 已有站点会话；若对应子系统未登录，调用一次 ensureSite，
 *   60s 内不重试（防止反复触发导致服务端风控）
 * - Auth 异常直接上抛给 AgentRunner 终止循环（不重试）
 * - 优先命中 DataCache，减少对学校服务器的请求
 */
class AgentToolRegistry(
    private val loginState: AppLoginState,
    private val dataCache: DataCache,
    private val context: Context,
    private val disabledCaps: Set<String> = emptySet(),
    private val defaultSearchEngine: String = AgentConfig.SEARCH_BING
) {
    private val gson = Gson()

    private val toolCaps = mapOf(
        "get_schedule" to "schedule",
        "get_exam_schedule" to "schedule",
        "get_school_calendar" to "schedule",
        "search_school_courses" to "schedule",
        "get_empty_rooms" to "schedule",
        "get_attendance" to "attendance",
        "get_grades" to "grades",
        "get_card_balance" to "card",
        "get_card_transactions" to "card",
        "get_notifications" to "notifications",
        "search_yellow_page" to "yellow_page",
        "web_search" to "web",
        "web_fetch" to "web",
        "get_library_booking" to "library",
        "get_library_seats" to "library",
        "get_textbooks" to "textbook",
        "get_coupons" to "coupon",
        "get_lms_courses" to "lms",
        "get_lms_activities" to "lms",
        "get_lms_assignments" to "lms",
        "get_lms_activity_detail" to "lms",
        "read_lms_attachment" to "lms",
        "get_fitness_score" to "fitness",
        "ask_jiaoxiaozhi" to "jiaoxiaozhi",
        "set_app_setting" to "settings_write"
    )

    // 每个 LoginType 上次失败的时间戳；60s 内不重试，防止反复触发服务端风控
    private val loginFailedAt = mutableMapOf<LoginType, Long>()

    // 本轮工具执行产出的富控件（课表卡/成绩卡/教室卡…）；ViewModel 在 run 结束后 drain。
    private val pendingWidgets = mutableListOf<AgentWidget>()

    /** 取走并清空本轮收集的控件。 */
    fun drainWidgets(): List<AgentWidget> = pendingWidgets.toList().also { pendingWidgets.clear() }

    // 学号省码（GB/T 2260 前两位）→ 省份
    private val provinceMap = mapOf(
        "11" to "北京", "12" to "天津", "13" to "河北", "14" to "山西", "15" to "内蒙古",
        "21" to "辽宁", "22" to "吉林", "23" to "黑龙江", "31" to "上海", "32" to "江苏",
        "33" to "浙江", "34" to "安徽", "35" to "福建", "36" to "江西", "37" to "山东",
        "41" to "河南", "42" to "湖北", "43" to "湖南", "44" to "广东", "45" to "广西",
        "46" to "海南", "50" to "重庆", "51" to "四川", "52" to "贵州", "53" to "云南",
        "54" to "西藏", "61" to "陕西", "62" to "甘肃", "63" to "青海", "64" to "宁夏",
        "65" to "新疆", "71" to "台湾", "81" to "香港", "82" to "澳门"
    )

    /**
     * 组装用户画像，注入首条 system prompt：姓名、学号（解析入学年/年级·学期、生源地）、学院。
     * 姓名/学院优先读缓存（昵称、校园卡），缺失则在线拉一网通办个人信息并缓存。
     */
    suspend fun userContext(): String = withContext(Dispatchers.IO) {
        dataCache.get("agent_user_context", Long.MAX_VALUE)?.let { return@withContext it }

        val sid = loginState.activeUsername
        var name = runCatching { com.xjtu.toolbox.util.CredentialStore(context).loadNickname() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        var college: String? = null
        runCatching { com.xjtu.toolbox.card.CampusCardCache.load(context)?.cardInfo }.getOrNull()?.let { ci ->
            if (name.isNullOrBlank()) name = ci.name.takeIf { it.isNotBlank() }
            college = ci.department.takeIf { it.isNotBlank() }
        }
        // 仍缺姓名或学院 → 在线拉一网通办
        if (name.isNullOrBlank() || college.isNullOrBlank()) {
            ensureSite(LoginType.YWTB)?.let { ywtbSite ->
                runCatching { com.xjtu.toolbox.ywtb.YwtbApi(ywtbSite).getUserInfo() }.getOrNull()?.let { ui ->
                    if (name.isNullOrBlank()) name = ui.userName.takeIf { it.isNotBlank() }
                    if (college.isNullOrBlank()) college = ui.organizationName.takeIf { it.isNotBlank() }
                }
            }
        }

        val lines = mutableListOf<String>()
        name?.let { lines.add("- 姓名：$it") }
        if (sid.isNotBlank()) {
            val enrollYear = sid.getOrNull(1)?.let { a -> sid.getOrNull(2)?.let { b -> "20$a$b" } }
            val provCode = if (sid.length >= 5) sid.substring(3, 5) else null
            val province = provCode?.let { provinceMap[it] }
            lines.add(buildString {
                append("- 学号：$sid")
                enrollYear?.let { append("（${it}级，即 $it 年秋季入学；$it 秋为大一上学期，请据当前日期推算其当前年级与学期）") }
            })
            province?.let { lines.add("- 生源地：${it}省（学号省码$provCode）") }
        }
        college?.let { lines.add("- 学院：$it") }

        if (lines.isEmpty()) return@withContext ""
        // 山东彩蛋
        val isShandong = sid.length >= 5 && sid.substring(3, 5) == "37"
        if (isShandong) lines.add("（注：该用户是山东人——交流中记得调侃一句\"沙东人太狠了\"😎）")

        val result = lines.joinToString("\n")
        // 只在姓名+学院都拿到时缓存（避免把"半成品"长期固化）
        if (name != null && college != null) dataCache.put("agent_user_context", result)
        result
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

    private suspend fun tryAutoLogin(type: LoginType): Boolean {
        val now = System.currentTimeMillis()
        if (now - (loginFailedAt[type] ?: 0L) < 60_000L) return false
        val ok = ensureSite(type) != null
        if (!ok) {
            loginFailedAt[type] = now
        }
        return ok
    }

    private suspend fun ensureSite(type: LoginType): SiteSession? =
        runCatching { loginState.sessionManager?.ensureSite(type) }.getOrNull()

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
        arr.add(tool("get_school_calendar",
            "查询西安交通大学校历，含学期起止日期、总周数、当前周、假期、考试周等重要事件。term可填学年或学期关键词，不填返回当前学期。",
            params("term" to strProp("学期关键词，如2025-2026、第一学期；不填查当前学期。"))))
        arr.add(tool("search_school_courses",
            "查询全校开课信息，可按课程名、教师、课程号、班级、院系、校区、星期、节次范围、校公选类别、学期筛选，返回教师、学分、容量、班级、时间地点等。需要教务系统登录。至少提供一个条件，避免无边界查询。",
            params(
                "course_name" to strProp("课程名称，模糊匹配。"),
                "teacher" to strProp("教师姓名，模糊匹配。"),
                "course_code" to strProp("课程号，模糊匹配。"),
                "class_name" to strProp("上课班级，模糊匹配。"),
                "department" to strProp("开课单位名称，如数学学院。"),
                "campus" to strProp("校区：兴庆/雁塔/曲江/苏州/创新港。"),
                "term" to strProp("学期代码，如2025-2026-2；不填使用当前学期。"),
                "weekday" to intProp("星期几，1-7。"),
                "section" to intProp("节次，1-11；同时匹配该节所在课程。"),
                "start_section" to intProp("起始节次，1-11；与end_section配合查时间段。"),
                "end_section" to intProp("结束节次，1-11。"),
                "public_elective" to boolProp("是否仅查校公选课。"),
                "elective_category" to strProp("校公选课类别：基础通识类选修课/基础通识类核心课/钱学森学院特色课。"),
                "limit" to intProp("返回条数，默认10，最多20。")
            )))
        arr.add(tool("get_empty_rooms",
            "查询空闲教室。从CDN获取数据，无需登录。campus可选：兴庆校区/雁塔校区/曲江校区/创新港校区；building为楼名如「主楼A」；section为节次1-11；date可填今天/明天/today/tomorrow/yyyy-MM-dd。",
            params(
                "campus"   to strProp("校区名称，不填则默认兴庆校区。"),
                "building" to strProp("教学楼名称，不填则查该校区所有楼。"),
                "section"  to intProp("节次1-11，不填则查全天空教室。"),
                "date"     to strProp("查询日期：今天/明天/today/tomorrow/yyyy-MM-dd；不填则查今天。")
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
            "查询校园卡最近若干天的消费流水（商户、金额、余额），并给出支出/收入汇总。需要校园卡系统登录。" +
                "整月填 days=30；整学期：先用 get_current_time 拿\"开学至今 N 天\"再把 N 填进来，别硬编死天数（学期可能刚开始）。",
            params("days" to intProp("最近几天，默认7，最多180。"))))
        arr.add(tool("get_notifications",
            "查询校内最新通知公告，含标题、来源、日期、链接。无需登录。可指定来源（某学院/部门）；不指定则看核心来源（教务处+研究生院+学生处）。",
            params(
                "source" to strProp("来源名称，如 教务处/研究生院/机械学院/电气学院/数学学院 等；不填看核心来源。"),
                "limit" to intProp("返回条数，默认10，最多20。")
            )))
        arr.add(tool("search_yellow_page",
            "查询西安交通大学校园黄页中的机构联系电话。可按机构名称、电话号码或机构分类搜索，无需登录。",
            params(
                "query" to strProp("机构名或电话号码关键词，如教务处、保卫处、82665623；可留空配合category列出分类。"),
                "category" to strProp("机构分类：党群机构/行政机构/直属单位/附属单位/其它。"),
                "limit" to intProp("返回条数，默认10，最多20。")
            )))
        arr.add(tool("web_search",
            "联网搜索互联网。用于校历、政策、报名通知、通用知识等本地工具无法回答的问题。返回结构化标题、URL、摘要；随后可用 web_fetch 读取某个 URL。",
            params(
                "query" to strProp("搜索关键词。"),
                "engine" to strProp("搜索引擎：bing / sogou / wechat。不填使用用户设置。"),
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
        arr.add(tool("get_lms_activity_detail",
            "读取思源学堂某门课某个作业/课件/活动的详情，包含说明、截止时间、提交状态、附件名和可下载 URL。需要思源学堂登录。",
            params(
                "course" to strProp("课程名称，模糊匹配。"),
                "activity" to strProp("活动/作业/课件标题，模糊匹配。")
            )))
        arr.add(tool("read_lms_attachment",
            "下载并读取思源学堂附件的文本内容。适合 txt/md/html/json/csv 等文本文件；PDF/Office 文件会提示去页面下载查看。需要思源学堂登录。",
            params(
                "course" to strProp("课程名称，模糊匹配。"),
                "activity" to strProp("活动/作业/课件标题，模糊匹配。"),
                "file" to strProp("附件文件名关键词，模糊匹配。")
            )))
        arr.add(tool("get_fitness_score",
            "查询本人体测成绩，包含总分、等级、学年和各项目成绩。需要体测系统登录。",
            params("year" to strProp("体测学年名称或年份关键词，不填查当前/默认学年。"))))
        arr.add(tool("ask_jiaoxiaozhi",
            "向学校交晓智知识服务提问。适合查询校园政策、办事流程、校内知识库内容；返回内容仍需核验，不应用于课表、成绩、余额等已有专用工具可查询的数据。",
            params(
                "question" to strProp("要交给交晓智回答的完整问题。"),
                "model" to strProp("可选模型：qwen-plus / qwen-max / deepseek-r1 / doubao-pro；默认 qwen-plus。")
            )))
        arr.add(tool("get_app_settings",
            "读取本应用可调设置（主题 / 启动页 / 网络模式 / 自动更新 / 更新通道）当前值与可选项。无需登录。"))
        arr.add(tool("set_app_setting",
            "修改本应用一项非敏感设置（账号密码等敏感项不可改）。无需登录。",
            params(
                "key" to strProp("设置键：dark_mode / default_tab / network_mode / auto_check_update / update_channel。"),
                "value" to strProp("取值，可先用 get_app_settings 查看每项的可选值。")
            )))
        arr.add(tool("calculate",
            "计算数学表达式（四则运算、括号、幂 ^）。算 GPA、排除课程后重算均分、累加金额等务必用它，别心算以免出错。",
            params("expression" to strProp("表达式，如 (3.7*4+4.0*3)/(4+3) 或 92*0.4+88*0.6。"))))
        arr.add(tool("check_update",
            "检查 App 是否有新版本（对比当前版本与发布渠道的最新版）。无需登录。"))
        return JsonArray().apply {
            arr.forEach { definition ->
                val name = definition.asJsonObject
                    .getAsJsonObject("function")
                    .get("name").asString
                val cap = toolCaps[name]
                if (cap == null || cap !in disabledCaps) add(definition)
            }
        }.toString()
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
    private fun boolProp(description: String): JsonObject = propOf("boolean", description)
    private fun propOf(type: String, description: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("description", description)
    }

    suspend fun execute(name: String, argsJson: String): String = withContext(Dispatchers.IO) {
        toolCaps[name]?.takeIf { it in disabledCaps }?.let { cap ->
            return@withContext "能力「$cap」已在屁岱设置中关闭，本次不会调用 ${name}。如需使用，请先在设置里重新开启。"
        }

        val args = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(argsJson, Map::class.java) as Map<String, Any>
        }.getOrDefault(emptyMap())

        when (name) {
            "get_current_time" -> getCurrentTime()
            "get_schedule" -> getSchedule(args["date"] as? String)
            "get_exam_schedule" -> getExamSchedule()
            "get_school_calendar" -> getSchoolCalendar(args["term"] as? String)
            "search_school_courses" -> searchSchoolCourses(
                courseName = args["course_name"] as? String,
                teacher = args["teacher"] as? String,
                courseCode = args["course_code"] as? String,
                className = args["class_name"] as? String,
                department = args["department"] as? String,
                campus = args["campus"] as? String,
                term = args["term"] as? String,
                weekday = (args["weekday"] as? Double)?.toInt(),
                section = (args["section"] as? Double)?.toInt(),
                startSection = (args["start_section"] as? Double)?.toInt(),
                endSection = (args["end_section"] as? Double)?.toInt(),
                publicElective = args["public_elective"] as? Boolean,
                electiveCategory = args["elective_category"] as? String,
                limit = (args["limit"] as? Double)?.toInt() ?: 10
            )
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
            "get_notifications" -> getNotifications(args["source"] as? String, (args["limit"] as? Double)?.toInt() ?: 10)
            "search_yellow_page" -> searchYellowPage(
                query = args["query"] as? String,
                category = args["category"] as? String,
                limit = (args["limit"] as? Double)?.toInt() ?: 10
            )
            "web_search" -> webSearch(
                query = args["query"] as? String ?: "",
                limit = (args["limit"] as? Double)?.toInt() ?: 5,
                engine = args["engine"] as? String
            )
            "web_fetch" -> webFetch(args["url"] as? String ?: "")
            "get_library_booking" -> getLibraryBooking()
            "get_library_seats" -> getLibrarySeats(args["area"] as? String)
            "get_textbooks" -> getTextbooks(args["keyword"] as? String ?: "")
            "get_coupons" -> getCoupons()
            "get_lms_courses" -> getLmsCourses()
            "get_lms_activities" -> getLmsActivities(args["course"] as? String)
            "get_lms_assignments" -> getLmsAssignments()
            "get_lms_activity_detail" -> getLmsActivityDetail(args["course"] as? String, args["activity"] as? String)
            "read_lms_attachment" -> readLmsAttachment(args["course"] as? String, args["activity"] as? String, args["file"] as? String)
            "get_fitness_score" -> getFitnessScore(args["year"] as? String)
            "ask_jiaoxiaozhi" -> askJiaoxiaozhi(
                question = args["question"] as? String ?: "",
                model = args["model"] as? String
            )
            "get_app_settings" -> getAppSettings()
            "set_app_setting" -> setAppSetting(args["key"] as? String ?: "", args["value"] as? String ?: "")
            "calculate" -> calculate(args["expression"] as? String ?: "")
            "check_update" -> checkUpdate()
            else -> "未知工具：$name"
        }
    }

    // ── 实现 ──────────────────────────────────────────────────────────────

    private suspend fun askJiaoxiaozhi(question: String, model: String?): String {
        if (question.isBlank()) return "请提供要向交晓智提问的问题。"
        val manager = loginState.sessionManager ?: return "交晓智会话管理器尚未初始化。"
        val modelId = when (model?.trim()?.lowercase()) {
            null, "", "qwen-plus" -> "qwen-plus"
            "qwen-max" -> "qwen-max"
            "deepseek-r1", "deepseek" -> "ep-20250207092149-pvc95"
            "doubao-pro", "doubao1.5-pro", "doubao" -> "ep-20250219175323-5mvmg"
            else -> "qwen-plus"
        }
        return runCatching {
            com.xjtu.toolbox.jiaoxiaozhi.JiaoxiaozhiCompat(manager).ask(
                question = """
                    你是一个供另一位校园助手参考的知识子代理。
                    请直接回答问题，区分已确认事实与不确定信息，不要声称你能访问未实际提供的数据。

                    问题：$question
                """.trimIndent(),
                modelId = modelId,
                networkEnabled = true,
            )
        }.getOrElse { "交晓智查询失败：${it.message ?: "服务暂不可用"}" }
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

        val termCode = runCatching {
            gson.fromJson(dataCache.get("schedule_term_list", Long.MAX_VALUE), Array<String>::class.java)?.firstOrNull()
        }.getOrNull()
        val weekInfo = termCode?.let {
            val startStr = runCatching { gson.fromJson(dataCache.get("start_date_$it", Long.MAX_VALUE), String::class.java) }.getOrNull()
            val startDate = startStr?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() }
            startDate?.let { sd ->
                val daysSince = java.time.temporal.ChronoUnit.DAYS.between(sd, today).toInt()
                val w = (daysSince / 7) + 1
                // 含起始日与已过天数，便于推算"整学期"区间（如校园卡整学期账单天数）
                if (w in 1..25) "第${w}周（起始 $startStr，开学至今 $daysSince 天）" else null
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

    private suspend fun getSchoolCalendar(term: String?): String {
        return try {
            val terms = com.xjtu.toolbox.calendar.SchoolCalendarApi(ensureSite(LoginType.JWXT)).getTerms()
            if (terms.isEmpty()) return "暂无校历数据。"
            val today = LocalDate.now()
            val selected = if (term.isNullOrBlank()) {
                terms.firstOrNull { today in it.startDate..it.endDate }
                    ?: terms.lastOrNull { it.startDate <= today }
                    ?: terms.last()
            } else {
                terms.firstOrNull {
                    it.id.contains(term, ignoreCase = true) ||
                        it.termName.contains(term, ignoreCase = true) ||
                        it.yearName.contains(term, ignoreCase = true)
                } ?: return "未找到学期「$term」。可选：${terms.takeLast(6).joinToString("、") { it.termName }}"
            }
            val week = selected.currentWeek(today)
            buildString {
                append("${selected.termName}校历：\n")
                append("• 学期：${selected.startDate} 至 ${selected.endDate}，共${selected.totalWeeks}周")
                if (week > 0) append("；今天是第${week}周")
                append("\n")
                if (selected.events.isEmpty()) {
                    append("• 暂无假期或重要事件数据。")
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
            "获取校历失败：${e.message ?: "网络异常"}"
        }
    }

    private fun searchYellowPage(query: String?, category: String?, limit: Int): String {
        return try {
            val data = com.xjtu.toolbox.yellowpage.YellowPageApi(context).getData()
            val categoryId = category?.takeIf { it.isNotBlank() }?.let { name ->
                data.categories.firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.id ?: return "未找到黄页分类「$name」。可选：${data.categories.joinToString("、") { it.name }}"
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
            if (result.isEmpty()) return "校园黄页中没有找到符合条件的机构。"
            buildString {
                append("校园黄页")
                if (data.updateTime.isNotBlank()) append("（更新于${data.updateTime}）")
                append("：\n")
                result.forEach { department ->
                    val categoryName = data.categories.firstOrNull { it.id == department.categoryId }?.name
                    append("• ${department.name}：${department.phone}")
                    categoryName?.let { append("（$it）") }
                    append("\n")
                }
                if (result.size == limit.coerceIn(1, 20)) append("如需查看更多，可打开校园黄页继续搜索。")
            }.trimEnd()
        } catch (e: Exception) {
            "查询校园黄页失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun searchSchoolCourses(
        courseName: String?,
        teacher: String?,
        courseCode: String?,
        className: String?,
        department: String?,
        campus: String?,
        term: String?,
        weekday: Int?,
        section: Int?,
        startSection: Int?,
        endSection: Int?,
        publicElective: Boolean?,
        electiveCategory: String?,
        limit: Int
    ): String {
        val hasFilter = listOf(courseName, teacher, courseCode, className, department, campus, term, electiveCategory)
            .any { !it.isNullOrBlank() } || weekday != null || section != null ||
            startSection != null || endSection != null || publicElective != null
        if (!hasFilter) return "请至少提供课程名、教师、校区、星期或其他一个查询条件。"
        if (weekday != null && weekday !in 1..7) return "weekday 必须是1到7。"
        if (section != null && section !in 1..11) return "section 必须是1到11。"
        if (startSection != null && startSection !in 1..11) return "start_section 必须是1到11。"
        if (endSection != null && endSection !in 1..11) return "end_section 必须是1到11。"
        return try {
            val site = ensureSite(LoginType.JWXT)
                ?: return "需要教务系统登录，请先打开全校课程查询页面完成认证。"
            val api = com.xjtu.toolbox.schedule.SchoolCourseApi(site)
            val termCode = term?.takeIf { it.isNotBlank() } ?: api.getCurrentTerm()
            if (termCode.isBlank()) return "无法获取当前学期，请明确提供学期代码，如2025-2026-2。"
            val departmentCode = department?.takeIf { it.isNotBlank() }?.let { name ->
                api.getDepartments().firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.code ?: return "未找到开课单位「$name」。"
            }
            val campusCode = campus?.takeIf { it.isNotBlank() }?.let { name ->
                api.getCampusList().firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.code ?: return "未找到校区「$name」。可选：${api.getCampusList().joinToString("、") { it.name }}"
            }
            val electiveCategoryCode = electiveCategory?.takeIf { it.isNotBlank() }?.let { name ->
                api.getElectiveCategories().firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.code ?: return "未找到校公选类别「$name」。可选：${api.getElectiveCategories().joinToString("、") { it.name }}"
            }
            val start = startSection ?: section
            val end = endSection ?: section
            val result = api.queryCourses(
                termCode = termCode,
                courseName = courseName,
                courseCode = courseCode,
                teacher = teacher,
                departmentCode = departmentCode,
                className = className,
                campusCode = campusCode,
                isPublicElective = publicElective,
                electiveCategoryCode = electiveCategoryCode,
                weekday = weekday,
                startSection = start,
                endSection = end,
                pageSize = limit.coerceIn(1, 20),
                pageNumber = 1
            )
            if (result.courses.isEmpty()) return "没有找到符合条件的课程。"
            buildString {
                append("全校课程查询：共找到${result.totalSize}条，展示${result.courses.size}条：\n")
                result.courses.forEach { course ->
                    append("• ${course.courseName}（${course.courseCode}-${course.sectionNumber}）")
                    if (course.teacher.isNotBlank()) append(" / ${course.teacher}")
                    append("\n")
                    append("  ${course.credit}学分")
                    if (course.department.isNotBlank()) append(" · ${course.department}")
                    if (course.campus.isNotBlank()) append(" · ${course.campus}")
                    if (course.scheduleLocation.isNotBlank()) append("\n  ${course.scheduleLocation}")
                    if (course.capacity > 0) {
                        append("\n  已选${course.enrollCount}/${course.capacity}，剩余${course.remaining.coerceAtLeast(0)}")
                    }
                    append("\n")
                }
                if (result.totalSize > result.courses.size) append("还有更多结果，可前往全校课程查询页继续筛选。")
            }.trimEnd()
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "查询全校课程失败：${e.message ?: "网络异常"}"
        }
    }

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

        val site = ensureSite(LoginType.JWXT)
            ?: return "课表未缓存，且当前无法登录教务系统（可能在校外或网络异常）。请联网后重试。"
        return try {
            val api = ScheduleApi(site)
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
        val site = ensureSite(LoginType.JWXT)
            ?: return "需要教务系统登录，请先打开任意教务功能完成认证。"
        return try {
            val exams = ScheduleApi(site).getExamSchedule()
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
            val targetDate = parseToolDate(date)
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
                ensureSite(LoginType.JWXT)?.client
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

    private fun parseToolDate(date: String?): LocalDate {
        val today = LocalDate.now()
        return when (date?.trim()?.lowercase()) {
            null, "", "今天", "今日", "today" -> today
            "明天", "tomorrow" -> today.plusDays(1)
            else -> runCatching { LocalDate.parse(date.trim()) }.getOrElse { today }
        }
    }

    private suspend fun getAttendance(limit: Int): String {
        val site = ensureSite(LoginType.ATTENDANCE)
            ?: ensureSite(LoginType.POSTGRADUATE_ATTENDANCE)
            ?: return "需要考勤系统登录，请先打开考勤功能完成认证。"
        return try {
            val api = AttendanceApi(site)
            val termBh = runCatching { api.getTermBh() }.getOrNull()
            val termStartDate = termBh?.let {
                runCatching { api.getTermList().firstOrNull { t -> t.bh == it }?.startDate }.getOrNull()
            }
            val records = api.getWaterRecords(
                termBh = termBh,
                startDate = termStartDate ?: ""
            ).take(limit.coerceIn(1, 30))
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
        val site = ensureSite(LoginType.JWXT)
            ?: return "需要教务系统登录，请先打开任意教务功能完成认证。"
        val studentId = loginState.activeUsername
        if (studentId.isBlank()) return "未获取到学号，请重新登录后再试。"
        return try {
            val all = ScoreReportApi(site).getReportedGrade(studentId)
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
                // 不截断：用户可能要求排除某些课程重算 GPA，需要完整成绩列表
                grades.forEach { g ->
                    append("• ${g.courseName}：${g.score}，${g.coursePoint}学分")
                    g.gpa?.let { append("，绩点%.2f".format(it)) }
                    append("\n")
                }
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
        val site = ensureSite(LoginType.CAMPUS_CARD)
            ?: return "需要校园卡系统登录，请先打开校园卡功能完成认证。"
        return try {
            val info = CampusCardApi(site).getCardInfo()
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
        val site = ensureSite(LoginType.CAMPUS_CARD)
            ?: return "需要校园卡系统登录，请先打开校园卡功能完成认证。"
        val d = days.coerceIn(1, 180)   // 放宽：用户可能要看整月/整学期账单
        return try {
            val txs = CampusCardApi(site).getAllTransactions(
                startDate = LocalDate.now().minusDays(d.toLong()),
                endDate = LocalDate.now()
            )
            if (txs.isEmpty()) return "最近${d}天无校园卡消费记录。"
            // 全量给模型：它可能要按整月统计、分类汇总、找最大笔等，需要完整流水
            val spend = txs.filter { it.amount < 0 }.sumOf { -it.amount }
            val income = txs.filter { it.amount > 0 }.sumOf { it.amount }
            val text = buildString {
                append("校园卡最近${d}天流水（共${txs.size}笔，支出¥${"%.2f".format(spend)}，充值/收入¥${"%.2f".format(income)}）：\n")
                txs.forEach { t ->
                    append("• ${t.time} ${t.merchant} ${"%+.2f".format(t.amount)}元，余额${"%.2f".format(t.balance)}\n")
                }
            }
            dataCache.put("agent_card_tx", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_card_tx", "获取校园卡流水失败：${e.message ?: "网络异常"}")
        }
    }

    private suspend fun getNotifications(source: String?, limit: Int): String {
        return try {
            val all = com.xjtu.toolbox.notification.NotificationSource.entries
            val sources = if (source.isNullOrBlank()) {
                // 默认核心来源，避免并发爬几十个学院官网又慢又常失败
                listOf(
                    com.xjtu.toolbox.notification.NotificationSource.JWC,
                    com.xjtu.toolbox.notification.NotificationSource.GS,
                    com.xjtu.toolbox.notification.NotificationSource.XSC
                )
            } else {
                all.filter { it.displayName.contains(source) || source.contains(it.displayName) }
                    .ifEmpty {
                        return "未找到来源「$source」。可选来源示例：${all.take(12).joinToString("、") { it.displayName }} 等；不指定来源则看核心通知。"
                    }
            }
            val list = com.xjtu.toolbox.notification.NotificationApi()
                .getMergedNotifications(sources, 1)
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

    private fun normalizeSearchLink(href: String): String {
        return when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> href
            else -> href
        }
    }

    private fun parseBingResults(body: String, limit: Int): List<Triple<String, String, String>> {
        val doc = org.jsoup.Jsoup.parse(body)
        return doc.select("li.b_algo").asSequence()
            .mapNotNull { el ->
                val a = el.selectFirst("h2 a") ?: return@mapNotNull null
                val title = a.text().ifBlank { return@mapNotNull null }
                val link = normalizeSearchLink(a.attr("href"))
                val snippet = el.selectFirst(".b_caption p, .b_snippet")?.text().orEmpty()
                Triple(title, link, snippet)
            }
            .filter { (_, link, _) -> link.startsWith("http") }
            .take(limit.coerceIn(1, 8))
            .toList()
    }

    private fun parseSogouResults(body: String, limit: Int): List<Triple<String, String, String>> {
        val doc = org.jsoup.Jsoup.parse(body)
        return doc.select(".results .vrwrap, .results .rb").asSequence()
            .mapNotNull { el ->
                val a = el.selectFirst("h3 a, .vrTitle a") ?: return@mapNotNull null
                val title = a.text().ifBlank { return@mapNotNull null }
                val link = normalizeSearchLink(a.attr("href"))
                val snippet = el.selectFirst(".str_info, .ft, .text-layout")?.text().orEmpty()
                Triple(title, link, snippet)
            }
            .take(limit.coerceIn(1, 8))
            .toList()
    }

    private fun webSearch(query: String, limit: Int, engine: String?): String {
        if (query.isBlank()) return "搜索词为空。"
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val selectedEngine = when (engine?.trim()?.lowercase()) {
                AgentConfig.SEARCH_SOGOU -> AgentConfig.SEARCH_SOGOU
                AgentConfig.SEARCH_WECHAT, "weixin", "wx" -> AgentConfig.SEARCH_WECHAT
                AgentConfig.SEARCH_BING -> AgentConfig.SEARCH_BING
                else -> defaultSearchEngine
            }
            fun fetch(url: String): String? = webClient.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", webUa)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                    .get()
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }

            fun searchOnce(which: String): List<Triple<String, String, String>> = when (which) {
                AgentConfig.SEARCH_SOGOU -> fetch("https://www.sogou.com/web?query=$encoded")
                    ?.let { parseSogouResults(it, limit) }.orEmpty()
                AgentConfig.SEARCH_WECHAT -> fetch("https://weixin.sogou.com/weixin?type=2&query=$encoded")
                    ?.let { parseSogouResults(it, limit) }.orEmpty()
                else -> fetch("https://www.bing.com/search?q=$encoded&mkt=zh-CN&setlang=zh-CN")
                    ?.let { parseBingResults(it, limit) }.orEmpty()
            }
            val primary = searchOnce(selectedEngine)
            val results = primary.ifEmpty {
                listOf(AgentConfig.SEARCH_BING, AgentConfig.SEARCH_SOGOU, AgentConfig.SEARCH_WECHAT)
                    .filter { it != selectedEngine }
                    .asSequence()
                    .map { searchOnce(it) }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            }
            if (results.isEmpty()) return "未搜到「$query」的结果。"
            buildString {
                append("搜索「$query」结果（${AgentConfig.searchEngineLabel(selectedEngine)}）：\n")
                results.forEachIndexed { i, (t, l, s) ->
                    append("${i + 1}. 标题：$t\n")
                    append("   URL：$l\n")
                    if (s.isNotBlank()) append("   摘要：${s.take(180)}\n")
                }
                append("\n这些 URL 可直接传给 web_fetch 继续阅读。")
            }
        } catch (e: Exception) {
            "搜索失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getLibraryBooking(): String {
        val site = ensureSite(LoginType.LIBRARY)
            ?: return "需要图书馆系统登录，请先打开图书馆功能完成认证。"
        return try {
            val b = com.xjtu.toolbox.library.LibraryApi(site).getMyBooking()
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
        val site = ensureSite(LoginType.LIBRARY)
            ?: return "需要图书馆系统登录，请先打开图书馆功能完成认证。"
        val areaMap = com.xjtu.toolbox.library.LibraryApi.AREA_MAP
        if (area.isNullOrBlank()) {
            return "可查询的图书馆区域：\n" + areaMap.keys.joinToString("、")
        }
        val entry = areaMap.entries.firstOrNull {
            it.key == area || it.key.contains(area) || area.contains(it.key)
        } ?: return "未找到区域「$area」。可选：${areaMap.keys.joinToString("、")}"
        return try {
            when (val r = com.xjtu.toolbox.library.LibraryApi(site).getSeats(entry.value)) {
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
        val site = ensureSite(LoginType.JIAOCAI)
            ?: return "需要教材中心登录，请先打开教材功能完成认证。"
        return try {
            val books = com.xjtu.toolbox.jiaocai.JiaocaiApi(site).search(keyword)
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
        val site = ensureSite(LoginType.COUPON)
            ?: return "需要加餐券系统登录，请先打开加餐券功能完成认证。"
        return try {
            val page = com.xjtu.toolbox.coupon.CouponApi(site)
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
        val site = ensureSite(LoginType.LMS)
            ?: return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        return try {
            val courses = com.xjtu.toolbox.lms.LmsApi(site).getMyCourses()
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
        val site = ensureSite(LoginType.LMS)
            ?: return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
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
        val site = ensureSite(LoginType.LMS)
            ?: return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
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

    private fun findLmsCourse(
        api: com.xjtu.toolbox.lms.LmsApi,
        course: String?
    ): com.xjtu.toolbox.lms.LmsCourseSummary? {
        val key = course?.trim().orEmpty()
        if (key.isBlank()) return null
        return api.getMyCourses().firstOrNull {
            it.name == key || it.name.contains(key) || key.contains(it.name)
        }
    }

    private fun findLmsActivity(
        api: com.xjtu.toolbox.lms.LmsApi,
        courseId: Int,
        activity: String?
    ): com.xjtu.toolbox.lms.LmsActivity? {
        val key = activity?.trim().orEmpty()
        if (key.isBlank()) return null
        return api.getCourseActivities(courseId).firstOrNull {
            it.title == key || it.title.contains(key) || key.contains(it.title)
        }
    }

    private suspend fun getLmsActivityDetail(course: String?, activity: String?): String {
        val site = ensureSite(LoginType.LMS)
            ?: return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
            val c = findLmsCourse(api, course) ?: return "请提供有效课程名；可先用 get_lms_courses 查看课程列表。"
            val brief = findLmsActivity(api, c.id, activity)
                ?: return "未找到「${c.name}」中的活动「${activity.orEmpty()}」。可先用 get_lms_activities 查看活动列表。"
            val a = api.getActivityDetail(brief.id)
            buildString {
                append("「${c.name}」${lmsTypeName(a.type)}详情：${a.title}\n")
                a.startTime?.let { append("开始：$it\n") }
                a.endTime?.let { append("截止：$it\n") }
                if (!a.description.isNullOrBlank()) append("说明：${org.jsoup.Jsoup.parse(a.description).text().take(1200)}\n")
                if (a.uploads.isNotEmpty()) {
                    append("附件：\n")
                    a.uploads.forEach { u ->
                        append("• ${u.name}（${u.readableSize}）")
                        val url = u.downloadUrl.ifBlank { u.attachmentUrl.ifBlank { u.previewUrl } }
                        if (url.isNotBlank()) append(" $url")
                        append("\n")
                    }
                }
                a.submissionList?.list?.firstOrNull()?.let { s ->
                    append("提交状态：${s.statusLabel}，分数：${s.scoreDisplay}\n")
                    if (s.content.isNotBlank()) append("提交内容：${org.jsoup.Jsoup.parse(s.content).text().take(800)}\n")
                    if (s.uploads.isNotEmpty()) append("我的提交附件：${s.uploads.joinToString("、") { it.name }}\n")
                    if (s.instructorComment.isNotBlank()) append("教师评语：${s.instructorComment}\n")
                }
            }.trimEnd()
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "读取 LMS 活动详情失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun readLmsAttachment(course: String?, activity: String?, file: String?): String {
        val site = ensureSite(LoginType.LMS)
            ?: return "需要思源学堂登录，请先打开思源学堂功能完成认证。"
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
            val c = findLmsCourse(api, course) ?: return "请提供有效课程名；可先用 get_lms_courses 查看课程列表。"
            val brief = findLmsActivity(api, c.id, activity)
                ?: return "未找到「${c.name}」中的活动「${activity.orEmpty()}」。"
            val detail = api.getActivityDetail(brief.id)
            val uploads = detail.uploads + detail.submissionList?.list.orEmpty().flatMap { it.uploads }
            val key = file?.trim().orEmpty()
            val upload = uploads.firstOrNull { key.isBlank() || it.name.contains(key, ignoreCase = true) }
                ?: return "未找到附件「$key」。可用 get_lms_activity_detail 查看附件名。"
            val url = upload.downloadUrl.ifBlank { upload.attachmentUrl.ifBlank { upload.previewUrl } }
            if (url.isBlank()) return "附件「${upload.name}」没有可下载 URL。"
            val ext = upload.name.substringAfterLast('.', "").lowercase()
            if (ext in listOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "zip", "rar", "7z")) {
                return "附件「${upload.name}」是 ${ext.uppercase()} 文件，屁岱当前不直接解析二进制内容；可打开 LMS 页面或下载记录查看。下载 URL：$url"
            }
            val bytes = api.downloadBytes(url) ?: return "下载附件失败。"
            val text = bytes.toString(Charsets.UTF_8)
            "附件「${upload.name}」内容：\n" + text.take(4000) + if (text.length > 4000) "\n…（已截断）" else ""
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "读取 LMS 附件失败：${e.message ?: "网络异常"}"
        }
    }

    private suspend fun getFitnessScore(year: String?): String {
        val site = ensureSite(LoginType.FITNESS)
            ?: return "需要体测系统登录，请先打开体测查询功能完成认证。"
        return try {
            val api = com.xjtu.toolbox.fitness.FitnessApi(site)
            val years = api.getYears()
            val selected = year?.takeIf { it.isNotBlank() }?.let { key ->
                years.firstOrNull { it.name.contains(key) || it.yearNum.contains(key) }
            } ?: years.firstOrNull { it.checked } ?: years.firstOrNull()
            selected ?: return "暂无可查询的体测学年。"
            val score = api.getScore(selected.yearNum)
            buildString {
                append("体测成绩（${selected.name}）：${score.studentName} ${score.studentNumber}\n")
                append("总分：${score.totalScore}，等级：${score.totalGrade}\n")
                if (score.reportStatus.isNotBlank()) append("状态：${score.reportStatus}\n")
                score.items.forEach { item ->
                    append("• ${item.name}: ${item.value}")
                    if (item.grade.isNotBlank()) append("（${item.grade}）")
                    append("\n")
                }
            }.trimEnd()
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            "查询体测成绩失败：${e.message ?: "网络异常"}"
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
                else {
                    cs.darkMode = v
                    AgentRuntimeHooks.applyDarkMode?.invoke(v)   // 即时刷新主题，而非只写 pref
                    "已将主题设为 $v（已即时生效）。"
                }
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

    private suspend fun checkUpdate(): String {
        val cur = com.xjtu.toolbox.BuildConfig.VERSION_NAME
        val channel = runCatching { com.xjtu.toolbox.util.CredentialStore(context).updateChannel }
            .getOrDefault("stable")
        return try {
            val info = com.xjtu.toolbox.util.AppUpdater.check(channel)
                ?: return "当前版本 v$cur；暂时没查到更新信息。"
            if (info.version.isNotBlank() && info.version != cur)
                "发现新版本 v${info.version}（你当前 v$cur）。可在「我的 → 检查更新」里下载更新。"
            else "当前已是最新版本 v$cur。"
        } catch (e: Exception) {
            "检查更新失败：${e.message ?: "网络异常"}"
        }
    }

    private fun calculate(expr: String): String {
        if (expr.isBlank()) return "请提供要计算的表达式。"
        return try {
            val v = ExprEval(expr).parse()
            val s = if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15)
                v.toLong().toString()
            else "%.4f".format(v).trimEnd('0').trimEnd('.')
            "$expr = $s"
        } catch (e: Exception) {
            "无法计算「$expr」：${e.message ?: "表达式有误"}"
        }
    }

    /** 极简安全表达式求值：四则、括号、幂(^)、一元正负。不依赖任何脚本引擎。 */
    private class ExprEval(private val s: String) {
        private var pos = 0
        fun parse(): Double {
            val v = expr(); skipWs()
            if (pos < s.length) throw IllegalArgumentException("多余字符")
            return v
        }
        private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun peek(): Char { skipWs(); return if (pos < s.length) s[pos] else '\u0000' }
        private fun expr(): Double {
            var v = term()
            while (true) when (peek()) {
                '+' -> { pos++; v += term() }
                '-' -> { pos++; v -= term() }
                else -> return v
            }
        }
        private fun term(): Double {
            var v = power()
            while (true) when (peek()) {
                '*' -> { pos++; v *= power() }
                '/' -> { pos++; v /= power() }
                else -> return v
            }
        }
        private fun power(): Double {
            val b = unary()
            return if (peek() == '^') { pos++; Math.pow(b, power()) } else b
        }
        private fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            else -> atom()
        }
        private fun atom(): Double {
            if (peek() == '(') {
                pos++; val v = expr()
                if (peek() != ')') throw IllegalArgumentException("缺少 )")
                pos++; return v
            }
            skipWs()
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos == start) throw IllegalArgumentException("非法字符")
            return s.substring(start, pos).toDouble()
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
