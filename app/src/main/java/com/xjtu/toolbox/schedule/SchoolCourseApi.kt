package com.xjtu.toolbox.schedule

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeDouble
import okhttp3.FormBody
import okhttp3.Request
import kotlinx.coroutines.runBlocking

private const val TAG = "SchoolCourseApi"
private const val BASE_URL = "https://jwxt.xjtu.edu.cn"

// ── 数据模型 ────────────────────────────────────────

/** 学期选项 */
data class TermOption(
    val code: String,   // e.g. "2025-2026-2"
    val name: String    // e.g. "2025-2026学年 第二学期"
)

/** 开课单位（院系）选项 */
data class DepartmentOption(
    val code: String,   // e.g. "13028000"
    val name: String    // e.g. "物理学院"
)

/** 校区选项 */
data class CampusOption(
    val code: String,   // e.g. "1"
    val name: String    // e.g. "兴庆校区"
)

/** 校公选课类别选项 */
data class ElectiveCategoryOption(
    val code: String,   // e.g. "06"
    val name: String    // e.g. "基础通识类选修课"
)

/** 全校课程查询结果 */
data class SchoolCourse(
    val courseCode: String,          // KCH - 课程号
    val courseName: String,          // KCM - 课程名
    val sectionNumber: String,       // KXH - 课序号
    val teacher: String,             // SKJS - 上课教师
    val department: String,          // KKDWDM_DISPLAY - 开课单位
    val credit: Double,              // XF - 学分
    val totalHours: Double,          // XS - 总学时
    val lectureHours: Double,        // SKXS - 授课学时
    val labHours: Double,            // SYXS - 实验学时
    val practiceHours: Double,       // SJXS - 实践学时
    val enrollCount: Int,            // XKZRS - 选课人数
    val capacity: Int,               // KRL - 课容量
    val className: String,           // SKBJ - 上课班级
    val scheduleLocation: String,    // YPSJDD - 已排时间地点
    val campus: String,              // XXXQDM_DISPLAY - 校区
    val isPublicElective: Boolean,   // SFXGXK - 是否校公选课
    val electiveCategory: String,    // XGXKLBDM_DISPLAY - 校公选课类别
    val weeklyHours: Double,         // KNZXS - 周学时
    val maleEnrollCount: Int,        // NSXKRS - 男生选课人数
    val femaleEnrollCount: Int,      // NVSXKRS - 女生选课人数
    val teachingClassId: String,     // JXBID - 教学班ID
    val termCode: String             // XNXQDM - 学年学期
) {
    /** 剩余容量 */
    val remaining: Int get() = capacity - enrollCount

    /** 容量比例 (0.0 ~ 1.0) */
    val fillRatio: Float get() = if (capacity > 0) (enrollCount.toFloat() / capacity).coerceIn(0f, 1f) else 0f
}

/** 查询分页结果 */
data class SchoolCourseResult(
    val totalSize: Int,
    val pageNumber: Int,
    val pageSize: Int,
    val courses: List<SchoolCourse>
) {
    val totalPages: Int get() = if (pageSize > 0) (totalSize + pageSize - 1) / pageSize else 0
}

// ── API ─────────────────────────────────────────────

class SchoolCourseApi(private val site: SiteSession) {

    /** kcbcx 应用的基础 URL（与 wdkb 不同，是独立的应用） */
    private val appBase = "$BASE_URL/jwapp/sys/kcbcx"

    // ── 初始化：确保 kcbcx 应用已加载 ──

    private var appInitialized = false

    /**
     * 确保应用会话就绪：先访问 kcbcx 首页让服务器初始化 session
     */
    private fun ensureAppInitialized() {
        if (appInitialized) return
        try {
            val req = Request.Builder()
                .url("$appBase/*default/index.do")
                .header("Accept", "text/html")
                .build()
            runBlocking { site.executeWithReAuth(req) }.close()
            appInitialized = true
        } catch (e: Exception) {
            Log.w(TAG, "ensureAppInitialized failed", e)
        }
    }

    // ── 下拉选项查询 ──

    /** 获取当前学期 */
    fun getCurrentTerm(): String {
        ensureAppInitialized()
        val request = Request.Builder()
            .url("$appBase/modules/bjkcb/dqxnxq.do")
            .post(FormBody.Builder().build())
            .header("Accept", "application/json")
            .build()

        val body = execute(request)
        val json = body.safeParseJsonObject()
        return json.getAsJsonObject("datas")
            ?.getAsJsonObject("dqxnxq")
            ?.getAsJsonArray("rows")?.get(0)?.asJsonObject
            ?.get("DM")?.asString ?: ""
    }

    /** 获取所有学期列表 */
    fun getTermList(): List<TermOption> {
        ensureAppInitialized()
        val request = Request.Builder()
            .url("$appBase/modules/bjkcb/xnxqcx.do")
            .post(FormBody.Builder().add("*order", "-DM").build())
            .header("Accept", "application/json")
            .build()

        val body = execute(request)
        val json = body.safeParseJsonObject()
        val rows = json.getAsJsonObject("datas")
            ?.getAsJsonObject("xnxqcx")
            ?.getAsJsonArray("rows")
            ?: return emptyList()

        return rows.map { row ->
            val obj = row.asJsonObject
            TermOption(
                code = obj.get("DM").asString,
                name = obj.get("MC")?.asString ?: obj.get("DM").asString
            )
        }
    }

    /** 获取开课单位列表 */
    fun getDepartments(): List<DepartmentOption> {
        ensureAppInitialized()
        val request = Request.Builder()
            .url("$BASE_URL/jwapp/code/44e02e19-e31b-4916-91b2-0a04380cbd3a.do")
            .post(FormBody.Builder().build())
            .header("Accept", "application/json")
            .build()

        val body = execute(request)
        val json = body.safeParseJsonObject()
        val rows = json.getAsJsonObject("datas")
            ?.getAsJsonObject("code")
            ?.getAsJsonArray("rows")
            ?: return emptyList()

        return rows.map { row ->
            val obj = row.asJsonObject
            DepartmentOption(
                code = obj.get("id").asString,
                name = obj.get("name").asString
            )
        }.sortedBy { it.name }
    }

    /** 获取校区列表 */
    fun getCampusList(): List<CampusOption> {
        // 硬编码（数据稳定，避免多余请求）
        return listOf(
            CampusOption("1", "兴庆校区"),
            CampusOption("2", "雁塔校区"),
            CampusOption("3", "曲江校区"),
            CampusOption("4", "苏州校区"),
            CampusOption("5", "创新港校区")
        )
    }

    /** 获取校公选课类别列表 */
    fun getElectiveCategories(): List<ElectiveCategoryOption> {
        // 硬编码（数据稳定）
        return listOf(
            ElectiveCategoryOption("06", "基础通识类选修课"),
            ElectiveCategoryOption("07", "基础通识类核心课"),
            ElectiveCategoryOption("08", "钱学森学院特色课")
        )
    }

    // ── 核心查询 ──

    /**
     * 全校课程查询
     * @param termCode 学期代码，如 "2025-2026-2"
     * @param courseName 课程名（模糊匹配），null 不限
     * @param courseCode 课程号（模糊匹配），null 不限
     * @param teacher 上课教师（模糊匹配），null 不限
     * @param departmentCode 开课单位代码，null 不限
     * @param className 上课班级（模糊匹配），null 不限
     * @param campusCode 校区代码，null 不限
     * @param isPublicElective 是否校公选课，null 不限
     * @param electiveCategoryCode 校公选课类别代码，null 不限
     * @param weekday 星期几（1~7），null 不限
     * @param startSection 开始节次，null 不限
     * @param endSection 结束节次，null 不限
     * @param pageSize 每页条数
     * @param pageNumber 页码
     */
    fun queryCourses(
        termCode: String,
        courseName: String? = null,
        courseCode: String? = null,
        teacher: String? = null,
        departmentCode: String? = null,
        className: String? = null,
        campusCode: String? = null,
        isPublicElective: Boolean? = null,
        electiveCategoryCode: String? = null,
        weekday: Int? = null,
        startSection: Int? = null,
        endSection: Int? = null,
        pageSize: Int = 20,
        pageNumber: Int = 1
    ): SchoolCourseResult {
        ensureAppInitialized()

        // 构建 querySetting JSON 数组
        val queryParts = JsonArray()

        // 用户输入的检索条件
        courseName?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildCondition("KCM", "课程名", "AND", "include", value))
        }
        courseCode?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildCondition("KCH", "课程号", "AND", "include", value))
        }
        teacher?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildCondition("SKJS", "上课教师", "AND", "include", value))
        }
        departmentCode?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildCondition("KKDWDM", "开课单位", "AND", "equal", value))
        }
        className?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildCondition("SKBJ", "上课班级", "AND", "include", value))
        }
        campusCode?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildCondition("XXXQDM", "学校校区", "AND", "equal", value))
        }
        isPublicElective?.let { value ->
            queryParts.add(buildCondition("SFXGXK", "是否校公选课", "AND", "equal", if (value) "1" else "0"))
        }
        electiveCategoryCode?.takeIf { it.isNotBlank() }?.let { value ->
            queryParts.add(buildConditionMValue("XGXKLBDM", "校公选课类别", "AND", value))
        }

        // 学期+任务状态（必选条件）
        val termGroup = JsonArray().apply {
            add(buildSimpleCondition("XNXQDM", termCode, "and", "equal"))
            add(JsonArray().apply {
                add(buildSimpleCondition("RWZTDM", "1", "and", "equal"))
                add(buildSimpleConditionNoValue("RWZTDM", "or", "isNull"))
            })
        }
        queryParts.add(termGroup)

        // 排序
        queryParts.add(buildOrderCondition("+KKDWDM,+KCH,+KXH"))

        val querySetting = queryParts.toString()
        Log.d(TAG, "querySetting: $querySetting")

        // 构建请求
        val formBuilder = FormBody.Builder()
            .add("querySetting", querySetting)
            .add("*order", "+KKDWDM,+KCH,+KXH")
            .add("SKXQ", weekday?.toString() ?: "")
            .add("KSJC", startSection?.toString() ?: "")
            .add("JSJC", endSection?.toString() ?: "")
            .add("pageSize", pageSize.toString())
            .add("pageNumber", pageNumber.toString())

        val request = Request.Builder()
            .url("$appBase/modules/qxkcb/qxfbkccx.do")
            .post(formBuilder.build())
            .header("Accept", "application/json")
            .build()

        val body = execute(request)
        val json = body.safeParseJsonObject()

        val datas = json.getAsJsonObject("datas")
            ?.getAsJsonObject("qxfbkccx")

        val totalSize = datas?.get("totalSize")?.asInt ?: 0
        val rows = datas?.getAsJsonArray("rows") ?: JsonArray()

        val courses = rows.map { row ->
            val obj = row.asJsonObject
            SchoolCourse(
                courseCode = obj.get("KCH").safeString(),
                courseName = obj.get("KCM").safeString(),
                sectionNumber = obj.get("KXH").safeString(),
                teacher = obj.get("SKJS").safeString(),
                department = obj.get("KKDWDM_DISPLAY").safeString(),
                credit = obj.get("XF").safeDouble(),
                totalHours = obj.get("XS").safeDouble(),
                lectureHours = obj.get("SKXS").safeDouble(),
                labHours = obj.get("SYXS").safeDouble(),
                practiceHours = obj.get("SJXS").safeDouble(),
                enrollCount = obj.get("XKZRS").safeInt(),
                capacity = obj.get("KRL").safeInt(),
                className = obj.get("SKBJ").safeString(),
                scheduleLocation = obj.get("YPSJDD").safeString(),
                campus = obj.get("XXXQDM_DISPLAY").safeString(),
                isPublicElective = obj.get("SFXGXK")?.asString == "1",
                electiveCategory = obj.get("XGXKLBDM_DISPLAY").safeString(),
                weeklyHours = obj.get("KNZXS").safeDouble(),
                maleEnrollCount = obj.get("NSXKRS").safeInt(),
                femaleEnrollCount = obj.get("NVSXKRS").safeInt(),
                teachingClassId = obj.get("JXBID").safeString(),
                termCode = obj.get("XNXQDM").safeString()
            )
        }

        Log.d(TAG, "queryCourses: totalSize=$totalSize, returned=${courses.size}, page=$pageNumber")
        return SchoolCourseResult(totalSize, pageNumber, pageSize, courses)
    }

    // ── JSON 辅助构建 ──

    private fun buildCondition(
        name: String, caption: String, linkOpt: String, builder: String, value: String
    ): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("caption", caption)
        addProperty("linkOpt", linkOpt)
        addProperty("builderList", "cbl_String")
        addProperty("builder", builder)
        addProperty("value", value)
    }

    private fun buildConditionMValue(
        name: String, caption: String, linkOpt: String, value: String
    ): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("caption", caption)
        addProperty("linkOpt", linkOpt)
        addProperty("builderList", "cbl_m_List")
        addProperty("builder", "m_value_equal")
        addProperty("value", value)
    }

    private fun buildSimpleCondition(
        name: String, value: String, linkOpt: String, builder: String
    ): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value)
        addProperty("linkOpt", linkOpt)
        addProperty("builder", builder)
    }

    private fun buildSimpleConditionNoValue(
        name: String, linkOpt: String, builder: String
    ): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("linkOpt", linkOpt)
        addProperty("builder", builder)
    }

    private fun buildOrderCondition(order: String): JsonObject = JsonObject().apply {
        addProperty("name", "*order")
        addProperty("value", order)
        addProperty("linkOpt", "AND")
        addProperty("builder", "m_value_equal")
    }

    private fun execute(request: Request): String =
        runBlocking { site.executeWithReAuth(request) }.use { it.body?.string().orEmpty() }
}
