package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.schedule.DepartmentOption
import com.xjtu.toolbox.schedule.SchoolCourse
import com.xjtu.toolbox.schedule.SchoolCourseQuery
import com.xjtu.toolbox.schedule.SchoolCourseResult
import com.xjtu.toolbox.schedule.SchoolCourseSource
import com.xjtu.toolbox.schedule.TermOption
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeIntOrNull
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 全校课表的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `SchoolCourseApi`）。 */
class CampusSchoolCourseApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : SchoolCourseSource {

    /** serve 的 `/api/jwxt/school-courses` 报的两个能力开关（拉到响应后才更新）。 */
    private var serveDepartmentFilter = false
    private var serveElectiveFilter = false

    /** 能不能按开课单位筛（见 [SchoolCourseSource.supportsDepartmentFilter]）。 */
    override val supportsDepartmentFilter: Boolean
        get() = mode == ApiMode.SERVE && serveDepartmentFilter && SERVE_HAS_FILTER_OPTIONS

    /** 能不能按是否公选课 / 公选类别筛（见 [SchoolCourseSource.supportsElectiveFilter]）。 */
    override val supportsElectiveFilter: Boolean
        get() = mode == ApiMode.SERVE && serveElectiveFilter && SERVE_HAS_FILTER_OPTIONS

    override suspend fun terms(): List<TermOption> {
        // serve 直接给学期名，不自己按学期号推（[termNameOf] 只给 campus-api 兜底）。
        if (mode == ApiMode.SERVE) {
            val data = getData("/api/jwxt/terms")
            return data.arr("terms").orEmpty().mapNotNull { element ->
                val term = element as? JsonObject ?: return@mapNotNull null
                val code = term["code"].safeString().trim()
                if (code.isEmpty()) return@mapNotNull null
                TermOption(code = code, name = term["name"].safeString().trim().ifBlank { termNameOf(code) })
            }
        }
        val data = getData("/api/jwxt/terms")
        return data.arr("terms").orEmpty()
            .map { it.safeString().trim() }
            .filter { it.isNotEmpty() }
            .map { TermOption(code = it, name = termNameOf(it)) }
    }

    override suspend fun currentTerm(): String = getData("/api/jwxt/term")["term"].safeString()

    /** 见类 KDoc：campus-api 不提供开课单位那张 id 表。 */
    override suspend fun departments(): List<DepartmentOption> = emptyList()

    override suspend fun query(query: SchoolCourseQuery, page: Int, pageSize: Int): SchoolCourseResult {
        if (mode == ApiMode.SERVE) return serveQuery(query, page, pageSize)
        val data = getData(
            "/api/jwxt/school-courses",
            "term" to query.termCode,
            "course" to query.courseName,
            "code" to query.courseCode,
            "teacher" to query.teacher,
            "campus" to query.campusCode,
            "classGroup" to query.className,
            "weekday" to query.weekday.takeIf { it > 0 }?.toString().orEmpty(),
            "from" to query.startSection.takeIf { it > 0 }?.toString().orEmpty(),
            "to" to query.endSection.takeIf { it > 0 }?.toString().orEmpty(),
            "page" to page.coerceAtLeast(1).toString(),
            "size" to pageSize.coerceIn(1, 200).toString(),
        )
        val term = data["term"].safeString()
        val courses = data.arr("rows").orEmpty()
            .mapNotNull { if (it.isObject) it.jsonObject else null }
            .map { parseCourse(it, term) }
        return SchoolCourseResult(
            totalSize = data["totalSize"].safeInt(),
            pageNumber = data["page"].safeInt().coerceAtLeast(1),
            pageSize = data["size"].safeInt().coerceAtLeast(1),
            courses = courses,
        )
    }

    private fun parseCourse(item: JsonObject, term: String): SchoolCourse = SchoolCourse(
        courseCode = item["courseCode"].safeString().trim(),
        courseName = item["courseName"].safeString().trim(),
        sectionNumber = item["classNo"].safeString().trim(),
        teacher = item["teacher"].safeString().trim(),
        department = item["department"].safeString().trim(),
        credit = item["credit"].safeDouble(),
        // 人数/学时见类 KDoc：这一端不知道 ⇒ 留 null，屏上不画那几块
        className = item["classGroup"].safeString().trim(),
        campus = item["campus"].safeString().trim(),
        isPublicElective = item["isPublicElective"].safeBoolean(),
        // campus-api 把上游的 `XGXKLBDM_DISPLAY` 投影成 `courseKind`
        electiveCategory = item["courseKind"].safeString().trim(),
        teachingClassId = item["id"].safeString().trim(),
        termCode = term,
    )

    /** `2025-2026-1` → `2025-2026学年 第一学期`；认不出就原样返回学期号。 */
    private fun termNameOf(code: String): String {
        val year = code.substringBeforeLast('-', missingDelimiterValue = "")
        val index = code.substringAfterLast('-', missingDelimiterValue = "").toIntOrNull()
        val cn = when (index) {
            1 -> "第一学期"
            2 -> "第二学期"
            3 -> "第三学期"
            4 -> "第四学期"
            else -> null
        }
        return if (year.isEmpty() || cn == null) code else "${year}学年 $cn"
    }

    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("全校课表", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> if (v.isNotEmpty()) parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 全校课表返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            error("campus-api 全校课表失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 全校课表返回缺少 data：${text.take(120)}")
    }
    // ─── serve 模式（契约 §5.3）────────────────────────────────────────────

    /** serve 模式（契约 §5.3） */
    private suspend fun serveQuery(query: SchoolCourseQuery, page: Int, pageSize: Int): SchoolCourseResult {
        val data = getData(
            "/api/jwxt/school-courses",
            "term" to query.termCode,
            "course" to query.courseName,
            "code" to query.courseCode,
            "teacher" to query.teacher,
            "campus" to query.campusCode,
            "weekday" to query.weekday.takeIf { it > 0 }?.toString().orEmpty(),
            "from" to query.startSection.takeIf { it > 0 }?.toString().orEmpty(),
            "to" to query.endSection.takeIf { it > 0 }?.toString().orEmpty(),
            "page" to page.coerceAtLeast(1).toString(),
            "size" to pageSize.coerceIn(1, 200).toString(),
        )
        serveDepartmentFilter = data["supportsDepartmentFilter"].safeBoolean()
        serveElectiveFilter = data["supportsElectiveFilter"].safeBoolean()
        val courses = data.arr("courses").orEmpty()
            .mapNotNull { if (it.isObject) it.jsonObject else null }
            .map { parseServeCourse(it) }
        return SchoolCourseResult(
            totalSize = data["total"].safeInt(),
            pageNumber = data["page"].safeInt().coerceAtLeast(1),
            pageSize = data["size"].safeInt().coerceAtLeast(1),
            courses = courses,
        )
    }

    /** serve 的 `SchoolCourseDto`：`:data` 直连教务 97 列的全量投影。 */
    private fun parseServeCourse(item: JsonObject): SchoolCourse = SchoolCourse(
        courseCode = item["courseCode"].safeString().trim(),
        courseName = item["courseName"].safeString().trim(),
        sectionNumber = item["sectionNumber"].safeString().trim(),
        teacher = item["teacher"].safeString().trim(),
        department = item["department"].safeString().trim(),
        credit = item["credit"].safeDouble(),
        totalHours = item["totalHours"].safeDoubleOrNull(),
        lectureHours = item["lectureHours"].safeDoubleOrNull(),
        labHours = item["labHours"].safeDoubleOrNull(),
        practiceHours = item["practiceHours"].safeDoubleOrNull(),
        enrollCount = item["enrollCount"].safeIntOrNull(),
        capacity = item["capacity"].safeIntOrNull(),
        className = item["className"].safeString().trim(),
        scheduleLocation = item["scheduleLocation"].safeString().trim(),
        campus = item["campus"].safeString().trim(),
        isPublicElective = item["isPublicElective"].safeBoolean(),
        electiveCategory = item["electiveCategory"].safeString().trim(),
        weeklyHours = item["weeklyHours"].safeDoubleOrNull(),
        maleEnrollCount = item["maleEnrollCount"].safeIntOrNull(),
        femaleEnrollCount = item["femaleEnrollCount"].safeIntOrNull(),
        teachingClassId = item["teachingClassId"].safeString().trim(),
        termCode = item["termCode"].safeString().trim(),
    )

    private companion object {
        /** serve 契约里有没有那两张筛选项表（开课单位 / 公选类别）。 */
        const val SERVE_HAS_FILTER_OPTIONS = false
    }


}
