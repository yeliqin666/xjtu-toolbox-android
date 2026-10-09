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
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 全校课表的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `SchoolCourseApi`）。
 *
 * 上游是同一个 `qxfbkccx.do`（campus-api 的行内注释写明），但**它的归一投影少了一批字段**，
 * 所以这一端的能力比 :app 弱 —— 每一处都写出来，不猜、不伪造：
 *
 * | 字段 / 能力 | :app（上游 97 键） | 本类（campus-api） |
 * |---|---|---|
 * | 课程号/名、课序号、教师、开课单位、学分、校区、上课班级、是否公选、公选类别 | 有 | 有 |
 * | 人数类（课容量/选课人数/男女）与各学时 | 有 | **没有**（campus-api 按「不猜」原则刻意不投影）|
 * | 已排时间地点 `YPSJDD`（[SchoolCourse.scheduleLocation]）| 有 | **没有**：campus-api 给的是
 *   `weekday`/`fromSection`/`toSection`/`weekText`/`roomCode` 这些**上游原始列**，形状不同、且
 *   `roomCode` 只是教室代码没有名字 ⇒ 不互相伪造，留空（屏上那一行自然不出现）|
 * | 按开课单位筛 | 有 | **没有**（那张 id 表在 `/jwapp/code/` 下）|
 * | 按公选课/公选类别筛 | 有 | **没有**（campus-api 的 `buildQuerySetting` 不放行这两个条件）|
 *
 * 后两条通过 [supportsDepartmentFilter] / [supportsElectiveFilter] 告诉屏：**那一档控件不出现**，
 * 而不是出现了却筛不动。
 *
 * 学期名是**从学期号推出来的**（`2025-2026-1` → `2025-2026学年 第一学期`）：campus-api 的
 * `/api/jwxt/terms` 只给学期号，而 `:app` 的上游给的是同一个格式的名字 ⇒ 这里按同一规则生成，
 * 不是编造事实。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusSchoolCourseApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : SchoolCourseSource {

    override val supportsDepartmentFilter: Boolean get() = false
    override val supportsElectiveFilter: Boolean get() = false

    override suspend fun terms(): List<TermOption> {
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
}
