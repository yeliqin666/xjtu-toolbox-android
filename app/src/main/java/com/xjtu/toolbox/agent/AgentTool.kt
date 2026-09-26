package com.xjtu.toolbox.agent

import kotlinx.serialization.json.jsonObject
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import com.xjtu.toolbox.network.HttpClients
import android.content.Intent
import android.provider.AlarmClock
import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.card.CampusCardApi
import com.xjtu.toolbox.emptyroom.CAMPUS_BUILDINGS
import com.xjtu.toolbox.emptyroom.EmptyRoomApi
import com.xjtu.toolbox.emptyroom.EmptyRoomCache
import com.xjtu.toolbox.emptyroom.EmptyRoomDirectQuery
import com.xjtu.toolbox.emptyroom.LIVE_CAMPUSES
import com.xjtu.toolbox.emptyroom.LiveRoomApi
import com.xjtu.toolbox.fitness.orderedFitnessYears
import com.xjtu.toolbox.fitness.pickFitnessYear
import com.xjtu.toolbox.fitness.yearValue
import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ScheduleApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.score.ScoreReportApi
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.XjtuTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * `app_guide` 的内容：App 功能与入口。
 *
 * 以前常驻在系统提示里（约 600 字），可只有「去哪办」「App 能不能做」这类问题才用得上，
 * 其余每轮都白付这些 token，所以挪到工具后面按需取。
 * 这是 App 页面的一份手写副本，**加减页面时记得同步**——以前写错过（日程「导出图片」其实走不到）。
 */
private const val APP_GUIDE = """App 功能与入口：
- 日程（底栏）：课表与教材、考试安排、自建日程；可切学期、导出日历（ICS）。
- 成绩查询：各学期成绩、GPA、成绩报表。电子成绩单另有一页：一键向学校申请并下载 PDF。
- 空闲教室：默认看此刻实时状态（空闲 / 其它使用及人数 / 上课中，兴庆、雁塔、创新港）；右上角可切到 CDN 课表或直查教务，按今天、明天逐节查，可筛「现在空闲」「刚解放」「大教室」。
- 考勤：考勤流水、打卡流水、统计，以及请假的提交、撤回和销假；另有快速考勤流水（人脸 / 班牌打卡）。
- 校园卡：余额、流水、消费分析；付款码单独一页。
- 加餐券：领取和使用。
- 图书馆：座位查询、预约等。
- 场馆预约：体育场馆按时段预订、看订单。
- 思源学堂：课程、作业、课件、回放。
- 教材：查本人教材、书架、全文库在线阅读。
- 本科评教：可一键好评。
- 其他：全校课程（按课程名或老师查开课）、校历、校园黄页、体测成绩、通知公告（多来源合并）、教师主页、仲英学辅资料站（课件、历年卷等资料的预览和下载）、WebVPN 链接转换（校外访问内网）、扫码登录（首页左上角，扫统一身份认证的二维码）、反馈与建议（「我的」页）。
- 选课、交作业、缴费不在 App 里，要去学校对应系统。"""

/** `app_guide` 的内容：校区食堂与开放时间。来源：学校后勤官网、交大新闻网及公开报道。 */
private const val XINGQING_GUIDE = """兴庆校区：
- 学生食堂：梧桐苑和康桥苑。梧桐苑三层，二楼是自选餐厅。康桥苑价格更便宜，离东南门和操场近：一楼大众伙食，二楼特色小吃和自选，三楼东苑食堂有点餐、套餐和自助餐，还有超市、奶茶店和瑞幸。
- 图书馆约 23:00 闭馆，二层连廊和流通大厅 24 小时开放；主楼群约 22:30 关门。
- 主楼在校园南部，中、东、西楼在北部。

创新港：
- 食堂：A 区和鸣苑、B 区惠风苑、C 区朗清苑，另有涵英楼食堂、同和苑，都在各自的宿舍园区里。
- 供餐时间：早餐 6:30–9:30，午餐 11:00–13:30，晚餐 17:00–19:30；部分档口有夜宵到 23:00。"""

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
    private val defaultSearchEngine: String = AgentConfig.SEARCH_AUTO
) {

    /**
     * 限流分组键：同一个后端系统的两次调用之间要隔开一点，别连着猛打学校服务器。
     * 返回 null 表示纯本地工具（算术、设闹钟、记偏好等），不需要间隔。
     */
    fun rateLimitKeyOf(toolName: String): String? = toolSites[toolName]

    /** 记住的用户偏好，拼进系统提示。走注册表是因为 ViewModel 手上没有 Context。 */
    fun memoryBlock(): String = AgentMemory.promptBlock(context)

    /** 工具 → 它访问的后端系统。只用于限流分组。 */
    private val toolSites = mapOf(
        "get_schedule" to "schedule",
        "get_exam_schedule" to "schedule",
        "get_calendar" to "schedule",
        "search_school_courses" to "schedule",
        "get_empty_rooms" to "schedule",
        "get_attendance" to "attendance",
        "get_grades" to "grades",
        "get_card_info" to "card",
        "get_notifications" to "notifications",
        "search_yellow_page" to "yellow_page",
        "web_search" to "web",
        "web_fetch" to "web",
        "get_library" to "library",
        "list_zyxf" to "zyxf",
        "read_zyxf_file" to "zyxf",
        "get_textbooks" to "textbook",
        "get_coupons" to "coupon",
        "get_lms" to "lms",
        "get_lms_activity" to "lms",
        "get_fitness_score" to "fitness",
        "find_faculty" to "faculty",
    )


    // 本轮工具执行产出的富控件（课表卡/成绩卡/教室卡…）；ViewModel 在 run 结束后 drain。
    private val pendingWidgets = mutableListOf<AgentWidget>()

    /** 取走并清空本轮收集的控件。 */
    fun drainWidgets(): List<AgentWidget> = pendingWidgets.toList().also { pendingWidgets.clear() }

    /** 一网通办拉到的身份。姓名、学院都是死数据，记下来免得为它反复联网。 */
    @kotlinx.serialization.Serializable
    private data class YwtbIdentity(val name: String = "", val college: String = "")

    /** 见 [YwtbIdentity]。缓存目录本身按账号隔离，键不必再带账号。 */
    private val YWTB_IDENTITY_KEY = "agent_ywtb_identity"

    /**
     * 组装用户画像，注入首条 system prompt：姓名、学号（解析入学年/年级·学期、生源地）、学院。
     * 姓名/学院优先读缓存（昵称、校园卡），缺失则在线拉一网通办个人信息并缓存。
     *
     * **不缓存拼好的结果。** 这段话是从昵称、校园卡、学籍档案几处**本地**存储拼出来的，
     * 重算一次只是几个磁盘读。缓存成品反而要在每个上游变化点手动失效，而以前一处都没有：
     * 先用屁岱、后进「我的」页把学籍档案补上，画像里就一直缺校区、专业、班级，
     * 要等 TTL 到期才换——期间屁岱会拿兴庆的教室回答创新港的学生，正是校区字段要防的事。
     * 现在每次重算，档案补上、登录完成都当场反映到下一句。
     *
     * @param allowNetwork 本地补不齐姓名/学院时，是否允许联网拉一网通办。没登录时每轮都去
     *   撞一次网络只会白等，所以由调用方控制节奏（见 AgentViewModel）。
     */
    suspend fun userContext(allowNetwork: Boolean = true): String = withContext(Dispatchers.IO) {
        val sid = loginState.activeUsername
        var name = runCatching { com.xjtu.toolbox.data.CredentialStore(context).loadNickname() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        var college: String? = null
        runCatching { com.xjtu.toolbox.card.CampusCardCache.load(context)?.cardInfo }.getOrNull()?.let { ci ->
            if (name.isNullOrBlank()) name = ci.name.takeIf { it.isNotBlank() }
            college = ci.department.takeIf { it.isNotBlank() }
        }
        // 一网通办那一趟是这里唯一的网络开销，所以单独把结果记下来：姓名和学院是死数据，
        // 记住了就不必再为它联网。缓存目录本身按账号隔离，不会串到别的账号。
        if (name.isNullOrBlank() || college.isNullOrBlank()) {
            dataCache.read<YwtbIdentity>(YWTB_IDENTITY_KEY, com.xjtu.toolbox.data.DataCache.TERM_TTL_MS)?.let { id ->
                if (name.isNullOrBlank()) name = id.name.takeIf { it.isNotBlank() }
                if (college.isNullOrBlank()) college = id.college.takeIf { it.isNotBlank() }
            }
        }
        if (allowNetwork && (name.isNullOrBlank() || college.isNullOrBlank())) {
            ensureSite(LoginType.YWTB)?.let { ywtbSite ->
                runCatching { com.xjtu.toolbox.ywtb.YwtbApi(ywtbSite).getUserInfo() }.getOrNull()?.let { ui ->
                    if (name.isNullOrBlank()) name = ui.userName.takeIf { it.isNotBlank() }
                    if (college.isNullOrBlank()) college = ui.organizationName.takeIf { it.isNotBlank() }
                    val fetchedName = name.orEmpty()
                    val fetchedCollege = college.orEmpty()
                    if (fetchedName.isNotBlank() || fetchedCollege.isNotBlank()) {
                        runCatching { dataCache.write(YWTB_IDENTITY_KEY, YwtbIdentity(fetchedName, fetchedCollege)) }
                    }
                }
            }
        }

        val lines = mutableListOf<String>()
        name?.let { lines.add("- 姓名：$it") }
        if (sid.isNotBlank()) {
            val enrollYear = sid.getOrNull(1)?.let { a -> sid.getOrNull(2)?.let { b -> "20$a$b" } }
            val province = com.xjtu.toolbox.account.ProvinceCode.of(sid)
            lines.add(buildString {
                append("- 学号：$sid")
                enrollYear?.let { append("（${it} 级，$it 年秋入学）") }
                // 年级和学期直接算好写出来，不让模型从入学年自己推（跨年、春季学期最容易推错）。
                // 以课表缓存里的当前学期代码为准，如 2025-2026-2 → 学年起始 2025、第 2 学期
                val y = enrollYear?.toIntOrNull()
                val term = cachedTermCode()?.split("-")
                val termStart = term?.getOrNull(0)?.toIntOrNull()
                val termNo = term?.getOrNull(2)?.toIntOrNull()
                if (y != null && termStart != null && termNo != null && termStart >= y) {
                    val grade = termStart - y + 1
                    val postgrad = runCatching {
                        com.xjtu.toolbox.data.CredentialStore(context).accountType == AccountType.POSTGRADUATE
                    }.getOrDefault(false)
                    val names = if (postgrad) listOf("研一", "研二", "研三") else listOf("大一", "大二", "大三", "大四", "大五")
                    val gradeName = names.getOrElse(grade - 1) { "入学第 $grade 年" }
                    val half = when (termNo) { 1 -> "上"; 2 -> "下"; else -> "小学期" }
                    append("；当前 $gradeName$half（${term.joinToString("-")} 学期）")
                }
            })
            province?.let { lines.add("- 生源地：${it}省（学号省码${sid.substring(3, 5)}）") }
        }
        college?.let { lines.add("- 学院：$it") }

        // 学籍档案（hello.xjtu.edu.cn，本地缓存，不联网）。
        // **刻意不做成工具**：这些字段静态、在"我的"页面就摆着，让模型专门调一次工具去查
        // 纯属绕远。它们的价值在于当**上下文**——比如推荐空教室时默认按校区过滤，
        // 而不是把创新港的教室报给兴庆的用户。
        runCatching { com.xjtu.toolbox.hello.HelloProfileStore.cached(context) }.getOrNull()
            ?.takeIf { it.hasContent() }
            ?.let { p ->
                if (college.isNullOrBlank()) p.departmentName.takeIf { it.isNotBlank() }
                    ?.let { lines.add("- 学院：$it") }
                p.academyName.takeIf { it.isNotBlank() }?.let { lines.add("- 书院：$it") }
                p.professionName.takeIf { it.isNotBlank() }?.let { lines.add("- 专业：$it") }
                p.className.takeIf { it.isNotBlank() }?.let { lines.add("- 班级：$it") }
                p.campusName.takeIf { it.isNotBlank() }?.let {
                    lines.add("- 校区：$it")
                }
                if (p.grade > 0) lines.add("- 年级：${p.grade} 级")
                // 请假、证明、心情不好时最该找的人；「我辅导员电话多少」黄页里查不到
                if (p.counselorName.isNotBlank()) {
                    lines.add("- 辅导员：" + listOf(p.counselorName, p.counselorPhone, p.counselorOffice)
                        .filter { it.isNotBlank() }.joinToString("｜"))
                }
                if (p.classTeacherName.isNotBlank()) {
                    lines.add("- 班主任：" + listOf(p.classTeacherName, p.classTeacherPhone)
                        .filter { it.isNotBlank() }.joinToString("｜"))
                }
            }

        if (lines.isEmpty()) return@withContext ""
        lines.joinToString("\n")
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
        val cached = dataCache.getStale(cacheKey)
        if (cached == null) {
            return ToolReply.noCache(liveError)
        }
        val age = dataCache.ageMs(cacheKey)?.let { humanAge(it) } ?: "较早"
        return ToolReply.stale(age, cached)
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
     * 最近一次 [ensureSite] 失败的原因。给 [loginHint] 用。
     *
     * 单字段而不是让 ensureSite 返回 sealed，是为了保住 `ensureSite(X) ?: return ...`
     * 这个已经用了几十处的调用形状；工具调用是严格串行的（见 AgentRunner），
     * 不存在两次 ensureSite 交叉覆盖这个字段的情况。
     */
    private var lastSiteError: Throwable? = null

    /**
     * 取一个已登录的子系统会话。
     *
     * **silent = true**：模型自己决定调工具时，不能在用户毫不知情的情况下触发一次
     * 带凭据的认证并把短信验证码发到他手机上。撞上 MFA 就抛 MfaRequiredException，
     * 由 [loginHint] 翻译成"你自己去那个页面登一次"。
     */
    private suspend fun ensureSite(type: LoginType): SiteSession? = try {
        lastSiteError = null
        loginState.sessionManager?.ensureSite(type, silent = true)
    } catch (e: Throwable) {
        lastSiteError = e
        null
    }

    /** 同 [ensureSite]，给不在 [LoginType] 里的站点（智慧教室等）用。 */
    private suspend fun ensureSiteKey(siteKey: String): SiteSession? = try {
        lastSiteError = null
        loginState.sessionManager?.ensureSite(siteKey, silent = true)
    } catch (e: Throwable) {
        lastSiteError = e
        null
    }

    /** 模型常写"兴庆""创新港"，补成 [CAMPUS_BUILDINGS] 的键。 */
    private fun normalizeCampus(campus: String?): String {
        val c = campus?.trim().orEmpty()
        if (c.isEmpty()) return "兴庆校区"
        if (c in CAMPUS_BUILDINGS) return c
        return CAMPUS_BUILDINGS.keys.firstOrNull { it.startsWith(c.removeSuffix("校区")) } ?: c
    }

    /**
     * 空闲教室的实时状态版回复。楼不在平台上时返回 null，调用方退回课表数据。
     *
     * 给模型的文字只列空闲的和其它使用的（没排课但有人，人少在前）；上课中的只给个数。
     * 卡片同样只放这两类——上课中的教室列出来也进不去。
     */
    private suspend fun liveRoomsReply(site: SiteSession, campus: String, building: String?): String? {
        val snap = LiveRoomApi(site, EmptyRoomCache(context)).fetchCampus(campus)
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

    /**
     * 登录失败的结果：只给系统名和原因，写成字段。怎么跟用户说、让用户做什么由模型自己判断。
     *
     * 原因要分开写：被熔断、撞 MFA、单纯断网时，一律说「去登录」会把用户支去做一件解决不了问题的事。
     */
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

    // OpenAI function calling 格式的工具描述。
    // 用 JSON 构建器生成（自动转义），description 里有引号也不会截断整串。
    // 新增/修改工具：只需在 buildToolDefinitions() 里加一行 tool(...)。
    val toolDefinitions: String = buildToolDefinitions()

    private fun buildToolDefinitions(): String {
        val arr = mutableListOf<JsonObject>()
        // 描述只写「返回什么、参数怎么填、前置条件」。何时该调、怎么答由系统提示和模型自己定，
        // 不在这里举例、劝说或解释。
        arr.add(tool("get_calendar",
            "第几周、放假、考试周、开学：教学周、当前与下一节次、今天的作息表、开学至今天数，以及学期校历。",
            params("term" to strProp("学年或学期关键词，如 2025-2026、第一学期。缺省：当前学期。"))))
        arr.add(tool("get_schedule",
            "课表，含用户自建日程。无缓存时联网拉取。",
            params(
                "date" to strProp("yyyy-MM-dd，只返回该日。缺省：本周；给了 term 则整学期。"),
                "term" to strProp("学期代码，如 2024-2025-1。缺省：当前学期。")
            )))
        arr.add(tool("get_exam_schedule",
            "考试安排：日期、时间、地点、座位号。需教务登录。"))
        arr.add(tool("search_school_courses",
            "全校开课查询，返回教师、学分、容量、班级、时间地点。至少给一个筛选条件。需教务登录。",
            params(
                "course_name" to strProp("课程名，模糊。"),
                "teacher" to strProp("教师姓名，模糊。"),
                "course_code" to strProp("课程号，模糊。"),
                "class_name" to strProp("上课班级，模糊。"),
                "department" to strProp("开课单位，如 数学学院。"),
                "campus" to strProp("兴庆/雁塔/曲江/苏州/创新港。"),
                "term" to strProp("学期代码，如 2025-2026-2。缺省：当前学期。"),
                "weekday" to intProp("星期 1–7。"),
                "section" to intProp("节次 1–11，匹配覆盖该节的课。"),
                "start_section" to intProp("起始节次 1–11，与 end_section 组成区间。"),
                "end_section" to intProp("结束节次 1–11。"),
                "public_elective" to boolProp("仅校公选课。"),
                "elective_category" to strProp("基础通识类选修课/基础通识类核心课/钱学森学院特色课。"),
                "limit" to intProp("条数，默认 10，上限 20。")
            )))
        arr.add(tool("get_empty_rooms",
            "空闲教室。今天且不给节次（或给的就是当前节）时返回此刻实时状态：空闲、其它使用（没排课但有人，附人数）、上课中；其余按课表逐节。",
            params(
                "campus"   to strProp("兴庆校区/雁塔校区/曲江校区/创新港校区。缺省：兴庆校区。实时状态只有兴庆、雁塔、创新港。"),
                "building" to strProp("楼名，如 主楼A、1号巨构。缺省：全校区。"),
                "section"  to intProp("节次 1–11。缺省：此刻（今天）/全天（其他日期）。"),
                "date"     to strProp("今天/明天/yyyy-MM-dd。缺省：今天。")
            )))
        arr.add(tool("get_attendance",
            "本学期考勤记录（正常/迟到/缺勤/请假）。需考勤登录。",
            params("limit" to intProp("条数，默认 20，上限 200。"))))
        arr.add(tool("get_grades",
            "成绩与加权 GPA。需教务登录。",
            params("term" to strProp("学期代码，如 2024-2025-1。缺省：全部学期。"))))
        arr.add(tool("get_card_info",
            "校园卡余额、消费流水与收支汇总（给 days 时）。需校园卡登录。",
            params("days" to intProp("流水天数 1–180。缺省：不查流水。"))))
        arr.add(tool("get_notifications",
            "校内通知：标题、来源、日期、链接。给 keyword 时用各站自己的站内搜索查全站（含往年），否则列最新。",
            params(
                "keyword" to strProp("关键词，如 推免、放假、奖学金、选课。缺省：不搜，列最新。"),
                "source" to strProp("来源名，如 教务处、OA 通知、仲英书院、电信学部。缺省：按用户身份自动选（本科生：教务处、学生处、实践教学中心、所在书院；研究生：研究生院；都含 OA 与所在学院）。"),
                "limit" to intProp("条数，默认 10，上限 20。")
            )))
        arr.add(tool("search_yellow_page",
            "校园黄页：机构总机电话，不含个人号码。",
            params(
                "query" to strProp("机构名或号码片段。可空，配合 category 列出。"),
                "category" to strProp("党群机构/行政机构/直属单位/附属单位/其它。"),
                "limit" to intProp("条数，默认 10，上限 20。")
            )))
        arr.add(tool("preference",
            "在本机长期保存或删除一条用户偏好。只存可复用的信息，不存一次性事项。",
            params(
                "key" to strProp("偏好名，同名覆盖。"),
                "value" to strProp("内容，一句。缺省：删除该偏好。")
            )))
        arr.add(tool("find_faculty",
            "本校在职教师主页：学院、职称、研究方向、办公地点、邮箱、主页地址。不含学生、行政人员、校外人士。",
            params(
                "name" to strProp("姓名，模糊。"),
                "college" to strProp("学院名，用于重名。"),
                "limit" to intProp("条数，默认 3，上限 8。")
            )))
        arr.add(tool("web_search",
            "联网搜索，返回标题、URL、摘要。",
            params(
                "query" to strProp("关键词。学校政策、办事流程加 site:xjtu.edu.cn 优先查官网。"),
                "engine" to strProp("auto/baidu/so360/wechat/wiki。缺省：用户设置。auto=百度与360合并，wechat=公众号，wiki=百科词条。"),
                "limit" to intProp("条数，默认 8，上限 22。")
            )))
        arr.add(tool("web_fetch",
            "抓取网页正文，转 Markdown，约一万字。",
            params("url" to strProp("http(s) URL。"))))
        arr.add(tool("set_alarm",
            "打开系统闹钟设定闹钟。",
            params(
                "hour" to intProp("0–23。"),
                "minute" to intProp("0–59。"),
                "message" to strProp("标签。"),
                "days" to strProp("重复星期，逗号分隔：MON,TUE,WED,THU,FRI,SAT,SUN。缺省：单次。")
            )))
        arr.add(tool("add_schedule_event",
            "往本 App 的日程里添加一条，只限当前学期。与课程或已有日程时间重叠时不添加，返回冲突；用户确认后带 force=true 重试。",
            params(
                "title" to strProp("标题。"),
                "date" to strProp("yyyy-MM-dd；星期取这一天。"),
                "start" to strProp("开始时间 HH:mm。"),
                "end" to strProp("结束时间 HH:mm。缺省：开始后 1 小时。"),
                "location" to strProp("地点。"),
                "note" to strProp("备注。"),
                "weeks" to strProp("每周重复的教学周，如 3-5,8。缺省：只加 date 所在的那一周。"),
                "force" to boolProp("冲突时仍然添加。")
            )))
        arr.add(tool("get_library",
            "图书馆：本人当前座位预约，以及各区域空座。给 area 时返回该区域空座并附平面图卡片。需图书馆登录。" +
                "推荐去哪自习时按用户所在校区选 campus，用户点名别的校区就用那个。",
            params(
                "campus" to strProp("校区：兴庆 / 雁塔 / 创新港。缺省：账号在图书馆系统里当前的校区。"),
                "area" to strProp("区域名，模糊，如 北楼二层外文库。缺省：列出该校区全部区域及空座数。"),
            )))
        arr.add(tool("list_zyxf",
            "历年卷、复习资料、课件、笔记（仲英学辅资料站，同学共享）：给 keyword 按文件名、目录名检索，否则列出目录。",
            params(
                "keyword" to strProp("关键词，宜用课程名。"),
                "folder_id" to intProp("目录 ID。缺省或 0：根目录。")
            )))
        arr.add(tool("read_zyxf_file",
            "读取仲英学辅资料站文件。文本返回正文；PDF/Office 只返回直链与大小。",
            params(
                "file_id" to intProp("文件 ID，来自 list_zyxf。")
            )))
        arr.add(tool("get_textbooks",
            "本人课程教材（教务教材报表）。需教务登录。",
            params(
                "course" to strProp("课程名关键词。缺省：全部。"),
                "term" to strProp("学期代码，如 2025-2026-2。缺省：当前学期。")
            )))
        arr.add(tool("get_coupons",
            "本人加餐券：可领取、可使用、余额、有效期。需加餐券登录。",
            params("status" to strProp("all=可领取+可使用；available=可领取；usable=可使用。缺省：all。"))))
        arr.add(tool("get_lms",
            "思源学堂：不给 course 列出本人课程；给 course 列出该课的作业、课件、回放等活动；scope=assignments 汇总全部课程的作业（逐课查询，较慢）。需思源学堂登录。",
            params(
                "course" to strProp("课程名，模糊。"),
                "scope" to strProp("assignments：全部作业汇总。")
            )))
        arr.add(tool("get_lms_activity",
            "思源学堂某个活动的详情：说明、截止时间、提交状态、附件名与 URL；给 file 时读取该附件文本（txt/md/html/json/csv；PDF/Office 只返回 URL）。需思源学堂登录。",
            params(
                "course" to strProp("课程名，模糊。"),
                "activity" to strProp("活动标题，模糊。"),
                "file" to strProp("附件名关键词，模糊。")
            )))
        arr.add(tool("get_fitness_score",
            "本人体测成绩：总分、等级、分项。按学年计。需体测登录。",
            params("year" to strProp("学年起始年，2025=2025-2026 学年。缺省：当前已开测学年。"))))
        arr.add(tool("app_setting",
            "本应用设置：不给 value 时列出全部设置的当前值与可选值；给 key 和 value 时修改该项。",
            params(
                "key" to strProp("dark_mode / dynamic_color / home_theme / nav_bar_style / show_quick_actions / default_tab / network_mode / account_type / venue_auto_solve_captcha / update_channel / receive_preview_updates。"),
                "value" to strProp("新取值。")
            )))
        arr.add(tool("app_guide",
            "本 App 有哪些功能、某件事去哪一页办；兴庆、创新港的食堂和开放时间。",
            params("topic" to strProp("app：功能与入口；campus：食堂与开放时间。缺省：两者都给。"))))
        arr.add(tool("calculate",
            "计算表达式：+ - * / ^ 与括号。",
            params("expression" to strProp("如 (3.7*4+4.0*3)/(4+3)。"))))
        return JsonArray(arr).toString()
    }

    /** 构造单个 function-calling 工具对象。params 省略时为无参。 */
    private fun tool(name: String, description: String, params: JsonObject = emptyParams()): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }

    private fun emptyParams(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
        put("required", JsonArray(emptyList()))
    }

    private fun params(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", JsonArray(emptyList()))
    }

    private fun strProp(description: String): JsonObject = propOf("string", description)
    private fun intProp(description: String): JsonObject = propOf("integer", description)
    private fun boolProp(description: String): JsonObject = propOf("boolean", description)
    private fun propOf(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    // 工具参数：模型偶尔把数字写成字符串，按内容解析
    private fun JsonObject.str(key: String): String? = this[key].safeStringOrNull()
    private fun JsonObject.int(key: String): Int? = this[key].safeStringOrNull()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    private fun JsonObject.double(key: String): Double? = this[key].safeDoubleOrNull()
    private fun JsonObject.bool(key: String): Boolean? = this[key].safeStringOrNull()?.let { it.equals("true", ignoreCase = true) }

    suspend fun execute(name: String, argsJson: String): String = withContext(Dispatchers.IO) {

        val args = runCatching { AppJson.parseToJsonElement(argsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))

        val widgetsBefore = pendingWidgets.size
        val result = when (name) {
            // 当前时间、节次、教学周和校历本是同一件事，合成一个工具
            "get_calendar" -> getCurrentTime() + "\n\n" + getSchoolCalendar(args.str("term"))
            "get_schedule" -> getSchedule(args.str("date"), args.str("term"))
            "get_exam_schedule" -> getExamSchedule()
            "search_school_courses" -> searchSchoolCourses(
                courseName = args.str("course_name"),
                teacher = args.str("teacher"),
                courseCode = args.str("course_code"),
                className = args.str("class_name"),
                department = args.str("department"),
                campus = args.str("campus"),
                term = args.str("term"),
                weekday = args.int("weekday"),
                section = args.int("section"),
                startSection = args.int("start_section"),
                endSection = args.int("end_section"),
                publicElective = args.bool("public_elective"),
                electiveCategory = args.str("elective_category"),
                limit = args.int("limit") ?: 10
            )
            "get_empty_rooms" -> getEmptyRooms(
                campus = args.str("campus"),
                building = args.str("building"),
                section = args.int("section"),
                date = args.str("date")
            )  // suspend: parallel per-building fetches inside
            "get_attendance" -> getAttendance(
                limit = args.int("limit") ?: 20
            )
            "get_grades" -> getGrades(args.str("term"))
            "get_card_info" -> getCardInfo(args.int("days"))
            "get_notifications" -> getNotifications(
                args.str("source"),
                args.int("limit") ?: 10,
                (args.str("keyword"))?.trim()?.takeIf { it.isNotEmpty() },
            )
            "search_yellow_page" -> searchYellowPage(
                query = args.str("query"),
                category = args.str("category"),
                limit = args.int("limit") ?: 10
            )
            // 给 value 是记下，不给是删掉
            "preference" -> {
                val key = args.str("key") ?: ""
                val value = (args.str("value")).orEmpty()
                if (value.isBlank()) AgentMemory.forget(context, key)
                else AgentMemory.remember(context, key, value)
            }
            "find_faculty" -> findFaculty(
                name = args.str("name") ?: "",
                college = args.str("college"),
                limit = args.int("limit") ?: 3
            )
            "web_search" -> webSearch(
                query = args.str("query") ?: "",
                limit = args.int("limit") ?: 8,
                engine = args.str("engine")
            )
            "web_fetch" -> webFetch(args.str("url") ?: "")
            "set_alarm" -> setAlarm(
                hour = args.int("hour"),
                minute = args.int("minute"),
                message = args.str("message"),
                days = args.str("days")
            )
            "add_schedule_event" -> addScheduleEvent(
                title = args.str("title"),
                date = args.str("date"),
                start = args.str("start"),
                end = args.str("end"),
                location = args.str("location"),
                note = args.str("note"),
                weeks = args.str("weeks"),
                force = args.bool("force") ?: false,
            )
            // 给关键词就检索，不给就按目录浏览
            "list_zyxf" -> {
                val keyword = (args.str("keyword")).orEmpty()
                if (keyword.isNotBlank()) searchZyxf(keyword)
                else browseZyxf(args.int("folder_id") ?: 0)
            }
            "read_zyxf_file" -> readZyxfFile(args.int("file_id") ?: 0)
            // 我的预约和区域空座一次给全
            "get_library" -> getLibraryBooking() + "\n\n" +
                getLibrarySeats(args.str("campus"), args.str("area"))
            "get_textbooks" -> getTextbooks(args.str("course"), args.str("term"))
            "get_coupons" -> getCoupons(args.str("status"))
            "get_lms" -> {
                val course = (args.str("course")).orEmpty()
                when {
                    args.str("scope") == "assignments" -> getLmsAssignments()
                    course.isBlank() -> getLmsCourses()
                    else -> getLmsActivities(course)
                }
            }
            "get_lms_activity" -> {
                val file = (args.str("file")).orEmpty()
                if (file.isNotBlank()) readLmsAttachment(args.str("course"), args.str("activity"), file)
                else getLmsActivityDetail(args.str("course"), args.str("activity"))
            }
            "get_fitness_score" -> getFitnessScore(args.str("year"))
            // 给了 key 和 value 是改，否则列出全部设置
            "app_setting" -> {
                val key = (args.str("key")).orEmpty()
                val value = (args.str("value")).orEmpty()
                if (key.isBlank() || value.isBlank()) getAppSettings() else setAppSetting(key, value)
            }
            "calculate" -> calculate(args.str("expression") ?: "")
            "app_guide" -> when ((args.str("topic"))?.trim()?.lowercase()) {
                "app" -> APP_GUIDE
                "campus", "xingqing" -> XINGQING_GUIDE
                else -> APP_GUIDE + "\n\n" + XINGQING_GUIDE
            }
            else -> ToolReply.notFound("tool", name)
        }
        // 这次调用生成了卡片（课表、成绩、空教室……）就明确告诉模型：用户已经看到了
        if (pendingWidgets.size > widgetsBefore) result + "\n" + ToolReply.CARD_SHOWN else result
    }

    // ── 实现 ──────────────────────────────────────────────────────────────

    /**
     * 查教师主页。
     *
     * 不需要任何登录——faculty.xjtu.edu.cn 是公开站点。
     *
     * 结果按精确同名优先重排：服务端的 `teacherName` 是模糊匹配且规则不可推断
     * （实测「刘」255 条、「刘进军」234 条，加字反而变多），直接取前几条经常
     * 给出的是别人。同名的按点击量排，通常是用户想找的那位。
     *
     * 只回文本字段，不回头像；联系方式照原样给出——这是学校公开发布的信息，
     * 不做二次加工也不猜哪个电话是私人的。
     */
    private suspend fun findFaculty(name: String, college: String?, limit: Int): String {
        if (name.isBlank()) return ToolReply.missing("name")
        val n = limit.coerceIn(1, 8)
        val all = try {
            com.xjtu.toolbox.faculty.FacultyApi().searchAll(
                query = com.xjtu.toolbox.faculty.FacultySearchQuery(name = name),
                limit = 60,
            )
        } catch (e: Exception) {
            return ToolReply.failed("find_faculty", e.message)
        }
        // 学院只能在客户端筛：检索接口的学院参数要的是数字 id，而模型手上只有名字。
        // 筛空了就退回不筛，宁可多给几条也别因为学院名写法不同（"电信学部"/"电信学院"）
        // 把正确结果全滤掉。
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
                val w = com.xjtu.toolbox.schedule.TermWeeks.weekOf(sd, today)
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

    /**
     * 本学期，和日程页、首页、小组件认同一个键（[ScheduleCache.readCurrentTerm]）。
     * 以前取学期列表的第一个：教务把下学期挂出来以后它就排在最前，屁岱的日程写进了
     * 日程页压根不显示的学期。
     */
    private fun cachedTermCode(): String? = ScheduleCache.readCurrentTerm(dataCache)

    private fun cachedStartDate(term: String): LocalDate? = ScheduleCache.readStartDate(dataCache, term)

    private suspend fun getSchoolCalendar(term: String?): String {
        return try {
            val terms = com.xjtu.toolbox.calendar.SchoolCalendarApi().getTerms()
            if (terms.isEmpty()) return ToolReply.empty("school_calendar")
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

    private fun searchYellowPage(query: String?, category: String?, limit: Int): String {
        return try {
            val data = com.xjtu.toolbox.yellowpage.YellowPageApi(context).getData()
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
        if (!hasFilter) return ToolReply.missing("any_filter")
        if (weekday != null && weekday !in 1..7) return ToolReply.outOfRange("weekday", "1-7")
        if (section != null && section !in 1..11) return ToolReply.outOfRange("section", "1-11")
        if (startSection != null && startSection !in 1..11) return ToolReply.outOfRange("start_section", "1-11")
        if (endSection != null && endSection !in 1..11) return ToolReply.outOfRange("end_section", "1-11")
        return try {
            val site = ensureSite(LoginType.JWXT)
                ?: return loginHint(LoginType.JWXT)
            val api = com.xjtu.toolbox.schedule.SchoolCourseApi(site)
            val termCode = term?.takeIf { it.isNotBlank() } ?: api.getCurrentTerm()
            if (termCode.isBlank()) return ToolReply.missing("term")
            val departmentCode = department?.takeIf { it.isNotBlank() }?.let { name ->
                api.getDepartments().firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.code ?: return ToolReply.notFound("department", name)
            }
            val campusCode = campus?.takeIf { it.isNotBlank() }?.let { name ->
                api.getCampusList().firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.code ?: return ToolReply.notFound("campus", name, api.getCampusList().map { it.name })
            }
            val electiveCategoryCode = electiveCategory?.takeIf { it.isNotBlank() }?.let { name ->
                api.getElectiveCategories().firstOrNull {
                    it.name == name || it.name.contains(name) || name.contains(it.name)
                }?.code ?: return ToolReply.notFound("elective_category", name, api.getElectiveCategories().map { it.name })
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
            if (result.courses.isEmpty()) return ToolReply.empty("school_courses")
            buildString {
                append("全校课程查询：共找到${result.totalSize}条，展示${result.courses.size}条：\n")
                result.courses.forEach { course ->
                    append("• ${course.courseName}（${course.courseCode}-${course.sectionNumber}）")
                    if (course.teacher.isNotBlank()) append(" / ${course.teacher}")
                    append("\n")
                    append("  ${course.credit}学分")
                    if (course.className.isNotBlank()) append(" · 班级:${course.className}")
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
            ToolReply.failed("search_school_courses", e.message)
        }
    }

    /**
     * 确保课表（学期 / 课程 / 起始日）就绪：缓存缺失时**直接在线拉取并写缓存**，
     * 而不是让用户「先去打开课表页同步」。需教务系统登录。返回 null 表示就绪，否则为错误提示。
     */
    /**
     * 确保某学期（[targetTerm] 为空则当前学期）的课表 + 起始日就绪：缺失则在线拉取并写缓存。
     * 返回 null 表示就绪，否则为错误提示。
     */
    private suspend fun ensureScheduleLoaded(targetTerm: String? = null): String? {
        val term0 = targetTerm?.takeIf { it.isNotBlank() } ?: cachedTermCode()
        val coursesCached = term0 != null && ScheduleCache.readCourses(dataCache, term0) != null
        if (coursesCached && cachedStartDate(term0) != null) return null

        val site = ensureSite(LoginType.JWXT)
            ?: return ToolReply.noCache(ToolReply.loginFailed("教务系统", "unreachable"))
        return try {
            val api = ScheduleApi(site)
            val term = term0 ?: api.getCurrentTerm()
            if (term0 == null) ScheduleCache.writeCurrentTerm(dataCache, term)
            if (ScheduleCache.readTermList(dataCache).isEmpty()) ScheduleCache.writeTermList(dataCache, listOf(term))
            runCatching {
                if (com.xjtu.toolbox.schedule.ScheduleTermStore.read(dataCache).isEmpty()) {
                    api.getTermList()
                }
                com.xjtu.toolbox.schedule.ScheduleTermStore.merge(dataCache, api.termNames())
            }
            if (ScheduleCache.readCourses(dataCache, term) == null) {
                val fresh = com.xjtu.toolbox.schedule.ScheduleSourceRouter.getSchedule(
                    context = context,
                    jwxt = api,
                    termCode = term,
                    manager = loginState.sessionManager,
                    accountType = loginState.accountType,
                )
                ScheduleCache.writeOptimizedCourses(dataCache, term, fresh)
            }
            if (cachedStartDate(term) == null) {
                ScheduleCache.writeStartDate(dataCache, term, api.getStartOfTerm(term))
            }
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
            com.xjtu.toolbox.data.AppDatabase.getInstance(context)
                .customCourseDao()
                .getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: "", termCode)
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
                    append("${it.courseName}｜${dayNames.getOrElse(it.dayOfWeek) { "" }} ${it.startSection}–${it.endSection}节｜${weekRange(it).trim()}｜${it.location}\n")
                }
            }.withChangeNote(changeNote)
        }

        val startDate = runCatching {
            cachedStartDate(termCode)
        }.getOrNull() ?: return ToolReply.failed("get_schedule", "no_term_start_date")

        // 法定假日：课表照排，但那天停课。数据源只有放假日，没有调休补课日，所以只标停课
        val holidays = runCatching { com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(context) }
            .getOrDefault(emptyMap())

        if (dateStr != null) {
            val targetDate = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now() }
            val weekNum = com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate, targetDate)
            val dayCourses = courses.filter { it.dayOfWeek == targetDate.dayOfWeek.value && it.isInWeek(weekNum) }
                .sortedBy { it.startSection }
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
                dayCourses.forEach { c -> append(courseLine(c, targetDate, withTeacher = true)).append('\n') }
            }.withChangeNote(changeNote)
        } else {
            val today = LocalDate.now()
            val weekNum = com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate, today)
            if (weekNum <= 0) return "当前不在学期内。"
            val weekCourses = courses.filter { it.isInWeek(weekNum) }
                .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))
            if (weekCourses.isEmpty()) return "第${weekNum}周没有课。"
            pendingWidgets.add(ScheduleWidget("第${weekNum}周课表", weekCourses))
            return buildString {
                append("第${weekNum}周：\n")
                // 每门课按它那天的日期算作息，跨月那周也不会错
                val monday = startDate.plusWeeks((weekNum - 1).toLong())
                weekCourses.forEach { c ->
                    val day = monday.plusDays((c.dayOfWeek - 1).toLong())
                    append(courseLine(c, day))
                    if (!c.isUserCreated) holidays[day]?.let { append("｜停课（$it）") }
                    append('\n')
                }
            }.withChangeNote(changeNote)
        }
    }

    /**
     * 课表的一行：`课程｜周二 10:10–12:00（3–4节）｜地点｜教师`。
     *
     * 直接写出钟点，模型不必知道作息表——学校有冬、夏两套作息（5–9 月下午晚上推后 30 分钟），
     * 让模型按节次自己推，问「明天几点下课」时容易错。作息按**那节课所在日期**的月份定。
     * 自建日程带分钟级时间，优先用它。
     */
    private fun courseLine(c: CourseItem, date: LocalDate, withTeacher: Boolean = false): String {
        val summer = XjtuTime.isSummerTime(date.monthValue)
        fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
        val start = c.startMinuteOfDay.takeIf { it >= 0 }?.let(::hhmm)
            ?: XjtuTime.getClassTime(c.startSection, summer)?.start?.toString()
        val end = c.endMinuteOfDay.takeIf { it >= 0 }?.let(::hhmm)
            ?: XjtuTime.getClassTime(c.endSection, summer)?.end?.toString()
        val days = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val time = if (start != null && end != null) "$start–$end" else ""
        val sections = if (c.courseType == "日程" || c.courseType == "自定义") "" else "（${c.startSection}–${c.endSection}节）"
        return listOfNotNull(
            c.courseName,
            "${days.getOrElse(c.dayOfWeek) { "" }} $time$sections".trim(),
            c.location.ifBlank { null },
            c.teacher.takeIf { withTeacher && it.isNotBlank() },
        ).joinToString("｜")
    }

    /**
     * 官方调课备注（`bz`），只有课表源选 jwapp 时才有。对不上号（换过源、这门课没有
     * 变更记录）就不提；报太多反而像凑数，最多挑 3 条。
     */
    private fun scheduleChangeNote(termCode: String, courses: List<CourseItem>): String? {
        val events = com.xjtu.toolbox.schedule.ScheduleSourceRouter.changeEvents(context, termCode)
            .filter { it.reason.isNotBlank() }
        if (events.isEmpty()) return null
        val codes = courses.map { it.courseCode }.toSet()
        val relevant = events.filter { it.courseCode.isBlank() || it.courseCode in codes }.take(3)
        if (relevant.isEmpty()) return null
        return "近期调课：" + relevant.joinToString("；") { "${it.courseName}${it.describe()}，原因：${it.reason}" }
    }

    /** 整学期列表里给每条标上周次：`第1-4、6-16周 `。周次拿不到就不标。 */
    private fun weekRange(c: CourseItem): String {
        val weeks = c.getWeeks()
        if (weeks.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var i = 0
        while (i < weeks.size) {
            var j = i
            while (j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
            parts += if (j > i) "${weeks[i]}-${weeks[j]}" else "${weeks[i]}"
            i = j + 1
        }
        return "第${parts.joinToString("、")}周 "
    }

    private fun String.withChangeNote(note: String?): String = if (note.isNullOrBlank()) this else "$this\n$note"

    private suspend fun getExamSchedule(): String {
        val site = ensureSite(LoginType.JWXT)
            ?: return loginHint(LoginType.JWXT)
        return try {
            val exams = ScheduleApi(site).getExamSchedule()
            if (exams.isEmpty()) return ToolReply.empty("exams")
            pendingWidgets.add(ExamWidget(exams))
            val text = buildString {
                append("考试安排（${exams.size}场）：\n")
                exams.forEach { e ->
                    append("${e.courseName}｜${e.examDate} ${e.examTime}｜${e.location}｜座位 ${e.seatNumber.ifBlank { "待定" }}\n")
                }
            }
            dataCache.put("agent_exam", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_exam", ToolReply.failed("get_exam_schedule", e.message))
        }
    }

    private suspend fun getEmptyRooms(campus: String?, building: String?, section: Int?, date: String?): String {
        return try {
            val api = EmptyRoomApi(context)
            val targetDate = parseToolDate(date)
            val dateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            val targetCampus = normalizeCampus(campus)
            val buildings = CAMPUS_BUILDINGS[targetCampus]
                ?: return ToolReply.notFound("campus", targetCampus, CAMPUS_BUILDINGS.keys)

            // 问的是"现在"：今天、没给节次或给的就是当前节 → 先用实时状态。
            // 实时状态比课表多一层信息（没排课但有人、有几个人），正是"现在去哪自习"要的。
            // 用户在页面里明确切到了课表数据源，就尊重他的选择。
            val sourcePref = context.getSharedPreferences("empty_room", Context.MODE_PRIVATE)
                .getString("empty_room_source", null)
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
                                EmptyRoomDirectQuery(directClient, EmptyRoomCache(context)).queryDay(targetCampus, b, dateStr)
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

    private suspend fun getAttendance(limit: Int): String {
        val site = ensureSite(LoginType.ATTENDANCE)
            ?: return loginHint(LoginType.ATTENDANCE)
        return try {
            val api = com.xjtu.toolbox.attendance.AttendanceApi(site)
            val termBh = runCatching { api.getTermBh() }.getOrNull()
            val termStartDate = termBh?.let {
                runCatching { api.getTermList().firstOrNull { t -> t.bh == it }?.startDate }.getOrNull()
            }
            val records = api.getWaterRecords(
                termBh = termBh,
                startDate = termStartDate ?: ""
            ).take(limit.coerceIn(1, 200))
            if (records.isEmpty()) return ToolReply.empty("attendance")
            pendingWidgets.add(AttendanceWidget(records))
            val text = buildString {
                // 统计先算好放第一行，「我这学期迟到几次」不用模型自己数
                val counts = records.groupingBy { it.status.displayName }.eachCount()
                append("最近 ${records.size} 条｜")
                append(counts.entries.sortedByDescending { it.value }.joinToString("，") { "${it.key} ${it.value}" })
                append("\n签到时间窗：每节开课前约 35–40 分钟到开课后 5 分钟，之后算迟到\n")
                records.forEach { r ->
                    append("${r.courseName}｜${r.date} ${r.startTime}–${r.endTime}节｜${r.status.displayName}")
                    if (r.location.isNotBlank()) append("｜${r.location}")
                    append("\n")
                }
            }
            dataCache.put("agent_attendance", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_attendance", ToolReply.failed("get_attendance", e.message))
        }
    }

    private suspend fun getGrades(term: String?): String {
        val site = ensureSite(LoginType.JWXT)
            ?: return loginHint(LoginType.JWXT)
        val studentId = loginState.activeUsername
        if (studentId.isBlank()) return ToolReply.failed("lookup", "no_student_id")
        return try {
            val all = ScoreReportApi(site).getReportedGrade(studentId)
            val grades = if (term != null) all.filter { it.term == term } else all
            if (grades.isEmpty()) return ToolReply.empty("grades; term: ${term ?: "all"}")

            // 加权平均绩点：按学分加权，仅统计有绩点的课程。
            val contributions = grades.map { g ->
                com.xjtu.toolbox.score.ScoreCalculator.calculateOneCourseContribution(
                    rawScore = g.score,
                    credit = g.coursePoint,
                    reportedGpa = g.gpa,
                )
            }
            val (weightedGpaSum, totalPoints) = com.xjtu.toolbox.score.ScoreCalculator.accumulate(contributions.asSequence())
            val gpa = if (totalPoints > 0) weightedGpaSum / totalPoints else null
            // 加权均分只算数值成绩；等级制、通过/不通过算不进去，数出来告诉模型，免得它以为漏了课
            val numeric = grades.mapNotNull { g -> g.score.trim().toDoubleOrNull()?.let { it to g.coursePoint } }
                .filter { it.second > 0 }
            val numericCredit = numeric.sumOf { it.second }
            val weightedAvg = if (numericCredit > 0) numeric.sumOf { it.first * it.second } / numericCredit else null
            val notInGpa = contributions.count { it.credit <= 0.0 }

            pendingWidgets.add(GradeWidget(grades, gpa, totalPoints))
            val text = buildString {
                // 统计先算好放第一行（仿问舟的做法），模型不用自己心算
                append("${grades.size} 门｜总学分 %.1f".format(grades.sumOf { it.coursePoint }))
                gpa?.let { append("｜加权 GPA %.3f".format(it)) }
                weightedAvg?.let { append("｜加权均分 %.2f（仅数值成绩）".format(it)) }
                if (notInGpa > 0) append("｜不计入 GPA $notInGpa 门")
                append("\n")
                // 不截断：用户可能要求排除某些课程重算 GPA，需要完整成绩列表
                grades.forEach { g ->
                    append("${g.courseName}｜${g.score}｜${g.coursePoint} 学分")
                    g.gpa?.let { append("｜绩点 %.2f".format(it)) }
                    if (term == null && g.term.isNotBlank()) append("｜${g.term}")
                    append("\n")
                }
            }
            dataCache.put("agent_grades_${term ?: "all"}", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_grades_${term ?: "all"}", ToolReply.failed("get_grades", e.message))
        }
    }

    private suspend fun getCardInfo(days: Int?): String {
        val site = ensureSite(LoginType.CAMPUS_CARD)
            ?: return loginHint(LoginType.CAMPUS_CARD)
        return try {
            val info = CampusCardApi(site).getCardInfo()
            pendingWidgets.add(CardWidget(info))
            val text = buildString {
                append("校园卡余额：¥%.2f".format(info.balance))
                if (info.pendingAmount > 0) append("，待入账¥%.2f".format(info.pendingAmount))
                if (info.lostFlag) append("（已挂失）")
                if (info.frozenFlag) append("（已冻结）")
                if (days != null) {
                    val d = days.coerceIn(1, 180)   // 放宽：用户可能要看整月/整学期账单
                    runCatching {
                        CampusCardApi(site).getAllTransactions(
                            startDate = LocalDate.now().minusDays(d.toLong()),
                            endDate = LocalDate.now(),
                            maxPages = 80,
                            allowIncomplete = false,
                        )
                    }.onSuccess { txs ->
                        if (txs.isEmpty()) {
                            append("\n最近${d}天无消费记录。")
                        } else {
                            // 全量给模型：它可能要按整月统计、分类汇总、找最大笔等，需要完整流水
                            val spend = txs.filter { it.amount < 0 }.sumOf { -it.amount }
                            val income = txs.filter { it.amount > 0 }.sumOf { it.amount }
                            append("\n最近${d}天流水（共${txs.size}笔，支出¥${"%.2f".format(spend)}，充值/收入¥${"%.2f".format(income)}）：\n")
                            txs.forEach { t ->
                                append("${t.time}｜${t.merchant}｜${"%+.2f".format(t.amount)}｜余额 ${"%.2f".format(t.balance)}\n")
                            }
                        }
                    }.onFailure {
                        append("\n" + ToolReply.failed("transactions_${d}d", it.message))
                    }
                }
            }.trimEnd()
            dataCache.put("agent_card", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_card", ToolReply.failed("get_card_info", e.message))
        }
    }

    /**
     * 没点名来源时，按身份挑这个人真正会看的几个站，而不是固定一组：
     * - 本科生：教务处、学生处、实践教学中心，加所在书院；
     * - 研究生：研究生院（教务处、学生处、书院的通知基本与研究生无关）；
     * - 都加 OA 通知和所在学院。
     * 学院 / 书院从本地缓存里取（校园卡、一网通办、学籍档案），不为此联网；取不到就只用校级几个。
     */
    private fun identityNoticeSources(): List<com.xjtu.toolbox.notification.NotificationSource> {
        val src = com.xjtu.toolbox.notification.NotificationSource
        val postgrad = runCatching {
            com.xjtu.toolbox.data.CredentialStore(context).accountType == AccountType.POSTGRADUATE
        }.getOrDefault(false)
        val profile = runCatching { com.xjtu.toolbox.hello.HelloProfileStore.cached(context) }.getOrNull()
        val college = listOfNotNull(
            profile?.departmentName,
            runCatching { com.xjtu.toolbox.card.CampusCardCache.load(context)?.cardInfo?.department }.getOrNull(),
            dataCache.read<YwtbIdentity>(YWTB_IDENTITY_KEY, com.xjtu.toolbox.data.DataCache.TERM_TTL_MS)?.college,
        ).firstNotNullOfOrNull { src.forOrg(it) }
        val academy = if (postgrad) null else src.forOrg(profile?.academyName)
        val gs = com.xjtu.toolbox.notification.NotificationSource.GS
        val oa = com.xjtu.toolbox.notification.NotificationSource.OA
        val base = if (postgrad) listOf(gs, oa) else listOf(
            com.xjtu.toolbox.notification.NotificationSource.JWC,
            com.xjtu.toolbox.notification.NotificationSource.XSC,
            com.xjtu.toolbox.notification.NotificationSource.PEC,
            oa,
        )
        return (base + listOfNotNull(college, academy)).distinct()
    }

    private suspend fun getNotifications(source: String?, limit: Int, keyword: String? = null): String {
        return try {
            val all = com.xjtu.toolbox.notification.NotificationSource.entries
            val sources = if (source.isNullOrBlank()) {
                // 按身份挑（见 identityNoticeSources），不并发爬几十个学院官网，又慢又常失败
                identityNoticeSources()
            } else {
                all.filter { it.displayName.contains(source) || source.contains(it.displayName) }
                    .ifEmpty {
                        return ToolReply.notFound("source", source, all.take(12).map { it.displayName })
                    }
            }
            // 告诉模型查了哪几个站：没查到时它能说清范围，用户想看别的站也知道该怎么问
            val scope = sources.joinToString("、") { it.displayName } +
                if (source.isNullOrBlank()) "（按你的身份自动选）" else ""
            val api = com.xjtu.toolbox.notification.NotificationApi()
            if (keyword != null) {
                // 站内搜索：查的是各站全站索引，不是本地已抓的那几页（见 NotificationApi.search）
                val found = api.search(sources, keyword)
                val hits = found.items.take(limit.coerceIn(1, 20))
                if (hits.isEmpty()) return ToolReply.empty("notifications: $keyword（已查：$scope）")
                return buildString {
                    append("「$keyword」站内搜索结果（${hits.size}条，按日期从新到旧")
                    if (found.skipped.isNotEmpty()) append("；${found.skipped.joinToString("、") { it.displayName }}这次没搜成")
                    append("）\n已查：$scope\n")
                    hits.forEach { n ->
                        append("${n.date}｜${n.source.displayName}｜${n.title}｜${n.link}\n")
                    }
                    append("\n" + ToolReply.EXTERNAL_DATA)
                }
            }
            val list = api
                .getMergedNotifications(sources, 1)
                .sortedByDescending { it.date }
                .take(limit.coerceIn(1, 20))
            if (list.isEmpty()) return ToolReply.empty("notifications（已查：$scope）")
            val text = buildString {
                append("校内最新通知（${list.size}条）\n已查：$scope\n")
                list.forEach { n ->
                    append("${n.date}｜${n.source.displayName}｜${n.title}｜${n.link}\n")
                }
                append("\n" + ToolReply.EXTERNAL_DATA)
            }
            dataCache.put("agent_notifications", text)
            text
        } catch (e: Exception) {
            staleOr("agent_notifications", ToolReply.failed("get_notifications", e.message))
        }
    }

    // ── 联网搜索/网页阅读（无需登录） ─────────────────────────────────────

    private val webCookies = object : okhttp3.CookieJar {
        private val lock = Any()
        private val jar = mutableListOf<okhttp3.Cookie>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            synchronized(lock) {
                cookies.forEach { incoming ->
                    jar.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                    jar.add(incoming)
                }
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                jar.removeAll { it.expiresAt < now }
                return jar.filter { it.matches(url) }
            }
        }
    }

    private val webClient by lazy {
        AgentWeb.applyPublicNetworkPolicy(HttpClients.base.newBuilder())
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(webCookies)
            .addInterceptor { chain ->
                val request = chain.request()
                AgentWeb.requirePublicHttpUrl(request.url.toString())
                val host = request.url.host.lowercase()
                val b = request.newBuilder()
                if (request.header("User-Agent").isNullOrBlank()) b.header("User-Agent", searchUa)
                if (request.header("Accept-Language").isNullOrBlank()) {
                    b.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                }
                if (request.header("Referer").isNullOrBlank()) {
                    when {
                        host.contains("sogou.com") -> b.header("Referer", "https://weixin.sogou.com/")
                        host.contains("mp.weixin.qq.com") -> b.header("Referer", "https://weixin.sogou.com/")
                        host.contains("so.com") -> b.header("Referer", "https://www.so.com/")
                        // 百度首页是「地址栏直接打开」，不能带 Referer（和 Sec-Fetch-Site: none 自相矛盾）
                        host.contains("baidu.com") && request.url.encodedPath != "/" ->
                            b.header("Referer", "https://www.baidu.com/")
                    }
                }
                val response = chain.proceed(b.build())
                AgentWeb.requirePublicHttpUrl(response.request.url.toString())
                response
            }
            .build()
    }
    private val webUa =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val wechatUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50.2701(0x2800323B) NetType/WIFI Language/zh_CN"

    private val searchUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 搜索要快失败。callTimeout 是整次调用（含跳转、读完正文）的硬上限，OkHttp 到点直接取消。
     * 以前只靠外面包一层 withTimeoutOrNull，而里面是阻塞的 execute()，协程超时根本打断不了它，
     * 各个源的连接 + 读取超时会一路叠加。
     */
    private val searchClient by lazy {
        webClient.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * 百度专用的内存 cookie，每次搜索前清空、重新从首页拿一份。
     *
     * 2026-09 实测（校园网，连发中文查询）：
     * - 只带 UA/Referer 的精简请求头：几乎每次都跳 wappass 验证码；
     * - 补齐浏览器导航请求头（Accept、Sec-Fetch-*、sec-ch-ua）：多数能过，但同一份 cookie
     *   连用第二次起常被拦；
     * - 每次搜索前换一份新 cookie：8/8 一次通过，只多一次首页请求（约 0.3 秒）。
     * 所以不再「一个会话热身一次」，而是每次都新拿；万一还是被拦，换 cookie 再试一次。
     */
    private val baiduJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Cookie>()
        fun clear() = store.clear()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            for (c in cookies) {
                store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                store.add(c)
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val now = System.currentTimeMillis()
            return store.filter { it.expiresAt > now && it.matches(url) }
        }
    }
    private val baiduClient by lazy { searchClient.newBuilder().cookieJar(baiduJar).build() }
    /** 换 cookie 这件事不能并发：两次搜索同时清空、同时热身会互相踩掉。 */
    private val baiduMutex = kotlinx.coroutines.sync.Mutex()

    /** 浏览器从地址栏 / 页内跳转打开网页时带的那一套请求头，缺了百度就当成脚本。 */
    private val baiduNavHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
        "sec-ch-ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-User" to "?1",
    )

    private fun normalizeSearchLink(href: String, baseUrl: String): String {
        val raw = href.trim()
        if (raw.isBlank() || raw.startsWith("javascript:", ignoreCase = true)) return ""
        return runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                else -> java.net.URL(java.net.URL(baseUrl), raw).toString()
            }
        }.getOrDefault(raw)
    }

    /**
     * 搜狗网页搜索与微信搜索共用。
     * 微信结果必须选到 `li`，不能选外层 `.news-box`（全页只有一个）。
     */
    private fun parseSogouResults(body: String, limit: Int, baseUrl: String): List<Triple<String, String, String>> {
        val doc = org.jsoup.Jsoup.parse(body, baseUrl)
        return doc.select(
            ".results .vrwrap, .results .rb, .vrwrap, .rb, .wx-rb, " +
                "ul.news-list > li, li[id^=sogou_vr_]"
        ).asSequence()
            .mapNotNull { el ->
                // 必须**逐个**选择器按优先级试，不能写成逗号列表：
                // Jsoup 的 selectFirst("a, b") 返回的是**文档顺序**里第一个命中的元素，
                // 与选择器的书写顺序无关。而微信结果 li 的结构是
                // `.img-box > a`（缩略图，在前）→ `.txt-box > h3 > a`（标题，在后），
                // 用逗号列表必然选中缩略图那个 a —— 标题空、链接指向图片。
                val a = TITLE_SELECTORS.firstNotNullOfOrNull { el.selectFirst(it) }
                    ?: return@mapNotNull null
                val title = a.text().ifBlank { return@mapNotNull null }
                val link = normalizeSearchLink(a.attr("href"), baseUrl)
                val snippet = el.selectFirst(".txt-info, .str_info, .ft, .text-layout, .s-p")?.text().orEmpty()
                Triple(title, link, snippet)
            }
            .filter { (_, link, snippet) -> isUsefulSogouResult(link, snippet) }
            .distinctBy { (_, link, _) -> link }
            .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
            .toList()
    }

    /** 标题链接候选，按优先级排列，逐个尝试（原因见调用处注释）。 */
    private val TITLE_SELECTORS = listOf(".txt-box h3 a", "h3 a", ".vrTitle a", "a[target=_blank]")

    private companion object {
        const val MAX_SEARCH_RESULTS = 22
        /** 搜狗/微信翻页极易撞验证码，自动链路只取首页。 */
        const val MAX_SEARCH_PAGES = 1
        /** 自动档百度返回后，360 最多再等多久才合并；实测 360 通常比百度早到，这点余量足够。 */
        const val AUTO_GRACE_MS = 400L
    }

    /**
     * 剔除搜狗结果页里的非自然结果。实测一次「西安交通大学」，16 个命中元素里只有 7 条是真结果，
     * 其余是广告、搜狗自家垂直卡片和站内相关搜索——不过滤的话模型拿到的就是一堆噪声。
     *
     * 判据：
     * - `www.sogou.com/link?url=…` 是自然结果的跳转链接，一律保留（web_fetch 会自动跟随重定向）；
     * - 其余 sogou.com 域名都是站内货（pic.sogou 图片、m.sogou 视频、www.sogou.com/sogou 相关搜索），丢弃；
     * - 带 `fromcoop=` / `channel=sgtp` 的是推广位，丢弃；
     * - 剩下的外链要求**必须有摘要**——腾讯地图之类的垂直卡片没有摘要，自然结果都有。
     */
    private fun isUsefulSogouResult(link: String, snippet: String): Boolean {
        if (!link.startsWith("http")) return false
        val lower = link.lowercase()
        if (lower.contains("fromcoop=") || lower.contains("channel=sgtp")) return false
        val isNaturalRedirect = lower.contains("sogou.com/link?url=")
        if (isNaturalRedirect) return true
        if (Regex("""https?://[^/]*\bsogou\.com""").containsMatchIn(lower)) return false
        return snippet.isNotBlank()
    }

    private suspend fun webSearch(query: String, limit: Int, engine: String?): String {
        if (query.isBlank()) return ToolReply.missing("query")
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val requested = engine?.trim()?.lowercase()
            val selectedEngine = when {
                // 模型可能沿用旧提示词点名已下线的源，一律当成没指定。
                requested in AgentConfig.RETIRED_SEARCH_ENGINES -> AgentConfig.SEARCH_AUTO
                else -> when (requested) {
                    AgentConfig.SEARCH_WECHAT, "weixin", "wx" -> AgentConfig.SEARCH_WECHAT
                    AgentConfig.SEARCH_BAIDU, "百度" -> AgentConfig.SEARCH_BAIDU
                    AgentConfig.SEARCH_SO360, "360", "so" -> AgentConfig.SEARCH_SO360
                    AgentConfig.SEARCH_WIKI, "wikipedia", "wiki" -> AgentConfig.SEARCH_WIKI
                    AgentConfig.SEARCH_AUTO, "auto", null, "" ->
                        if (engine.isNullOrBlank()) defaultSearchEngine else AgentConfig.SEARCH_AUTO
                    else -> defaultSearchEngine
                }
            }
            // 异步 + 可取消：协程一取消（自动档不再等某一家时）就 call.cancel() 立刻断开，
            // 不会像阻塞的 execute() 那样非要跑到超时，把整个 coroutineScope 一起拖住。
            suspend fun fetch(
                url: String,
                extra: Map<String, String> = emptyMap(),
                client: okhttp3.OkHttpClient = searchClient,
            ): Pair<String, String>? =
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val req = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", searchUa)
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                    extra.forEach { (k, v) -> req.header(k, v) }
                    val call = try {
                        client.newCall(req.get().build())
                    } catch (_: Exception) {
                        cont.resume(null) {}
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                            cont.resume(null) {}
                        }
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            val result = runCatching {
                                response.use { resp ->
                                    if (!resp.isSuccessful) null
                                    else resp.body?.string()?.let { it to resp.request.url.toString() }
                                }
                            }.getOrNull()
                            cont.resume(result) {}
                        }
                    })
                }

            val want = limit.coerceIn(1, MAX_SEARCH_RESULTS)

            fun parseOrEmpty(which: String, body: String, finalUrl: String): List<Triple<String, String, String>> {
                // 百度正常结果页很大、脚本里什么词都有，只认它自己的拦截特征，不走通用关键词
                if (which == AgentConfig.SEARCH_BAIDU) {
                    return if (AgentWeb.looksLikeBaiduBlock(body, finalUrl)) emptyList()
                    else AgentWeb.parseBaiduHtml(body, want)
                }
                if (AgentWeb.looksLikeCaptcha(body)) return emptyList()
                return when (which) {
                    AgentConfig.SEARCH_WECHAT -> parseSogouResults(body, want, finalUrl)
                    else -> AgentWeb.parseSo360Html(body, want)
                }
            }

            suspend fun fetchPage(which: String, page: Int): List<Triple<String, String, String>> = when (which) {
                AgentConfig.SEARCH_WECHAT -> {
                    if (page == 1) fetch("https://weixin.sogou.com/")
                    val pc = fetch(
                        "https://weixin.sogou.com/weixin?type=2&ie=utf8&s_from=input&query=$encoded&page=$page",
                        mapOf("Referer" to "https://weixin.sogou.com/"),
                    )?.let { (body, finalUrl) -> parseOrEmpty(which, body, finalUrl) }.orEmpty()
                    if (pc.isNotEmpty() || page > 1) pc
                    else fetch(
                        "https://weixin.sogou.com/weixinwap?type=2&query=$encoded",
                        mapOf("Referer" to "https://weixin.sogou.com/"),
                    )?.let { (body, finalUrl) -> parseOrEmpty(which, body, finalUrl) }.orEmpty()
                }
                AgentConfig.SEARCH_BAIDU -> {
                    if (page > 1) emptyList()
                    else baiduMutex.withLock {
                        var rows = emptyList<Triple<String, String, String>>()
                        // 换一份新 cookie 再搜；被拦就再换一次（原因见 baiduJar 注释）
                        for (attempt in 1..2) {
                            baiduJar.clear()
                            fetch("https://www.baidu.com/", baiduNavHeaders + ("Sec-Fetch-Site" to "none"), baiduClient)
                            val got = fetch(
                                "https://www.baidu.com/s?ie=utf-8&tn=baidu&wd=$encoded&rn=${want.coerceIn(10, 20)}",
                                baiduNavHeaders + ("Sec-Fetch-Site" to "same-origin"),
                                baiduClient,
                            ) ?: break
                            val (body, finalUrl) = got
                            if (AgentWeb.looksLikeBaiduBlock(body, finalUrl)) continue
                            rows = AgentWeb.parseBaiduHtml(body, want)
                            break
                        }
                        rows
                    }
                }
                AgentConfig.SEARCH_SO360 ->
                    if (page > 1) emptyList()
                    else fetch(
                        "https://www.so.com/s?q=$encoded",
                        mapOf("Referer" to "https://www.so.com/"),
                    )?.let { (body, finalUrl) -> parseOrEmpty(which, body, finalUrl) }.orEmpty()
                AgentConfig.SEARCH_WIKI ->
                    if (page > 1) emptyList()
                    else fetch(
                        "https://zh.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$want&namespace=0&format=json",
                        mapOf("Accept" to "application/json"),
                    )?.let { (body, _) -> AgentWeb.parseWikiOpenSearch(body, want) }.orEmpty()
                else -> emptyList()
            }

            suspend fun searchOnce(which: String): List<Triple<String, String, String>> {
                val acc = LinkedHashMap<String, Triple<String, String, String>>()
                val pages = if (which == AgentConfig.SEARCH_WECHAT) MAX_SEARCH_PAGES else 1
                for (page in 1..pages) {
                    val before = acc.size
                    fetchPage(which, page).forEach { r -> acc.putIfAbsent(r.second, r) }
                    if (acc.size >= want || acc.size == before) break
                }
                return acc.values.take(want)
            }

            // 自动：百度为主、360 陪跑。2026-09 校园网实测，六条中文查询：
            //   百度  1.0~1.6s  每条 8~10 个结果，大量直达 xjtu.edu.cn 校内页面，连发不弹验证码
            //   360   0.5~1.2s  每条 4~7 个结果，相关；此前因解析 bug 一直返回 0 条
            // 两家同时发出。百度一回来就出结果，360 只再多等 [AUTO_GRACE_MS]：赶上了合并补充，
            // 赶不上就取消（fetch 可取消，立刻断开），不让较慢的一家拖住整体。
            // 百度挂了（拦截 / 超时 / 空）才完整等 360。每个请求另有 callTimeout 6 秒兜底。
            suspend fun searchAuto(skip: String?): Pair<String, List<Triple<String, String, String>>> =
                kotlinx.coroutines.coroutineScope {
                    fun run(which: String) = async(Dispatchers.IO) {
                        if (which == skip) emptyList() else runCatching { searchOnce(which) }.getOrDefault(emptyList())
                    }
                    val baiduJob = run(AgentConfig.SEARCH_BAIDU)
                    val so360Job = run(AgentConfig.SEARCH_SO360)
                    val baidu = baiduJob.await()
                    val so360 = if (baidu.isEmpty()) so360Job.await()
                    else withTimeoutOrNull(AUTO_GRACE_MS) { so360Job.await() } ?: emptyList<Triple<String, String, String>>().also { so360Job.cancel() }

                    // 合并去重：同一地址或同一标题只留一条，百度在前
                    val merged = LinkedHashMap<String, Triple<String, String, String>>()
                    val titles = HashSet<String>()
                    for (r in baidu + so360) {
                        val key = r.second.substringAfter("://").trimEnd('/').lowercase()
                        if (key in merged || !titles.add(r.first)) continue
                        merged[key] = r
                    }
                    val label = listOfNotNull(
                        AgentConfig.searchEngineLabel(AgentConfig.SEARCH_BAIDU).takeIf { baidu.isNotEmpty() },
                        AgentConfig.searchEngineLabel(AgentConfig.SEARCH_SO360).takeIf { so360.isNotEmpty() },
                    ).joinToString(" + ")
                    label to merged.values.take(want)
                }

            var usedLabel = AgentConfig.searchEngineLabel(selectedEngine)
            var results = emptyList<Triple<String, String, String>>()
            val primary = selectedEngine.takeUnless { it == AgentConfig.SEARCH_AUTO }
            if (primary != null) {
                results = runCatching { searchOnce(primary) }.getOrDefault(emptyList())
            }
            if (results.isEmpty()) {
                val (label, rows) = searchAuto(skip = primary)
                if (rows.isNotEmpty()) {
                    usedLabel = label
                    results = rows
                }
            }
            if (results.isEmpty()) return ToolReply.empty("query: $query")
            buildString {
                append("「$query」｜$usedLabel｜${results.size} 条\n")
                // 一条一行、带编号，模型转述时能写「据 [2]」，再用 web_fetch 读原文
                results.forEachIndexed { i, (t, l, s) ->
                    append("[${i + 1}] $t｜$l")
                    if (s.isNotBlank()) append("｜${s.take(240)}")
                    append("\n")
                }
                append(ToolReply.EXTERNAL_DATA)
            }
        } catch (e: Exception) {
            ToolReply.failed("web_search", e.message)
        }
    }

    private suspend fun getLibraryBooking(): String {
        val site = ensureSite(LoginType.LIBRARY)
            ?: return loginHint(LoginType.LIBRARY)
        return try {
            val b = com.xjtu.toolbox.library.LibraryApi(site).getMyBooking()
                ?: return ToolReply.empty("library_booking")
            buildString {
                append("当前图书馆预约：座位 ${b.seatId ?: "?"}")
                b.area?.let { append("，$it") }
                b.statusText?.let { append("，状态：$it") }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed("get_library.booking", e.message)
        }
    }

    // ── 仲英学辅资料站 ────────────────────────────────────────────────────
    //
    // 资料站是**公开站点**，不带任何校内凭据，所以这三个工具不走 ensureSite。
    // 返回里一律带上 ID，模型才接得下去：search → read，或 browse → browse → read。

    /** 一行条目的统一写法，目录和文件在同一份列表里要能一眼分清。 */
    private fun zyxfLine(e: com.xjtu.toolbox.zyxf.ZyxfApi.Entry): String = buildString {
        if (e.isFolder) {
            append("📁 ${e.name}（目录 ID ${e.id}）")
        } else {
            append("📄 ${e.name}（文件 ID ${e.id}")
            e.sizeText.takeIf { it.isNotBlank() }?.let { append("，$it") }
            if (!e.readable) append("，不可直接阅读")
            append("）")
        }
        e.path.takeIf { it.isNotBlank() }?.let { append("  ← $it") }
    }

    /** 把条目转成卡片。只带 UI 要用的字段，卡片得随会话一起存盘。 */
    private fun zyxfWidgetOf(
        query: String,
        entries: List<com.xjtu.toolbox.zyxf.ZyxfApi.Entry>,
    ) = ZyxfWidget(
        query = query,
        items = entries.take(20).map {
            ZyxfEntryRef(
                id = it.id,
                name = it.name,
                path = it.path,
                sizeText = it.sizeText,
                isFolder = it.isFolder,
            )
        },
    )

    private suspend fun searchZyxf(keyword: String): String = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext ToolReply.missing("keyword")
        try {
            val r = com.xjtu.toolbox.zyxf.ZyxfApi.search(keyword)
            if (r.entries.isEmpty()) {
                return@withContext ToolReply.empty("keyword: $keyword")
            }
            pendingWidgets.add(zyxfWidgetOf(keyword, r.entries))
            buildString {
                append("仲英学辅资料站「$keyword」检索结果（共 ${r.entries.size} 条")
                if (r.truncated) append("，服务端已截断")
                append("）：\n")
                r.entries.take(25).forEach { append("• ${zyxfLine(it)}\n") }
                append("\ntap_to_download: true")
            }.trimEnd()
        } catch (e: Exception) {
            ToolReply.failed("list_zyxf", e.message)
        }
    }

    private suspend fun browseZyxf(folderId: Int): String = withContext(Dispatchers.IO) {
        try {
            val entries = com.xjtu.toolbox.zyxf.ZyxfApi.listFolder(folderId.coerceAtLeast(0))
            val where = if (folderId <= 0) "根目录"
                else com.xjtu.toolbox.zyxf.ZyxfApi.breadcrumb(folderId).ifBlank { "目录 $folderId" }
            if (entries.isEmpty()) return@withContext "仲英学辅资料站 $where 是空的。"
            pendingWidgets.add(zyxfWidgetOf(where, entries))
            buildString {
                append("仲英学辅资料站 · $where（${entries.size} 项）：\n")
                entries.take(40).forEach { append("• ${zyxfLine(it)}\n") }
                if (entries.size > 40) append("…（还有 ${entries.size - 40} 项）\n")
                append("\ntap_to_download: true")
            }.trimEnd()
        } catch (e: Exception) {
            ToolReply.failed("list_zyxf", e.message)
        }
    }

    private suspend fun readZyxfFile(fileId: Int): String = withContext(Dispatchers.IO) {
        if (fileId <= 0) return@withContext ToolReply.missing("file_id")
        try {
            val link = com.xjtu.toolbox.zyxf.ZyxfApi.fileLink(fileId)
            val ext = link.ext.lowercase().removePrefix(".")
            val entry = com.xjtu.toolbox.zyxf.ZyxfApi.Entry(
                id = fileId, name = link.name, isFolder = false,
                sizeBytes = link.sizeBytes, ext = ext,
            )
            // 二进制文档不在 App 里解析：PDF/Office 的文本抽取要拖进一整套解析库，
            // 而资料站服务端本来就有 WebOffice 预览。这里给链接，让用户去看。
            if (!entry.readable) {
                return@withContext buildString {
                    append("result: not_parsed; file: ${link.name}; type: ${ext.uppercase().ifBlank { "binary" }}")
                    entry.sizeText.takeIf { it.isNotBlank() }?.let { append("; size: $it") }
                    append("; url: ${link.url}; url_ttl: 30min")
                }
            }
            val text = com.xjtu.toolbox.zyxf.ZyxfApi.readText(entry)
                ?: return@withContext ToolReply.failed("read_zyxf_file", "empty_body; file: ${link.name}")
            buildString {
                append("「${link.name}」内容：\n")
                append(text.take(4000))
                if (text.length > 4000) append("\n…（已截断，只给了开头 4000 字）")
            }
        } catch (e: Exception) {
            ToolReply.failed("read_zyxf_file", e.message)
        }
    }

    private suspend fun getLibrarySeats(campusArg: String?, area: String?): String {
        val site = ensureSite(LoginType.LIBRARY)
            ?: return loginHint(LoginType.LIBRARY)
        val api = com.xjtu.toolbox.library.LibraryApi(site)
        val current = withContext(Dispatchers.IO) { runCatching { api.getCurrentCampus() }.getOrNull() }
        val requested = campusArg?.trim()?.takeIf { it.isNotEmpty() }?.let { arg ->
            com.xjtu.toolbox.library.LibraryCampus.entries.firstOrNull { arg.contains(it.displayName) || it.displayName.contains(arg) }
                ?: return ToolReply.notFound("campus", arg, com.xjtu.toolbox.library.LibraryCampus.entries.map { it.displayName })
        }
        val campus = requested ?: current ?: com.xjtu.toolbox.library.LibraryCampus.DEFAULT

        // 区域和空座都跟着账号在图书馆系统里的校区（rplace）走：查别的校区得先切过去，查完切回来，
        // 和图书馆页「看一眼别的校区」一个规矩——不能因为屁岱查了一下就把用户的账号留在那边。
        val switched = current != null && campus != current &&
            withContext(Dispatchers.IO) { runCatching { api.switchCampus(campus) }.getOrDefault(false) }
        if (current != null && campus != current && !switched) {
            return ToolReply.failed("get_library.campus", "切到${campus.displayName}查询失败")
        }
        try {
            return withContext(Dispatchers.IO) { librarySeatsIn(api, campus, area) }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            return ToolReply.failed("get_library.seats", e.message)
        } finally {
            if (switched && current != null) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { api.switchCampus(current) } }
            }
        }
    }

    private suspend fun librarySeatsIn(
        api: com.xjtu.toolbox.library.LibraryApi,
        campus: com.xjtu.toolbox.library.LibraryCampus,
        area: String?,
    ): String {
        // 区域按校区各不相同，不能拿兴庆那张写死的表当全集（issue #42）。逐层现拉，
        // 顺带收集每层 scount 里的空座统计，列区域时一并给出，方便直接推荐。
        val discovered = linkedMapOf<String, String>()   // 中文名 → 区域码
        val stats = mutableMapOf<String, com.xjtu.toolbox.library.AreaStats>()
        campus.floorCodes.forEach { floorCode ->
            runCatching { api.getFloorAreas(floorCode) }.getOrNull()?.let { areas ->
                areas.forEach { (code, name) -> discovered[name] = code }
                stats += api.cachedAreaStats.filterKeys { it in areas }
            }
        }
        val areaMap = discovered.ifEmpty { com.xjtu.toolbox.library.LibraryApi.AREA_MAP }
        val where = "${campus.displayName}校区"
        if (area.isNullOrBlank()) {
            val lines = areaMap.entries.map { (name, code) ->
                val s = stats[code]
                when {
                    s == null -> "- $name"
                    !s.isOpen -> "- $name：未开放"
                    else -> "- $name：空闲 ${s.available} / ${s.total}"
                }
            }
            return "$where 图书馆各区域（按楼层）：\n" + lines.joinToString("\n")
        }
        val entry = areaMap.entries.firstOrNull {
            it.key == area || it.key.contains(area) || area.contains(it.key)
        } ?: return ToolReply.notFound("area", area, areaMap.keys)
        return when (val r = api.getSeats(entry.value)) {
            is com.xjtu.toolbox.library.SeatResult.Success -> {
                val free = r.seats.filter { it.available }
                // 平面图卡片：坐标这一枪失败不影响文字答复
                runCatching {
                    val layout = api.getSeatLayout(entry.value)
                    val imageName = com.xjtu.toolbox.library.LibraryPages.planImageNames(entry.value).getValue(null)
                    val cached = com.xjtu.toolbox.library.PlanImageDiskCache.get(context, imageName) { api.getPlanImage(it) }
                    if (layout.seats.isNotEmpty() && cached != null) {
                        pendingWidgets.add(
                            LibraryWidget(
                                campusId = campus.id,
                                campusName = campus.displayName,
                                areaCode = entry.value,
                                areaName = entry.key,
                                imageName = imageName,
                                seats = layout.seats,
                            )
                        )
                    }
                }
                buildString {
                    append("$where ${entry.key}：空闲 ${free.size} / 共 ${r.seats.size} 座")
                    if (free.isNotEmpty()) append("\n空闲座位（节选）：" + free.take(20).joinToString("、") { it.seatId })
                }
            }
            is com.xjtu.toolbox.library.SeatResult.AuthError -> "登录失效：图书馆"
            is com.xjtu.toolbox.library.SeatResult.Error -> ToolReply.failed("get_library.seats", r.message)
        }
    }

    private suspend fun getTextbooks(course: String?, term: String?): String {
        val requestedTerm = term?.takeIf { it.isNotBlank() } ?: cachedTermCode()
        val cacheTermForFallback = requestedTerm.orEmpty()
        fun formatTextbooks(termCode: String, items: List<com.xjtu.toolbox.schedule.TextbookItem>, cached: Boolean): String {
            val key = course?.trim().orEmpty()
            val filtered = if (key.isBlank()) items else items.filter {
                it.courseName.contains(key, ignoreCase = true) ||
                    it.textbookName.contains(key, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                return ToolReply.empty("textbooks; term: $termCode" + (if (key.isBlank()) "" else "; course: $key"))
            }
            return buildString {
                if (cached) append("source: cache\n")
                append("${termCode}教材信息")
                if (key.isNotBlank()) append("（筛选：$key）")
                append("，共${filtered.size}条：\n")
                filtered.sortedBy { if (it.hasSubstantiveTextbook) 0 else 1 }.take(30).forEach { item ->
                    append("• ${item.courseName.ifBlank { "未知课程" }}：")
                    if (!item.hasSubstantiveTextbook) {
                        append("无")
                    } else {
                        append("《${item.textbookName}》")
                        val meta = buildList {
                            if (item.author.isNotBlank()) add(item.author)
                            if (item.publisher.isNotBlank()) add(item.publisher)
                            if (item.edition.isNotBlank()) add(item.edition)
                            if (item.isbn.isNotBlank() && !item.isbn.startsWith("978000000000")) add("ISBN ${item.isbn}")
                            if (item.price.isNotBlank()) add("¥${item.price}")
                        }
                        if (meta.isNotEmpty()) append("（${meta.joinToString(" · ")}）")
                    }
                    append("\n")
                }
                if (filtered.size > 30) append("还有 ${filtered.size - 30} 条未展示，可指定 course 缩小范围。")
            }.trimEnd()
        }

        val site = ensureSite(LoginType.JWXT)
            ?: return loginHint(LoginType.JWXT)
        return try {
            val api = ScheduleApi(site)
            val termCode = requestedTerm ?: api.getCurrentTerm()
            runCatching { com.xjtu.toolbox.schedule.ScheduleTermStore.merge(dataCache, api.termNames()) }
            val studentId = loginState.activeUsername
            if (studentId.isBlank()) return ToolReply.failed("lookup", "no_student_id")
            val books = api.getTextbooks(studentId, termCode)
            ScheduleCache.writeTextbooks(dataCache, termCode, books)
            formatTextbooks(termCode, books, cached = false)
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            if (cacheTermForFallback.isNotBlank()) {
                ScheduleCache.readTextbooks(dataCache, cacheTermForFallback)
                    ?.let { return formatTextbooks(cacheTermForFallback, it, cached = true) }
            }
            ToolReply.failed("get_textbooks", e.message)
        }
    }

    private suspend fun getCoupons(status: String? = null): String {
        val site = ensureSite(LoginType.COUPON)
            ?: return loginHint(LoginType.COUPON)
        return try {
            val api = com.xjtu.toolbox.coupon.CouponApi(site)
            val filters = when (status?.lowercase()?.trim()) {
                "available", "可领取", "unclaimed" -> listOf(com.xjtu.toolbox.coupon.CouponFilter.AVAILABLE)
                "usable", "可用", "可使用" -> listOf(com.xjtu.toolbox.coupon.CouponFilter.USABLE)
                else -> listOf(
                    com.xjtu.toolbox.coupon.CouponFilter.AVAILABLE,
                    com.xjtu.toolbox.coupon.CouponFilter.USABLE
                )
            }
            val pages = filters.associateWith { filter -> api.queryCoupons(filter, page = 1, pageSize = 20) }
            if (pages.values.all { it.records.isEmpty() }) return ToolReply.empty("coupons")
            val text = buildString {
                pages[com.xjtu.toolbox.coupon.CouponFilter.AVAILABLE]?.records?.takeIf { it.isNotEmpty() }?.let { records ->
                    append("可领取加餐券（${records.size}张）：\n")
                    records.take(20).forEach { c ->
                        append("${c.voucherName}｜面额 ¥${"%.2f".format(c.amountFen / 100.0)}｜${c.startDate}~${c.endDate}\n")
                    }
                }
                pages[com.xjtu.toolbox.coupon.CouponFilter.USABLE]?.records?.takeIf { it.isNotEmpty() }?.let { records ->
                    if (isNotEmpty()) append("\n")
                    append("可使用加餐券（${records.size}张）：\n")
                    records.take(20).forEach { c ->
                        append("${c.voucherName}｜${c.typeName}｜余额 ¥${"%.2f".format(c.leftAmountFen / 100.0)}｜${c.startDate}~${c.endDate}\n")
                    }
                }
            }
            dataCache.put("agent_coupons", text)
            text
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            staleOr("agent_coupons", ToolReply.failed("get_coupons", e.message))
        }
    }

    private fun lmsTypeName(t: com.xjtu.toolbox.lms.LmsActivityType): String = when (t) {
        com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK -> "作业"
        com.xjtu.toolbox.lms.LmsActivityType.MATERIAL -> "课件"
        com.xjtu.toolbox.lms.LmsActivityType.LESSON -> "课程/回放"
        com.xjtu.toolbox.lms.LmsActivityType.LECTURE_LIVE -> "直播/回放"
        com.xjtu.toolbox.lms.LmsActivityType.PAGE -> "页面"
        com.xjtu.toolbox.lms.LmsActivityType.FORUM -> "讨论区"
        com.xjtu.toolbox.lms.LmsActivityType.QUESTIONNAIRE -> "问卷"
        com.xjtu.toolbox.lms.LmsActivityType.ONLINE_VIDEO -> "在线视频"
        else -> "其他"
    }

    private suspend fun getLmsCourses(): String {
        val site = ensureSite(LoginType.LMS)
            ?: return loginHint(LoginType.LMS)
        return try {
            val courses = com.xjtu.toolbox.lms.LmsApi(site).getMyCourses()
            if (courses.isEmpty()) return ToolReply.empty("lms_courses")
            "思源学堂课程（${courses.size}门）：\n" + courses.joinToString("\n") { "• ${it.name}" }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed("get_lms.courses", e.message)
        }
    }

    /**
     * 活动的时间尾巴：作业报"截止"（优先上游 `deadline`，它才是老师设的那个时间，实测 4.6%
     * 与 `end_time` 不同，且都是 `end_time` 比它早），其余类型报"结束"（下课/回放结束，不是截止）。
     */
    private fun lmsTimeSuffix(a: com.xjtu.toolbox.lms.LmsActivity): String {
        val isHw = a.type == com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK
        val raw = if (isHw) (a.deadline ?: a.endTime) else a.endTime
        if (raw.isNullOrBlank()) return ""
        return if (isHw) "（截止 $raw）" else "（结束 $raw）"
    }

    private suspend fun getLmsActivities(course: String?): String {
        if (course.isNullOrBlank()) return ToolReply.missing("course")
        val site = ensureSite(LoginType.LMS)
            ?: return loginHint(LoginType.LMS)
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
            val c = api.getMyCourses().firstOrNull {
                it.name == course || it.name.contains(course) || course.contains(it.name)
            } ?: return ToolReply.notFound("course", course)
            val acts = api.getCourseActivities(c.id)
            if (acts.isEmpty()) return ToolReply.empty("lms_activities; course: ${c.name}")
            buildString {
                append("「${c.name}」活动（${acts.size}项）：\n")
                acts.groupBy { it.type }.forEach { (t, list) ->
                    append("【${lmsTypeName(t)}】\n")
                    list.take(15).forEach { a ->
                        append("• ${a.title}")
                        append(lmsTimeSuffix(a))
                        append("\n")
                    }
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed("get_lms.activities", e.message)
        }
    }

    private suspend fun getLmsAssignments(): String {
        val site = ensureSite(LoginType.LMS)
            ?: return loginHint(LoginType.LMS)
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
            if (homeworks.isEmpty()) return ToolReply.empty("lms_assignments")
            val sorted = homeworks.sortedBy { it.second.deadline ?: it.second.endTime ?: "9999" }
            buildString {
                append("思源学堂作业（${sorted.size}项）：\n")
                sorted.take(25).forEach { (cn, a) ->
                    append("• [$cn] ${a.title}")
                    append(lmsTimeSuffix(a))
                    append("\n")
                }
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed("get_lms.assignments", e.message)
        }
    }

    private suspend fun findLmsCourse(
        api: com.xjtu.toolbox.lms.LmsApi,
        course: String?
    ): com.xjtu.toolbox.lms.LmsCourseSummary? {
        val key = course?.trim().orEmpty()
        if (key.isBlank()) return null
        return api.getMyCourses().firstOrNull {
            it.name == key || it.name.contains(key) || key.contains(it.name)
        }
    }

    private suspend fun findLmsActivity(
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
            ?: return loginHint(LoginType.LMS)
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
            val c = findLmsCourse(api, course) ?: return ToolReply.notFound("course", course.orEmpty())
            val brief = findLmsActivity(api, c.id, activity)
                ?: return ToolReply.notFound("activity", "${c.name} / ${activity.orEmpty()}")
            val a = api.getActivityDetail(brief.id, brief)
            buildString {
                append("「${c.name}」${lmsTypeName(a.type)}详情：${a.title}\n")
                a.startTime?.let { append("开始：$it\n") }
                a.visibleStartAt?.let { append("可见：$it\n") }
                if (a.type == com.xjtu.toolbox.lms.LmsActivityType.HOMEWORK) {
                    (a.deadline ?: a.endTime)?.let { append("截止：$it\n") }
                } else {
                    a.endTime?.let { append("结束：$it\n") }
                }
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
            ToolReply.failed("get_lms_activity", e.message)
        }
    }

    private suspend fun readLmsAttachment(course: String?, activity: String?, file: String?): String {
        val site = ensureSite(LoginType.LMS)
            ?: return loginHint(LoginType.LMS)
        return try {
            val api = com.xjtu.toolbox.lms.LmsApi(site)
            val c = findLmsCourse(api, course) ?: return ToolReply.notFound("course", course.orEmpty())
            val brief = findLmsActivity(api, c.id, activity)
                ?: return ToolReply.notFound("activity", "${c.name} / ${activity.orEmpty()}")
            val detail = api.getActivityDetail(brief.id, brief)
            val uploads = detail.uploads + detail.submissionList?.list.orEmpty().flatMap { it.uploads }
            val key = file?.trim().orEmpty()
            val upload = uploads.firstOrNull { key.isBlank() || it.name.contains(key, ignoreCase = true) }
                ?: return ToolReply.notFound("file", key)
            val url = upload.downloadUrl.ifBlank { upload.attachmentUrl.ifBlank { upload.previewUrl } }
            if (url.isBlank()) return ToolReply.failed("get_lms_activity.file","no_download_url; file: ${upload.name}")
            val ext = upload.name.substringAfterLast('.', "").lowercase()
            if (ext in listOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "zip", "rar", "7z")) {
                return "result: not_parsed; file: ${upload.name}; type: ${ext.uppercase()}; url: $url"
            }
            val bytes = api.downloadBytes(url) ?: return ToolReply.failed("get_lms_activity.file","download")
            val text = bytes.toString(Charsets.UTF_8)
            "附件「${upload.name}」内容：\n" + text.take(4000) + if (text.length > 4000) "\n…（已截断）" else ""
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            ToolReply.failed("get_lms_activity.file",e.message)
        }
    }

    private suspend fun getFitnessScore(year: String?): String {
        val site = ensureSite(LoginType.FITNESS)
            ?: return loginHint(LoginType.FITNESS)
        return try {
            val api = com.xjtu.toolbox.fitness.FitnessApi(site)
            val years = api.getYears()
            val selected = pickFitnessYear(years, year)
            if (selected == null) {
                val opts = orderedFitnessYears(years)
                    .mapNotNull { it.yearValue() }
                    .distinct()
                return ToolReply.notFound("year", year.orEmpty(), opts.map { it.toString() })
            }
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
            ToolReply.failed("get_fitness_score", e.message)
        }
    }

    // ── 应用设置读写（仅非敏感项白名单） ──────────────────────────────────

    private val writableSettingKeys = listOf(
        "dark_mode", "dynamic_color", "home_theme", "nav_bar_style", "show_quick_actions",
        "default_tab", "network_mode", "account_type", "venue_auto_solve_captcha",
        "update_channel", "receive_preview_updates",
    )

    private fun parseBoolSetting(value: String): Boolean? {
        return when (value.trim().lowercase()) {
            "true", "1", "on", "yes", "开", "开启" -> true
            "false", "0", "off", "no", "关", "关闭" -> false
            else -> null
        }
    }

    private fun getAppSettings(): String {
        val cs = com.xjtu.toolbox.data.CredentialStore(context)
        return buildString {
            append("当前应用设置：\n")
            append("• dark_mode（深色模式）：${cs.darkMode}　可选 system/light/dark\n")
            append("• dynamic_color（跟随系统取色）：${cs.dynamicColor}　可选 true/false\n")
            append("• home_theme（首页主题）：${cs.homeTheme}　可选 card/icon\n")
            append("• nav_bar_style（界面风格）：${cs.navBarStyle}　可选 floating（玻璃，默认）/classic（经典，不透明、更省电）\n")
            append("• show_quick_actions（首页常用功能）：${cs.showQuickActions}　可选 true/false\n")
            append("• default_tab（启动页）：${cs.defaultTab}　可选 HOME/COURSES/TOOLS/PROFILE\n")
            append("• network_mode（网络模式）：${cs.networkMode}　可选 auto/direct/vpn\n")
            append("• account_type（账号类型）：${cs.accountType.key}　可选 undergraduate/postgraduate\n")
            append("• venue_auto_solve_captcha（场馆验证码自动识别）：${cs.venueAutoSolveCaptchaEnabled}　可选 true/false\n")
            append("• update_channel（更新通道）：${cs.updateChannel}（${com.xjtu.toolbox.update.AppUpdater.channelLabel(cs.updateChannel)}）　可选 ${com.xjtu.toolbox.update.AppUpdater.channelKeys.joinToString("/")}\n")
            append("• receive_preview_updates（接收预览版更新）：${cs.receivePreviewUpdates}　可选 true/false")
        }
    }

    private fun setAppSetting(key: String, value: String): String {
        val cs = com.xjtu.toolbox.data.CredentialStore(context)
        return when (key.trim()) {
            "dark_mode" -> {
                val v = value.trim().lowercase()
                if (v !in listOf("system", "light", "dark")) ToolReply.badValue("dark_mode", "system/light/dark")
                else {
                    cs.darkMode = v
                    "已将深色模式设为 $v（已即时生效）。"
                }
            }
            "dynamic_color" -> {
                val b = parseBoolSetting(value) ?: return ToolReply.badValue("dynamic_color", "true/false")
                cs.dynamicColor = b
                "已${if (b) "开启" else "关闭"}跟随系统取色（已即时生效）。"
            }
            "home_theme" -> {
                val v = when (value.trim().lowercase()) {
                    "card", "卡片", "卡片主题" -> CredentialStore.THEME_CARD
                    "icon", "图标", "图标主题" -> CredentialStore.THEME_ICON
                    else -> return ToolReply.badValue("home_theme", "card/icon")
                }
                cs.homeTheme = v
                "已将首页主题设为 $v（已即时生效）。"
            }
            "nav_bar_style" -> {
                val v = when (value.trim().lowercase()) {
                    "floating", "悬浮", "悬浮胶囊", "玻璃", "液态玻璃" -> CredentialStore.NAV_STYLE_FLOATING
                    "classic", "经典", "经典底栏", "不透明" -> CredentialStore.NAV_STYLE_CLASSIC
                    else -> return ToolReply.badValue("nav_bar_style", "floating/classic")
                }
                cs.navBarStyle = v
                "已将界面风格设为 $v（已即时生效）。"
            }
            "show_quick_actions" -> {
                val b = parseBoolSetting(value) ?: return ToolReply.badValue("show_quick_actions", "true/false")
                cs.showQuickActions = b
                "已${if (b) "显示" else "隐藏"}首页常用功能（已即时生效）。"
            }
            "default_tab" -> {
                val v = value.trim().uppercase()
                if (v !in listOf("HOME", "COURSES", "TOOLS", "PROFILE")) ToolReply.badValue("default_tab", "HOME/COURSES/TOOLS/PROFILE")
                else { cs.defaultTab = v; "已将启动页设为 $v（下次冷启动生效）。" }
            }
            "network_mode" -> {
                val v = when (value.trim().lowercase()) {
                    "auto", "自动", "自动检测" -> CredentialStore.NETWORK_AUTO
                    "direct", "直连", "强制直连" -> CredentialStore.NETWORK_DIRECT
                    "vpn", "webvpn", "强制 webvpn" -> CredentialStore.NETWORK_VPN
                    else -> return ToolReply.badValue("network_mode", "auto/direct/vpn")
                }
                cs.networkMode = v
                "已将网络模式设为 $v。"
            }
            "account_type" -> {
                val type = when (value.trim().lowercase()) {
                    "undergraduate", "本科", "本科生" -> AccountType.UNDERGRADUATE
                    "postgraduate", "研究生" -> AccountType.POSTGRADUATE
                    else -> return ToolReply.badValue("account_type", "undergraduate/postgraduate")
                }
                cs.accountType = type
                "已将账号类型设为 ${type.displayName}。"
            }
            "venue_auto_solve_captcha" -> {
                val b = parseBoolSetting(value) ?: return ToolReply.badValue("venue_auto_solve_captcha", "true/false")
                cs.venueAutoSolveCaptchaEnabled = b
                "已${if (b) "开启" else "关闭"}场馆验证码自动识别。"
            }
            "update_channel" -> {
                val raw = value.trim().lowercase()
                val accepted = com.xjtu.toolbox.update.AppUpdater.channelKeys + listOf("stable", "beta")
                if (raw !in accepted) {
                    ToolReply.badValue("update_channel", com.xjtu.toolbox.update.AppUpdater.channelKeys.joinToString("/"))
                } else {
                    val v = com.xjtu.toolbox.update.AppUpdater.normalizeChannel(raw)
                    cs.updateChannel = v
                    "已将更新通道设为 $v（${com.xjtu.toolbox.update.AppUpdater.channelLabel(v)}）。"
                }
            }
            "receive_preview_updates" -> {
                val b = parseBoolSetting(value) ?: return ToolReply.badValue("receive_preview_updates", "true/false")
                cs.receivePreviewUpdates = b
                "已将「接收预览版更新」设为 $b。"
            }
            else -> ToolReply.badValue("key", writableSettingKeys.joinToString("/"))
        }
    }

    private fun setAlarm(hour: Int?, minute: Int?, message: String?, days: String?): String {
        val h = hour ?: return ToolReply.missing("hour")
        val m = minute ?: 0
        if (h !in 0..23) return ToolReply.outOfRange("hour", "0-23")
        if (m !in 0..59) return ToolReply.outOfRange("minute", "0-59")
        val repeatDays = days.orEmpty()
            .split(",", "，", " ")
            .mapNotNull { day ->
                when (day.trim().uppercase()) {
                    "MON", "MONDAY", "周一", "星期一" -> java.util.Calendar.MONDAY
                    "TUE", "TUESDAY", "周二", "星期二" -> java.util.Calendar.TUESDAY
                    "WED", "WEDNESDAY", "周三", "星期三" -> java.util.Calendar.WEDNESDAY
                    "THU", "THURSDAY", "周四", "星期四" -> java.util.Calendar.THURSDAY
                    "FRI", "FRIDAY", "周五", "星期五" -> java.util.Calendar.FRIDAY
                    "SAT", "SATURDAY", "周六", "星期六" -> java.util.Calendar.SATURDAY
                    "SUN", "SUNDAY", "周日", "星期日" -> java.util.Calendar.SUNDAY
                    else -> null
                }
            }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, message?.takeIf { it.isNotBlank() } ?: "岱宗盒子提醒")
                if (repeatDays.isNotEmpty()) {
                    putExtra(AlarmClock.EXTRA_DAYS, ArrayList(repeatDays.distinct()))
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolReply.handedOff("alarm", "time: ${"%02d:%02d".format(h, m)}" +
                (message?.takeIf { it.isNotBlank() }?.let { "; label: $it" } ?: ""))
        } catch (e: Exception) {
            ToolReply.failed("set_alarm", e.message ?: "no_alarm_app")
        }
    }

    /**
     * 往本 App 的日程里加一条（和日程页「添加日程」同一张表），只加进**当前学期**。
     *
     * 写入前查冲突：正式课表（缓存）和已有的自建日程都算，周次、星期、时间三样都重叠才算撞。
     * 撞了默认不写，把冲突列给模型，由它问过用户再带 force 重试——
     * 日程页手动添加时也是先弹「时间冲突」让人选，这里不能替用户默默决定。
     */
    private suspend fun addScheduleEvent(
        title: String?,
        date: String?,
        start: String?,
        end: String?,
        location: String?,
        note: String?,
        weeks: String?,
        force: Boolean,
    ): String {
        val name = title?.trim()?.takeIf { it.isNotBlank() } ?: return ToolReply.missing("title")
        val day = date?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return ToolReply.badFormat("date", "yyyy-MM-dd")
        val hhmm = DateTimeFormatter.ofPattern("H:mm")
        val startTime = start?.trim()?.let { runCatching { LocalTime.parse(it, hhmm) }.getOrNull() }
            ?: return ToolReply.badFormat("start", "HH:mm")
        val endTime = end?.trim()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalTime.parse(it, hhmm) }.getOrNull() ?: return ToolReply.badFormat("end", "HH:mm") }
            ?: startTime.plusHours(1)
        val startMin = startTime.hour * 60 + startTime.minute
        val endMin = endTime.hour * 60 + endTime.minute
        if (endMin <= startMin) return ToolReply.outOfRange("end", "> start")

        ensureScheduleLoaded(null)?.let { return it }
        val termCode = cachedTermCode() ?: return ToolReply.failed("add_schedule_event", "no_term_code")
        val termStart = cachedStartDate(termCode)
            ?: return ToolReply.failed("add_schedule_event", "no_term_start_date")
        val maxWeeks = 20
        val week = com.xjtu.toolbox.schedule.TermWeeks.weekOf(termStart, day)
        if (week !in 1..maxWeeks) {
            return ToolReply.outOfRange("date", "current term $termCode, weeks 1-$maxWeeks (from $termStart)")
        }
        // weeks 给了就按周重复（星期取 date 那天），否则只加 date 所在的这一周
        val weekList = weeks?.takeIf { it.isNotBlank() }
            ?.let { com.xjtu.toolbox.schedule.parseWeeksString(it).filter { w -> w in 1..maxWeeks } }
            ?.ifEmpty { return ToolReply.badFormat("weeks", "如 3-5,8，范围 1-$maxWeeks") }
            ?: listOf(week)

        // 节次和日程页自建日程同一个算法：按学校作息表换算，下课那一刻不多占下一节
        val startSection = kotlin.math.floor(com.xjtu.toolbox.schedule.XjtuTime.sectionScaleOf(startMin)).toInt()
            .coerceIn(1, com.xjtu.toolbox.schedule.MAX_SECTIONS)
        val endSection = (kotlin.math.ceil(com.xjtu.toolbox.schedule.XjtuTime.sectionScaleOf(endMin)).toInt() - 1)
            .coerceIn(startSection, com.xjtu.toolbox.schedule.MAX_SECTIONS)
        val accountId = com.xjtu.toolbox.account.AccountContext.activeAccountId ?: ""
        val entity = com.xjtu.toolbox.schedule.CustomCourseEntity(
            accountId = accountId,
            courseName = name,
            location = location?.trim().orEmpty(),
            weekBits = (1..maxWeeks).joinToString("") { w -> if (w in weekList) "1" else "0" },
            dayOfWeek = day.dayOfWeek.value,
            startSection = startSection,
            endSection = endSection,
            startMinuteOfDay = startMin,
            endMinuteOfDay = endMin,
            termCode = termCode,
            note = com.xjtu.toolbox.schedule.encodeAgendaNote(note.orEmpty()),
        )

        val dao = com.xjtu.toolbox.data.AppDatabase.getInstance(context).customCourseDao()
        val conflicts = mutableListOf<String>()
        dao.getConflicts(accountId, termCode, entity.dayOfWeek, 1, com.xjtu.toolbox.schedule.MAX_SECTIONS)
            .filter { com.xjtu.toolbox.schedule.CustomCourseConflicts.conflicts(entity, it) }
            .forEach { other ->
                val shared = com.xjtu.toolbox.schedule.CustomCourseConflicts.sharedWeeks(entity.weekBits, other.weekBits)
                conflicts += "${other.courseName}（自建，${com.xjtu.toolbox.schedule.CustomCourseConflicts.describeWeeks(shared)}）"
            }
        // 正式课按真实上课时刻比：节次换算成钟点，夏季 / 冬季作息按 date 所在月份
        val summer = XjtuTime.isSummerTime(day.monthValue)
        val official = ScheduleCache.readCourses(dataCache, termCode).orEmpty()
        official.filter { it.dayOfWeek == entity.dayOfWeek }.forEach { c ->
            val cStart = c.startMinuteOfDay.takeIf { it >= 0 }
                ?: XjtuTime.getClassTime(c.startSection, summer)?.start?.let { it.hour * 60 + it.minute } ?: return@forEach
            val cEnd = c.endMinuteOfDay.takeIf { it >= 0 }
                ?: XjtuTime.getClassTime(c.endSection, summer)?.end?.let { it.hour * 60 + it.minute } ?: return@forEach
            if (startMin < cEnd && cStart < endMin) {
                val shared = com.xjtu.toolbox.schedule.CustomCourseConflicts.sharedWeeks(entity.weekBits, c.weekBits)
                if (shared.isNotEmpty()) {
                    conflicts += "${c.courseName}（课程，${com.xjtu.toolbox.schedule.CustomCourseConflicts.describeWeeks(shared)}）"
                }
            }
        }
        if (conflicts.isNotEmpty() && !force) {
            return "error: conflict; not_added: true; with: ${conflicts.joinToString("；")}; retry_with: force=true"
        }

        return try {
            dao.insert(entity)
            // 日程页经 Room 的 Flow 自动刷新；桌面小组件得单独叫一声
            com.xjtu.toolbox.widget.ScheduleWidgetUpdater.requestUpdate(context)
            "ok: added; term: $termCode; title: $name; weekday: ${entity.dayOfWeek}; " +
                "time: ${"%02d:%02d".format(startTime.hour, startTime.minute)}-${"%02d:%02d".format(endTime.hour, endTime.minute)}; " +
                "weeks: ${com.xjtu.toolbox.schedule.CustomCourseConflicts.describeWeeks(weekList.sorted())}" +
                (if (conflicts.isNotEmpty()) "; overlaps_kept: ${conflicts.joinToString("；")}" else "")
        } catch (e: Exception) {
            ToolReply.failed("add_schedule_event", e.message)
        }
    }

    private fun calculate(expr: String): String {
        if (expr.isBlank()) return ToolReply.missing("expression")
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

    private data class FetchedPage(
        val finalUrl: String,
        val html: String,
        val truncated: Boolean,
        val via: String = "direct",
    )

    private fun getUrl(
        url: String,
        userAgent: String,
        extra: Map<String, String> = emptyMap(),
        accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    ): FetchedPage? {
        AgentWeb.requirePublicHttpUrl(url)
        return try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            extra.forEach { (k, v) -> req.header(k, v) }
            webClient.newCall(req.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val finalUrl = resp.request.url.toString()
                val body = resp.body ?: return@use null
                if (AgentWeb.isBinaryContentType(resp.header("Content-Type") ?: body.contentType()?.toString())) {
                    return@use null
                }
                val source = body.source()
                val buffer = okio.Buffer()
                var totalRead = 0L
                val maxBytes = AgentWeb.HTML_READ_BYTES
                while (totalRead < maxBytes) {
                    val read = source.read(buffer, maxBytes - totalRead)
                    if (read == -1L) break
                    totalRead += read
                }
                val truncated = source.request(1L)
                val bytes = buffer.readByteArray()
                if (bytes.isEmpty()) return@use null
                FetchedPage(finalUrl, String(bytes, Charsets.UTF_8), truncated)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchReadablePage(startUrl: String): FetchedPage? {
        var url = startUrl
        if (AgentWeb.isSogouJumpUrl(url)) url = AgentWeb.withSogouClickParams(url)
        val firstUa = if (AgentWeb.isWeChatUrl(url) || AgentWeb.isSogouJumpUrl(url)) wechatUa else webUa
        var page = getUrl(url, firstUa)
        val html = page?.html.orEmpty()
        val blocked = html.isNotEmpty() && (
            AgentWeb.looksLikeCaptcha(html) ||
                (AgentWeb.isWeChatUrl(page?.finalUrl ?: url) && AgentWeb.looksLikeWeChatBlock(html))
            )
        if ((page == null || blocked) && AgentWeb.isSogouJumpUrl(startUrl)) {
            page = getUrl(AgentWeb.withSogouClickParams(startUrl, extraOffset = 28), wechatUa)
                ?: getUrl(startUrl, searchUa)
        }
        val after = page
        val afterHtml = after?.html.orEmpty()
        val stillThin = after == null ||
            AgentWeb.looksLikeCaptcha(afterHtml) ||
            AgentWeb.looksLikeWeChatBlock(afterHtml) ||
            (AgentWeb.isWeChatUrl(after.finalUrl) && afterHtml.length < 400)
        if (stillThin && !startUrl.contains("r.jina.ai")) {
            val reader = getUrl(
                AgentWeb.jinaReaderUrl(after?.finalUrl ?: startUrl),
                searchUa,
                extra = mapOf("Accept" to "text/plain"),
                accept = "text/plain, text/markdown, */*;q=0.8",
            )
            if (reader != null && reader.html.isNotBlank() &&
                !AgentWeb.looksLikeCaptcha(reader.html) &&
                !AgentWeb.looksLikeWeChatBlock(reader.html)
            ) {
                return reader.copy(via = "jina")
            }
        }
        return after
    }

    private fun webFetch(rawUrl: String): String {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ToolReply.badFormat("url", "http(s)://…")
        }
        return try {
            AgentWeb.requirePublicHttpUrl(url)
            val page = fetchReadablePage(url)
                ?: return ToolReply.failed("web_fetch", "refused_or_empty")
            val html = page.html
            if (AgentWeb.looksLikeCaptcha(html) && !AgentWeb.looksLikeJinaMarkdown(html)) {
                return ToolReply.failed("web_fetch", "captcha_page")
            }
            if (AgentWeb.looksLikeJinaMarkdown(html)) {
                return buildString {
                    append("最终URL：${page.finalUrl}\n")
                    if (page.via == "jina") append("（经公开读页服务提取）\n")
                    append("\n正文：\n")
                    append(AgentWeb.truncateMarkdown(html))
                    append("\n\n" + ToolReply.EXTERNAL_DATA)
                }
            }
            val doc = org.jsoup.Jsoup.parse(html, page.finalUrl)
            val title = doc.title().ifBlank { page.finalUrl }
            val markdown = AgentWeb.truncateMarkdown(AgentWeb.htmlToMarkdown(doc.html(), page.finalUrl))
            val links = doc.select("a[href]").asSequence()
                .mapNotNull { a ->
                    val href = normalizeSearchLink(a.attr("href"), page.finalUrl)
                    val label = a.text().replace(Regex("\\s+"), " ").trim().take(80)
                    if (href.startsWith("http") && label.isNotBlank()) label to href else null
                }
                .distinctBy { it.second }
                .take(12)
                .toList()
            buildString {
                append("标题：$title\n")
                append("最终URL：${page.finalUrl}\n")
                if (page.via == "jina") append("（经公开读页服务提取）\n")
                if (page.truncated) append("（页面较大，已从开头提取正文）\n")
                if (links.isNotEmpty()) {
                    append("页面链接：\n")
                    links.forEachIndexed { i, (label, href) ->
                        append("${i + 1}. [$label]($href)\n")
                    }
                }
                append("\n正文：\n")
                append(markdown.ifBlank { "页面无可提取正文。" })
                append("\n\n" + ToolReply.EXTERNAL_DATA)
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (e is java.net.UnknownServiceException || msg.contains("CLEARTEXT", ignoreCase = true)) {
                ToolReply.failed("web_fetch", "cleartext_http_blocked")
            } else {
                ToolReply.failed("web_fetch", msg)
            }
        }
    }
}
