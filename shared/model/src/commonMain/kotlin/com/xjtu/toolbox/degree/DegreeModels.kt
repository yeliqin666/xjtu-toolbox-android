package com.xjtu.toolbox.degree

import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 培养方案与学业进度（#255）。树是基础，成绩来自成绩查询，教务的完成报告只作参考。 */
enum class DegreeCourseStatus { PASSED, FAILED, NOT_TAKEN, OTHER }

data class DegreeCourse(
    val code: String,
    val name: String,
    val credit: Double,
    val status: DegreeCourseStatus = DegreeCourseStatus.NOT_TAKEN,
    /** 教务原样写的状态（通过、未修读……），有些码我们没见过，别自己翻译。 */
    val statusText: String = "",
    val score: String? = null,
    val term: String? = null,
    val plannedTerm: String? = null,
    val substitute: String? = null,
    /** 课程号对不上、靠课程名完全相同匹配上的（换过课程号的课）。 */
    val matchedByName: Boolean = false,
    /** 用户自己确认抵扣的，教务没认。 */
    val userConfirmed: Boolean = false,
    /** 教务的原始字段。课程组织各专业写法不一，详情时原样给模型看，见 [DegreeAgentJson]。 */
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class DegreeNode(
    val id: String,
    val name: String,
    /** 这一组要求的最少学分；0 表示自身没要求，学分全部算给上一级。 */
    val required: Double,
    val minCourses: Int?,
    val requirementText: String?,
    /** 这组实际通过的学分，不截断。 */
    val earned: Double,
    /** 算给上一级的学分：有要求时不超过要求。 */
    val counted: Double,
    val satisfied: Boolean,
    /** 教务报告里的已修学分和是否通过；报告没有这一组时为 null。 */
    val officialTaken: Double?,
    val officialPassed: Boolean?,
    val courses: List<DegreeCourse>,
    val children: List<DegreeNode>,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** 重算和教务对不上：这正是页面要提示的地方。 */
    val differsFromOfficial: Boolean
        get() = officialTaken != null && kotlin.math.abs(officialTaken - counted) > 0.01

    val flattened: List<DegreeNode> get() = listOf(this) + children.flatMap { it.flattened }
}

data class DegreeLooseCourse(
    val code: String,
    val name: String,
    val credit: Double,
    val passed: Boolean,
    val score: String?,
    val term: String?,
)

data class DegreeProgress(
    val planCode: String,
    val planName: String,
    val required: Double,
    val earned: Double,
    val counted: Double,
    val officialTaken: Double?,
    val officialEarned: Double?,
    val calculatedAt: String?,
    val notes: String,
    val nodes: List<DegreeNode>,
    /** 没归进方案的课：可能换了课程号、可能真是方案外，让用户和小岱判断。 */
    val unplaced: List<DegreeLooseCourse>,
    val gpaEstimate: Double?,
    val failedCourses: List<String>,
    val warnings: List<String>,
)

/** 成绩查询里的一门成绩。 */
data class DegreeGrade(
    val code: String,
    val name: String,
    val credit: Double,
    val score: String,
    val passed: Boolean,
    val gpa: Double?,
    val term: String,
)

/** 教务接口的原始行，解析全在 [DegreeBuilder]，方便用假数据测各种奇形怪状的树。 */
data class DegreeRaw(
    val planCode: String,
    val planName: String,
    val required: Double?,
    val notes: String,
    val groups: List<JsonObject>,
    val courses: List<JsonObject>,
    /** `queryXyzhbx.do` 的 data；拿不到为 null，树和成绩照样能算。 */
    val report: JsonObject?,
    val grades: List<DegreeGrade>,
)

/** 取数端口。 */
interface DegreeSource {
    suspend fun fetch(): DegreeRaw

    /** 教务的「重新计算」。只在用户点按钮时调。返回 false 表示等超时了。 */
    suspend fun recalculate(): Boolean
}

object DegreeBuilder {
    const val ROOT_PARENT = "-1"
    private const val UNKNOWN_GROUP = "_unknown"
    private val HTML_TAG = Regex("<[^>]+>")

    private class Draft(
        val id: String,
        var name: String,
        var required: Double,
        var minCourses: Int?,
        var requirementText: String?,
        val raw: JsonObject,
    ) {
        var officialTaken: Double? = null
        var officialPassed: Boolean? = null
        val courses = LinkedHashMap<String, DegreeCourse>()
        val children = ArrayList<Draft>()
    }

    private fun JsonObject.text(key: String) = this[key].safeStringOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.number(key: String) = this[key].safeDoubleOrNull()
    private fun keyOf(code: String, name: String) = code.trim().uppercase().ifEmpty { "NAME:${name.trim()}" }
    private fun plain(html: String?) = html?.replace(HTML_TAG, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseCourse(row: JsonObject): DegreeCourse {
        val status = when (row.text("ZT")) {
            "01" -> DegreeCourseStatus.PASSED
            "02" -> DegreeCourseStatus.FAILED
            null -> DegreeCourseStatus.NOT_TAKEN
            else -> if (row.text("ZT_DISPLAY")?.contains("未修") == true) DegreeCourseStatus.NOT_TAKEN else DegreeCourseStatus.OTHER
        }
        return DegreeCourse(
            code = row.text("KCH").orEmpty(),
            name = row.text("KCM").orEmpty(),
            credit = row.number("XF") ?: 0.0,
            status = status,
            statusText = row.text("ZT_DISPLAY").orEmpty(),
            score = row.text("CJ"),
            term = row.text("CJXNXQDM") ?: row.text("XKXNXQDM"),
            plannedTerm = row.text("JHXNXQDM") ?: row.text("XNXQ"),
            substitute = row.text("TDKCM"),
            raw = row,
        )
    }

    /**
     * @param confirmed `课程号 -> 节点 id`，用户确认抵扣的课。
     */
    fun build(raw: DegreeRaw, confirmed: Map<String, String> = emptyMap()): DegreeProgress {
        val warnings = ArrayList<String>()
        val drafts = LinkedHashMap<String, Draft>()
        raw.groups.forEach { g ->
            val id = g.text("KZH") ?: return@forEach
            drafts[id] = Draft(
                id = id,
                name = g.text("KZM") ?: g.text("KCLBDM_DISPLAY") ?: "未命名课组",
                required = g.number("ZSXDXF") ?: 0.0,
                minCourses = g.number("ZSXDMS")?.toInt(),
                requirementText = plain(g.text("XDYQ")),
                raw = g,
            )
        }

        // ── 连树。奇形怪状的方案也不能丢节点：父节点不存在的、成环的，都挂回顶层并记一笔 ──
        val roots = ArrayList<Draft>()
        raw.groups.forEach { g ->
            val draft = drafts[g.text("KZH") ?: return@forEach] ?: return@forEach
            val parentId = g.text("FKZH")
            val parent = parentId?.takeIf { it != ROOT_PARENT }?.let { drafts[it] }
            when {
                parent != null && parent !== draft -> parent.children += draft
                else -> {
                    if (parentId != null && parentId != ROOT_PARENT) warnings += "课组「${draft.name}」找不到上级，已放到顶层"
                    roots += draft
                }
            }
        }
        val reached = HashSet<String>()
        fun mark(d: Draft) {
            if (reached.add(d.id)) d.children.forEach(::mark)
        }
        roots.forEach(::mark)
        drafts.values.filter { it.id !in reached }.forEach { d ->
            if (d.id in reached) return@forEach
            warnings += "课组「${d.name}」的上下级成环，已放到顶层"
            // 拆环：从它的上级摘下来
            drafts.values.forEach { it.children.remove(d) }
            roots += d
            mark(d)
        }

        // ── 方案里的课 ──
        fun unknownGroup() = drafts.getOrPut(UNKNOWN_GROUP) {
            Draft(UNKNOWN_GROUP, "未归类课程", 0.0, null, null, JsonObject(emptyMap())).also {
                roots += it
                warnings += "有课程不属于任何课组，已放到「未归类课程」"
            }
        }
        raw.courses.forEach { row ->
            val course = parseCourse(row)
            val target = row.text("KZH")?.let { drafts[it] } ?: unknownGroup()
            target.courses[keyOf(course.code, course.name)] = course
        }

        // ── 教务报告：只补状态和官方数字，方案里没有的组和课（比如通识课）也收进来 ──
        val report = raw.report
        val top = report?.get("fanbx") as? JsonObject
        val calculatedAt = report?.text("calculatedTime")
        fun mergeReport(node: JsonObject, parent: Draft?) {
            val id = node.text("courseGroupId") ?: return
            val draft = drafts.getOrPut(id) {
                Draft(id, node.text("courseCategoryName") ?: "未命名课组", node.number("creditsRequired") ?: 0.0, null, null, JsonObject(emptyMap())).also {
                    if (parent != null) parent.children += it else roots += it
                    warnings += "教务报告里的「${it.name}」不在方案树中"
                }
            }
            draft.officialTaken = node.number("creditsTaken")
            draft.officialPassed = node.text("sftg")?.let { it == "1" }
            (node["data"] as? JsonArray)?.forEach { element ->
                val row = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                val course = parseCourse(row)
                val key = keyOf(course.code, course.name)
                val known = draft.courses[key]
                draft.courses[key] = course.copy(
                    credit = course.credit.takeIf { it > 0 } ?: known?.credit ?: 0.0,
                    plannedTerm = course.plannedTerm ?: known?.plannedTerm,
                )
            }
            (node["children"] as? JsonArray)?.forEach { child ->
                runCatching { child.jsonObject }.getOrNull()?.let { mergeReport(it, draft) }
            }
        }
        (top?.get("children") as? JsonArray)?.forEach { child ->
            runCatching { child.jsonObject }.getOrNull()?.let { mergeReport(it, null) }
        }

        // ── 成绩查询：同一门课多次成绩，有通过的算通过，否则看最新一次 ──
        val bestGrades = raw.grades
            .groupBy { keyOf(it.code, it.name) }
            .mapValues { (_, attempts) -> attempts.firstOrNull { it.passed } ?: attempts.maxBy { it.term } }

        val allDrafts = { drafts.values.toList() }
        fun applyGrade(draft: Draft, key: String, g: DegreeGrade, byName: Boolean) {
            val known = draft.courses.getValue(key)
            if (known.status == DegreeCourseStatus.PASSED) return
            draft.courses[key] = known.copy(
                status = if (g.passed) DegreeCourseStatus.PASSED else DegreeCourseStatus.FAILED,
                statusText = if (g.passed) "通过" else "未通过",
                score = g.score,
                term = g.term,
                credit = known.credit.takeIf { it > 0 } ?: g.credit,
                matchedByName = byName,
            )
        }
        val placedGrades = HashSet<String>()
        bestGrades.forEach { (key, g) ->
            val byCode = allDrafts().firstOrNull { key in it.courses }
            if (byCode != null) {
                applyGrade(byCode, key, g, byName = false)
                placedGrades += key
                return@forEach
            }
            allDrafts().forEach { d ->
                val hit = d.courses.entries.firstOrNull { it.value.name == g.name.trim() && it.value.name.isNotEmpty() }
                if (hit != null && key !in placedGrades) {
                    applyGrade(d, hit.key, g, byName = true)
                    placedGrades += key
                }
            }
        }

        // ── 还没归位的：报告的方案外课 + 成绩里对不上的 ──
        val loose = LinkedHashMap<String, DegreeLooseCourse>()
        (report?.get("fawbx") as? JsonArray)?.forEach { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val code = o.text("courseId").orEmpty()
            val name = o.text("courseName").orEmpty()
            loose[keyOf(code, name)] = DegreeLooseCourse(
                code, name, o.number("credit") ?: 0.0, o.text("passed") == "true", o.text("point"), o.text("pointSchoolYearTermCode"),
            )
        }
        bestGrades.filterKeys { it !in placedGrades }.forEach { (key, g) ->
            loose[key] = DegreeLooseCourse(g.code, g.name, g.credit, g.passed, g.score, g.term)
        }
        // 已经归进树里的（报告把它放进了课组，成绩又查到）不再算没归位
        val inTree = drafts.values.flatMap { it.courses.entries.filter { e -> e.value.status == DegreeCourseStatus.PASSED }.map { e -> e.key } }.toSet()
        loose.keys.removeAll(inTree)

        // ── 通识课：方案树常常不列具体课（海量可选），按课程号前缀放进对应课组 ──
        fun genEdGroup(code: String): Draft? {
            val upper = code.uppercase()
            val word = when {
                upper.startsWith("CORE") -> "核心"
                upper.startsWith("GNED") -> "选修"
                else -> return null
            }
            return drafts.values.firstOrNull { "通识" in it.name && word in it.name }
        }
        loose.entries.toList().forEach { (key, c) ->
            val group = genEdGroup(c.code) ?: return@forEach
            group.courses[key] = DegreeCourse(
                c.code, c.name, c.credit,
                if (c.passed) DegreeCourseStatus.PASSED else DegreeCourseStatus.FAILED,
                if (c.passed) "通过" else "未通过", c.score, c.term,
            )
            loose.remove(key)
        }

        // ── 用户确认的抵扣 ──
        confirmed.forEach { (code, nodeId) ->
            val key = code.trim().uppercase()
            val c = loose[key] ?: return@forEach
            val target = drafts[nodeId] ?: return@forEach
            if (!c.passed) return@forEach
            target.courses[key] = DegreeCourse(
                c.code, c.name, c.credit, DegreeCourseStatus.PASSED, "已确认抵扣（未经教务确认）", c.score, c.term, userConfirmed = true,
            )
            loose.remove(key)
        }

        // ── 重算。教务的问题是子组学分先截断到自身要求，要求为 0 的模块就全归零（专业选修 0/11）──
        val countedOnce = HashSet<String>()
        fun finish(d: Draft): DegreeNode {
            val children = d.children.map(::finish)
            val passed = d.courses.filter { (key, c) -> c.status == DegreeCourseStatus.PASSED && countedOnce.add(key) }
            val own = passed.values.sumOf { it.credit }
            val earned = own + children.sumOf { it.counted }
            val counted = if (d.required > 0) minOf(earned, d.required) else earned
            val enoughCredits = d.required <= 0 || earned + 0.001 >= d.required
            val enoughCourses = d.minCourses?.let { passed.size >= it } ?: true
            return DegreeNode(
                id = d.id,
                name = d.name,
                required = d.required,
                minCourses = d.minCourses,
                requirementText = d.requirementText,
                earned = earned,
                counted = counted,
                satisfied = enoughCredits && enoughCourses && children.all { it.satisfied },
                officialTaken = d.officialTaken,
                officialPassed = d.officialPassed,
                courses = d.courses.values.toList(),
                children = children,
                raw = d.raw,
            )
        }
        val nodes = roots.map(::finish)
        val earned = nodes.sumOf { it.counted }
        val required = raw.required ?: top?.number("creditsRequired") ?: nodes.sumOf { it.required }

        val graded = bestGrades.values.filter { it.gpa != null && it.credit > 0 }
        val creditSum = graded.sumOf { it.credit }
        return DegreeProgress(
            planCode = raw.planCode,
            planName = raw.planName,
            required = required,
            earned = earned,
            counted = if (required > 0) minOf(earned, required) else earned,
            officialTaken = top?.number("creditsTaken"),
            officialEarned = top?.number("creditsEarned"),
            calculatedAt = calculatedAt,
            notes = plain(raw.notes).orEmpty(),
            nodes = nodes,
            unplaced = loose.values.toList(),
            gpaEstimate = if (creditSum > 0) graded.sumOf { it.gpa!! * it.credit } / creditSum else null,
            failedCourses = bestGrades.values.filter { !it.passed }.map { it.name },
            warnings = warnings,
        )
    }
}
