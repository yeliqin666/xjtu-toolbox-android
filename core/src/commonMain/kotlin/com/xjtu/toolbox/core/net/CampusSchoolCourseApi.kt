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
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : SchoolCourseSource {

    /** serve 的 `/api/jwxt/school-courses` 如实报的两个能力开关（拉到响应后才更新）。 */
    private var serveDepartmentFilter = false
    private var serveElectiveFilter = false

    /**
     * 能不能按**开课单位**筛（见 [SchoolCourseSource.supportsDepartmentFilter]）。
     *
     * serve 模式的响应里 `supportsDepartmentFilter` 是 `true`（`:data` 直连教务、那张 id 表真在），
     * 但契约 §5.3 的端点清单里**没有**返回它的端点 ⇒ [departments] 只能给空表，而屏是先看这个开关
     * 画下拉、再拿 [departments] 填选项：「下拉出现了、里面一个选项都没有」比「控件不出现」更糟
     * （见 [SchoolCourseSource] 的 KDoc）。
     *
     * 所以这里报的是两件事的**与**：能力 true、选项表不在契约里 ⇒ false。实测就是 false。
     * TODO：`:server` 补一个选项表端点（或让 school-courses 响应带上它）之后，把
     * [SERVE_HAS_FILTER_OPTIONS] 改成 true 并实现 [departments] —— 就这两处。
     */
    override val supportsDepartmentFilter: Boolean
        get() = mode == ApiMode.SERVE && serveDepartmentFilter && SERVE_HAS_FILTER_OPTIONS

    /**
     * 能不能按**是否公选课 / 公选类别**筛（见 [SchoolCourseSource.supportsElectiveFilter]）。
     *
     * 与 [supportsDepartmentFilter] 同一条理由：`:server` 报 `true`，但公选类别那张表同样没有
     * 端点（旧路是 campus-api 的 `buildQuerySetting` 不放行这两个条件）。
     */
    override val supportsElectiveFilter: Boolean
        get() = mode == ApiMode.SERVE && serveElectiveFilter && SERVE_HAS_FILTER_OPTIONS

    override suspend fun terms(): List<TermOption> {
        // serve（§5.3）给的是 `[{code,name}]` —— 名字由 `:data` 从上游取，所以**不自己按学期号推名字**
        // （那份 [termNameOf] 只是旧路的兜底：campus-api 只给学期号字符串）
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

    /**
     * serve 模式（契约 §5.3）：`{page,size,total,courses:[SchoolCourseDto],
     * supportsDepartmentFilter,supportsElectiveFilter}`。
     *
     * 两个能力开关在**每一个响应**里（开关随部署变，见契约 §2）：拉到就更新，屏据此决定
     * 画不画那两档控件（与 [supportsDepartmentFilter] 那条「与」一起生效）。
     * `classGroup` / 公选那两个条件**不发** —— 契约 §5.3 的请求参数里没有它们。
     */
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

    /**
     * serve 的 `SchoolCourseDto`：`:data` 直连教务 97 列的**全量**投影。
     *
     * 契约 §5「要改」点名的那一批（人数/学时、`YPSJDD`、开课单位）在这里都有值，所以旧路
     * 刻意留空的那几项改成照读（见类 KDoc 的那张对照表：旧路是 campus-api 的降级）。
     * 缺字段/`null` 就交给模型的可空字段（`null` = 不知道 ⇒ 屏上不画容量条），**不拿 0 冒充**。
     */
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
        /**
         * serve 契约里有没有那两张**筛选项表**（开课单位 / 公选类别）。
         *
         * 实测 `:server` 的 `/api/jwxt/school-courses` 报 `supports*Filter:true`，但契约 §5.3
         * 的端点清单里没有返回 id 表的端点 ⇒ 客户端只能给空表。这一条就是那份缺口：
         * 补上端点之后改成 `true` 即可（见 [supportsDepartmentFilter] 的 TODO）。
         */
        const val SERVE_HAS_FILTER_OPTIONS = false
    }


}
