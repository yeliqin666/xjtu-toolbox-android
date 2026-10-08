package com.xjtu.toolbox.schedule

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.arr
import kotlinx.serialization.json.jsonObject
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeDouble
import okhttp3.FormBody
import okhttp3.Request

private const val TAG = "SchoolCourseApi"
private const val BASE_URL = "https://jwxt.xjtu.edu.cn"

// ── 数据模型 ────────────────────────────────────────
//
// 模型（TermOption / DepartmentOption / CampusOption / ElectiveCategoryOption / SchoolCourse /
// SchoolCourseResult / SchoolCourseQuery）与取数端口 `SchoolCourseSource` 都搬到了 :core 的
// `schedule/SchoolCourseModels.kt`（两端共用）。本文件只剩上游解析：`querySetting` 那个 JSON
// 数组、`qxfbkccx.do` 的 POST、以及 `/jwapp/code/*` 的院系表。
//
// ⚠️ `SchoolCourse` 里人数/学时那几项现在是**可空**的（campus-api 不投影它们），
// :app 的上游一直有值 ⇒ 这里照旧直接赋值，行为逐字不变。

// ── API ─────────────────────────────────────────────

class SchoolCourseApi(private val site: SiteSession) {

    /** kcbcx 应用的基础 URL（与 wdkb 不同，是独立的应用） */
    private val appBase = "$BASE_URL/jwapp/sys/kcbcx"
    private val kcbcxReferer = "$appBase/*default/index.do"

    // ── 初始化：确保 kcbcx 应用已加载 ──

    private var appInitialized = false

    /**
     * 确保应用会话就绪：先访问 kcbcx 首页让服务器初始化 session
     */
    private suspend fun ensureAppInitialized() {
        if (appInitialized) return
        try {
            val req = Request.Builder()
                .url("$appBase/*default/index.do")
                .header("Accept", "text/html")
                .build()
            site.executeWithReAuth(req).close()
            appInitialized = true
        } catch (e: Exception) {
            Log.w(TAG, "ensureAppInitialized failed", e)
        }
    }

    // ── 下拉选项查询 ──

    /** 获取当前学期 */
    suspend fun getCurrentTerm(): String {
        ensureAppInitialized()
        val request = kcbcxPost("$appBase/modules/bjkcb/dqxnxq.do")

        val body = execute(request)
        val json = body.safeParseJsonObject()
        return json.obj("datas")
            ?.obj("dqxnxq")
            ?.arr("rows")?.get(0)?.jsonObject
            ?.get("DM")?.stringValue ?: ""
    }

    /** 获取所有学期列表 */
    suspend fun getTermList(): List<TermOption> {
        ensureAppInitialized()
        val request = kcbcxPost(
            "$appBase/modules/bjkcb/xnxqcx.do",
            FormBody.Builder().add("*order", "-DM").build(),
        )

        val body = execute(request)
        val json = body.safeParseJsonObject()
        val rows = json.obj("datas")
            ?.obj("xnxqcx")
            ?.arr("rows")
            ?: return emptyList()

        return rows.map { row ->
            val obj = row.jsonObject
            TermOption(
                code = obj.get("DM").stringValue,
                name = obj.get("MC")?.stringValue ?: obj.get("DM").stringValue
            )
        }
    }

    /** 获取开课单位列表 */
    suspend fun getDepartments(): List<DepartmentOption> {
        ensureAppInitialized()
        // /jwapp/code/* 与空教室的校区字典同类：不带 kcbcx Referer + XHR 头时 rows 经常是空的。
        val request = kcbcxPost("$BASE_URL/jwapp/code/44e02e19-e31b-4916-91b2-0a04380cbd3a.do")

        val body = execute(request)
        val json = body.safeParseJsonObject()
        val rows = json.obj("datas")
            ?.obj("code")
            ?.arr("rows")
            ?: return emptyList()

        return rows.map { row ->
            val obj = row.jsonObject
            DepartmentOption(
                code = obj.get("id").stringValue,
                name = obj.get("name").stringValue
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
    suspend fun queryCourses(
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
        val queryParts = mutableListOf<JsonElement>()

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
        val termGroup = buildJsonArray {
            add(buildSimpleCondition("XNXQDM", termCode, "and", "equal"))
            add(buildJsonArray {
                add(buildSimpleCondition("RWZTDM", "1", "and", "equal"))
                add(buildSimpleConditionNoValue("RWZTDM", "or", "isNull"))
            })
        }
        queryParts.add(termGroup)

        // 排序
        queryParts.add(buildOrderCondition("+KKDWDM,+KCH,+KXH"))

        val querySetting = JsonArray(queryParts).toString()
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

        val request = kcbcxPost("$appBase/modules/qxkcb/qxfbkccx.do", formBuilder.build())

        val body = execute(request)
        val json = body.safeParseJsonObject()

        val datas = json.obj("datas")
            ?.obj("qxfbkccx")

        val totalSize = datas?.get("totalSize")?.intValue ?: 0
        val rows = datas?.arr("rows") ?: JsonArray(emptyList())

        val courses = rows.map { row ->
            val obj = row.jsonObject
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
                isPublicElective = obj.get("SFXGXK")?.stringValue == "1",
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
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("caption", caption)
        put("linkOpt", linkOpt)
        put("builderList", "cbl_String")
        put("builder", builder)
        put("value", value)
    }

    private fun buildConditionMValue(
        name: String, caption: String, linkOpt: String, value: String
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("caption", caption)
        put("linkOpt", linkOpt)
        put("builderList", "cbl_m_List")
        put("builder", "m_value_equal")
        put("value", value)
    }

    private fun buildSimpleCondition(
        name: String, value: String, linkOpt: String, builder: String
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("value", value)
        put("linkOpt", linkOpt)
        put("builder", builder)
    }

    private fun buildSimpleConditionNoValue(
        name: String, linkOpt: String, builder: String
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("linkOpt", linkOpt)
        put("builder", builder)
    }

    private fun buildOrderCondition(order: String): JsonObject = buildJsonObject {
        put("name", "*order")
        put("value", order)
        put("linkOpt", "AND")
        put("builder", "m_value_equal")
    }

    private fun kcbcxPost(url: String, form: FormBody = FormBody.Builder().build()): Request =
        Request.Builder()
            .url(url)
            .post(form)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", kcbcxReferer)
            .build()

    private suspend fun execute(request: Request): String =
        site.executeWithReAuth(request).use { it.body?.string().orEmpty() }
}
