package com.xjtu.toolbox.jwapp

import android.util.Log
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import okhttp3.FormBody
import okhttp3.Request

private const val TAG = "PyfaApi"

// ── 数据类 ──────────────────────────────

data class PyfaSummary(
    val pyfadm: String,
    val pyfamc: String,
    val totalRequired: Double,
    val completedCredits: Double,
    val studentId: String,
    val studentName: String,
    val major: String,
    val college: String,
    val grade: String,
    val className: String,
)

data class CourseGroupNode(
    val kzh: String,
    val parentKzh: String,
    val name: String,
    val kclbdm: String,
    val totalCredits: Double,
    val minCredits: Double,
    val courseCount: Int,
    val children: MutableList<CourseGroupNode> = mutableListOf(),
    var courses: List<PlanCourse> = emptyList(),
)

data class PlanCourse(
    val kch: String,
    val name: String,
    val kzh: String,
    val groupName: String,
    val credits: Double,
    val requiredFlag: String,
    val suggestedSemester: Int,
    val termCode: String,
    val termDisplay: String,
    val college: String,
    val examType: String,
    val totalHours: Double,
)

data class CurriculumProgress(
    val summary: PyfaSummary,
    val groupTree: List<CourseGroupNode>,
    val allCourses: List<PlanCourse>,
    val groupCreditMap: Map<String, GroupCreditStatus>,
    val insufficientGroups: List<GroupCreditStatus>,
    val outOfPlanCourses: List<ScoreItem>,
)

data class GroupCreditStatus(
    val group: CourseGroupNode,
    val completedCredits: Double,
    val cappedCredits: Double,
    val requiredCredits: Double,
    val isOpen: Boolean,
    val matchedScores: List<ScoreItem>,
    val depth: Int,
)

// ── 课组树辅助 ──────────────────────────────

/** 课组树展平为 KZH → Node */
fun flattenGroupTree(roots: List<CourseGroupNode>): Map<String, CourseGroupNode> {
    val map = mutableMapOf<String, CourseGroupNode>()
    fun walk(node: CourseGroupNode) {
        map[node.kzh] = node
        node.children.forEach { walk(it) }
    }
    roots.forEach { walk(it) }
    return map
}

/** 课组祖先链是否含指定 KCLBDM */
private fun hasAncestorWithKclbdm(
    kzh: String,
    target: String,
    nodeMap: Map<String, CourseGroupNode>,
): Boolean {
    var cur = nodeMap[kzh]
    while (cur != null) {
        if (cur.kclbdm == target) return true
        if (cur.parentKzh == "-1") break
        cur = nodeMap[cur.parentKzh]
    }
    return false
}

/** 成绩是否属于某个开放选课组（KCLBDM 或课程号前缀匹配） */
private fun matchesOpenGroup(score: ScoreItem, kclbdm: String): Boolean {
    if (score.courseCategory == kclbdm) return true
    val code = score.courseCode?.uppercase() ?: return false
    return when (kclbdm) {
        "基础通识类核心课" -> code.startsWith("CORE")
        "基础通识类选修课" -> code.startsWith("GNED")
        "体育类" -> code.startsWith("PE") || code.startsWith("PHED")
        else -> false
    }
}

// ── 课程五分类 ──────────────────────────────

/**
 * 培养方案感知分类（优先级）：
 * 1. CORE 前缀 → 通核
 * 2. GNED 前缀 → 通选
 * 3. KCLBDM == "专业选修课程" → 专选
 * 4. 方案课组祖先链含 "专业选修课程" → 专选
 * 5. 在方案固定课表或开放组 → 核心
 * 6. 其余 → 方案外
 */
fun classifyCourse(
    score: ScoreItem,
    planCourseByKch: Map<String, PlanCourse>,
    planCourseNames: Set<String>,
    nodeMap: Map<String, CourseGroupNode>,
    openGroupNodes: List<CourseGroupNode>,
): CourseGroup {
    val code = score.courseCode?.uppercase()

    // 1-2. 课程号前缀
    if (code != null && code.startsWith("CORE")) return CourseGroup.GEN_CORE
    if (code != null && code.startsWith("GNED")) return CourseGroup.GEN_ELECTIVE

    // 3. KCLBDM 精确匹配
    if (score.courseCategory == "专业选修课程") return CourseGroup.MAJOR_ELECTIVE

    // 4. 方案课表中查找 → 祖先链判定专选
    val planCourse = (code?.let { planCourseByKch[it] })
        ?: planCourseByKch.values.find {
            CjcxApi.normalizeName(it.name) == CjcxApi.normalizeName(score.courseName)
        }
    if (planCourse != null && hasAncestorWithKclbdm(planCourse.kzh, "专业选修课程", nodeMap)) {
        return CourseGroup.MAJOR_ELECTIVE
    }
    for (node in openGroupNodes) {
        if (matchesOpenGroup(score, node.kclbdm)
            && hasAncestorWithKclbdm(node.kzh, "专业选修课程", nodeMap)
        ) {
            return CourseGroup.MAJOR_ELECTIVE
        }
    }

    // 5. 方案内 → 核心
    val inFixedPlan = planCourse != null
        || CjcxApi.normalizeName(score.courseName) in planCourseNames
    if (inFixedPlan || openGroupNodes.any { matchesOpenGroup(score, it.kclbdm) }) {
        return CourseGroup.CORE
    }

    // 6. 方案外
    return CourseGroup.OUT_OF_PLAN
}

/**
 * 批量分类 + 开放组学分上限：
 * - minCredits=0 → 纯开放组匹配的课程降级为方案外
 * - minCredits>0 → 超额课程降级为方案外
 */
fun classifyCoursesWithCaps(
    allScores: List<ScoreItem>,
    planCourseByKch: Map<String, PlanCourse>,
    planCourseNames: Set<String>,
    nodeMap: Map<String, CourseGroupNode>,
    openGroupNodes: List<CourseGroupNode>,
): List<ScoreItem> {
    val result = allScores.map { s ->
        s.copy(courseGroup = classifyCourse(s, planCourseByKch, planCourseNames, nodeMap, openGroupNodes))
    }.toMutableList()

    for (openNode in openGroupNodes) {
        // 仅收集"纯开放组匹配"的 CORE 课程（排除方案固定课表中的）
        val indices = result.indices.filter { i ->
            val s = result[i]
            s.courseGroup == CourseGroup.CORE
                && matchesOpenGroup(s, openNode.kclbdm)
                && s.courseCode?.uppercase()?.let { planCourseByKch[it] } == null
                && CjcxApi.normalizeName(s.courseName) !in planCourseNames
        }
        if (indices.isEmpty()) continue

        if (openNode.minCredits <= 0) {
            // 无学分要求 → 全部降级
            indices.forEach { i -> result[i] = result[i].copy(courseGroup = CourseGroup.OUT_OF_PLAN) }
        } else {
            // 按顺序填充至上限
            var acc = 0.0
            for (i in indices) {
                if (acc >= openNode.minCredits) {
                    result[i] = result[i].copy(courseGroup = CourseGroup.OUT_OF_PLAN)
                } else {
                    acc += result[i].coursePoint
                }
            }
        }
    }
    return result
}

// ── API 实现 ──────────────────────────────

class PyfaApi(private val login: JwxtLogin) {

    private val basePersonal = "https://jwxt.xjtu.edu.cn/jwapp/sys/xsfacx"
    private val basePyfa = "https://jwxt.xjtu.edu.cn/jwapp/sys/jwpubapp"

    private fun ensureSession() {
        try {
            login.client.newCall(
                Request.Builder().url("$basePersonal/*default/index.do").get().build()
            ).execute().close()
            login.client.newCall(
                Request.Builder().url("$basePyfa/*default/index.do").get().build()
            ).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "session init: ${e.message}")
        }
    }

    /** 个人培养方案概要 (grpyfacx.do) */
    fun getPersonalPlan(): PyfaSummary {
        ensureSession()
        val request = Request.Builder()
            .url("$basePersonal/modules/pyfacxepg/grpyfacx.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().build())
            .build()

        val body = login.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("grpyfacx.do HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("空响应")
        }
        val obj = JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("datas").getAsJsonObject("grpyfacx")
            .getAsJsonArray("rows")
            .also { if (it.size() == 0) throw RuntimeException("无培养方案数据") }
            .first().asJsonObject

        return PyfaSummary(
            pyfadm = obj.get("PYFADM").safeString(),
            pyfamc = obj.get("PYFAMC").safeString(),
            totalRequired = obj.get("ZSYQXF").safeDouble(),
            completedCredits = obj.get("YWCXF").safeDouble(),
            studentId = obj.get("XH").safeString(),
            studentName = obj.get("XM").safeString(),
            major = obj.get("ZYDM_DISPLAY").safeString(),
            college = obj.get("YXDM_DISPLAY").safeString(),
            grade = obj.get("XZNJ_DISPLAY").safeString(),
            className = obj.get("BJDM_DISPLAY").safeString(),
        )
    }

    /** 培养方案课组树 (kzcx.do) */
    fun getCourseGroups(pyfadm: String): List<CourseGroupNode> {
        val request = Request.Builder()
            .url("$basePyfa/modules/pyfa/kzcx.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().add("PYFADM", pyfadm).build())
            .build()

        val body = login.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("kzcx.do HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("空响应")
        }
        val rows = JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("datas").getAsJsonObject("kzcx").getAsJsonArray("rows")

        val flatNodes = rows.map { el ->
            val o = el.asJsonObject
            CourseGroupNode(
                kzh = o.get("KZH").safeString(),
                parentKzh = o.get("FKZH").safeString(),
                name = o.get("KZM").safeString(),
                kclbdm = o.get("KCLBDM_DISPLAY").safeString(),
                totalCredits = o.get("KCZXF").safeDouble(),
                minCredits = o.get("ZSXDXF").safeDouble(),
                courseCount = o.get("KCZMS").safeInt(),
            )
        }
        val nodeMap = flatNodes.associateBy { it.kzh }
        val roots = mutableListOf<CourseGroupNode>()
        for (node in flatNodes) {
            if (node.parentKzh == "-1") roots.add(node)
            else nodeMap[node.parentKzh]?.children?.add(node)
        }
        Log.d(TAG, "课组树: ${roots.size} 根, ${flatNodes.size} 节点")
        return roots
    }

    /** 方案内课程列表 (kzkccx.do) */
    fun getPlanCourses(pyfadm: String): List<PlanCourse> {
        val request = Request.Builder()
            .url("$basePyfa/modules/pyfa/kzkccx.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().add("PYFADM", pyfadm).build())
            .build()

        val body = login.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("kzkccx.do HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("空响应")
        }
        val rows = JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("datas").getAsJsonObject("kzkccx").getAsJsonArray("rows")

        return rows.map { el ->
            val o = el.asJsonObject
            PlanCourse(
                kch = o.get("KCH").safeString(),
                name = o.get("KCM").safeString(),
                kzh = o.get("KZH").safeString(),
                groupName = o.get("KZM").safeString(),
                credits = o.get("XF").safeDouble(),
                requiredFlag = o.get("KCXZDM_DISPLAY").safeString(),
                suggestedSemester = o.get("XDXQ").safeInt(),
                termCode = o.get("XNXQ").safeString(),
                termDisplay = o.get("XNXQ_DISPLAY").safeString(),
                college = o.get("KKDWDM_DISPLAY").safeString(),
                examType = o.get("KSLXDM_DISPLAY").safeString(),
                totalHours = o.get("XS").safeDouble(),
            )
        }.also { Log.d(TAG, "方案课程: ${it.size} 门") }
    }

    /**
     * 构建培养进度：
     * 1. 固定课表组按课程名匹配，开放组按 KCLBDM/前缀匹配
     * 2. 子节点学分 cap at minCredits 后上传父节点
     * 3. 学分不足仅展示最底层可操作组
     */
    fun buildProgress(
        allScores: List<ScoreItem>,
        @Suppress("UNUSED_PARAMETER") currentTermCode: String,
    ): CurriculumProgress {
        val summary = getPersonalPlan()
        val groupTree = getCourseGroups(summary.pyfadm)
        val planCourses = getPlanCourses(summary.pyfadm)

        // 挂载课程到叶子节点
        val coursesByGroup = planCourses.groupBy { it.kzh }
        fun attach(nodes: List<CourseGroupNode>) {
            for (n in nodes) { n.courses = coursesByGroup[n.kzh] ?: emptyList(); attach(n.children) }
        }
        attach(groupTree)

        // 分类辅助数据
        val nodeMap = flattenGroupTree(groupTree)
        val planCourseByKch = planCourses.associateBy { it.kch }
        val planCourseNames = planCourses.map { CjcxApi.normalizeName(it.name) }.toSet()
        val openGroupNodes = nodeMap.values.filter { it.courseCount == 0 && it.children.isEmpty() }

        // 批量分类 + 开放组学分上限
        val classified = classifyCoursesWithCaps(
            allScores, planCourseByKch, planCourseNames, nodeMap, openGroupNodes
        )
        val passed = classified.filter { it.passFlag || (it.scoreValue != null && it.scoreValue >= 60.0) }
        val outOfPlan = classified.filter { it.courseGroup == CourseGroup.OUT_OF_PLAN }

        // 递归计算各课组学分
        val creditMap = mutableMapOf<String, GroupCreditStatus>()

        fun compute(node: CourseGroupNode, depth: Int): Pair<Double, Double> {
            if (node.children.isEmpty()) {
                // 叶子：开放组按 KCLBDM 匹配，固定组按课程名匹配
                val matched = if (node.courseCount == 0) {
                    passed.filter { matchesOpenGroup(it, node.kclbdm) }
                } else {
                    val names = node.courses.map { CjcxApi.normalizeName(it.name) }.toSet()
                    passed.filter { CjcxApi.normalizeName(it.courseName) in names }
                }
                val raw = matched.sumOf { it.coursePoint }
                val capped = if (node.minCredits > 0) minOf(raw, node.minCredits) else raw
                creditMap[node.kzh] = GroupCreditStatus(node, raw, capped, node.minCredits, node.courseCount == 0, matched, depth)
                return raw to capped
            }

            // 父节点：聚合子节点
            var rawTotal = 0.0; var cappedTotal = 0.0
            for (child in node.children) {
                val (r, c) = compute(child, depth + 1)
                rawTotal += r; cappedTotal += c
            }
            // 父节点直属课程（罕见）
            if (node.courses.isNotEmpty()) {
                val names = node.courses.map { CjcxApi.normalizeName(it.name) }.toSet()
                val extra = passed.filter { CjcxApi.normalizeName(it.courseName) in names }.sumOf { it.coursePoint }
                rawTotal += extra; cappedTotal += extra
            }
            val parentCapped = if (node.minCredits > 0) minOf(cappedTotal, node.minCredits) else cappedTotal
            creditMap[node.kzh] = GroupCreditStatus(node, rawTotal, parentCapped, node.minCredits, false, emptyList(), depth)
            return rawTotal to parentCapped
        }

        for (root in groupTree) compute(root, 0)

        // 学分不足：仅展示最底层可操作组
        val allInsufficient = creditMap.values
            .filter { it.requiredCredits > 0 && it.cappedCredits < it.requiredCredits }
            .associateBy { it.group.kzh }

        fun hasInsufficientChild(node: CourseGroupNode): Boolean =
            node.children.any { it.kzh in allInsufficient || hasInsufficientChild(it) }

        val actionable = allInsufficient.values
            .filter { !hasInsufficientChild(it.group) }
            .sortedWith(compareBy<GroupCreditStatus> { it.depth }
                .thenByDescending { it.requiredCredits - it.completedCredits })

        Log.d(TAG, "进度: 通过${passed.size}门, 不足${actionable.size}组, 方案外${outOfPlan.size}门")
        return CurriculumProgress(summary, groupTree, planCourses, creditMap, actionable, outOfPlan)
    }
}
