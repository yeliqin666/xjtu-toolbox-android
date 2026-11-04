package com.xjtu.toolbox.degree

import com.xjtu.toolbox.util.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 字段形状照抓包写，数据全是编的。真实响应别进仓库，里面有学号、姓名和成绩。 */
class DegreeBuilderTest {
    private fun obj(json: String) = AppJson.parseToJsonElement(json).jsonObject
    private fun group(id: String, parent: String, name: String, min: Double?, extra: String = "") =
        obj("""{"KZH":"$id","FKZH":"$parent","KZM":"$name","ZSXDXF":${min ?: "null"}$extra}""")
    private fun course(group: String, code: String, name: String, credit: Double) =
        obj("""{"KZH":"$group","KCH":"$code","KCM":"$name","XF":"$credit"}""")
    private fun grade(code: String, name: String, credit: Double, passed: Boolean = true, score: String = "90", term: String = "2025-2026-1", gpa: Double? = 4.0) =
        DegreeGrade(code, name, credit, score, passed, gpa, term)
    private fun raw(
        groups: List<JsonObject>,
        courses: List<JsonObject> = emptyList(),
        grades: List<DegreeGrade> = emptyList(),
        report: JsonObject? = null,
        required: Double? = 20.0,
    ) = DegreeRaw("plan", "测试方案", required, "", groups, courses, report, grades)
    private fun DegreeProgress.node(id: String) = nodes.flatMap { it.flattened }.first { it.id == id }

    /** 复现教务的 bug：模块要求都是 0，父节点要求 11。 */
    private val electives = raw(
        groups = listOf(
            group("e", "-1", "专业选修课程", 11.0),
            group("m1", "e", "光电模块", 0.0),
            group("m2", "e", "理论模块", 0.0),
        ),
        courses = listOf(course("m1", "A1", "光电子学", 4.0), course("m1", "A2", "信息光学", 3.0), course("m2", "B1", "量子场论", 4.0), course("m2", "B2", "宇宙学", 2.0)),
        grades = listOf(grade("A1", "光电子学", 4.0), grade("A2", "信息光学", 3.0), grade("B1", "量子场论", 4.0)),
    )

    @Test fun zeroRequirementModulesStillCountForTheParent() {
        val e = DegreeBuilder.build(electives).node("e")
        assertEquals(11.0, e.earned)
        assertTrue(e.satisfied)
        assertEquals(7.0, DegreeBuilder.build(electives).node("m1").counted)
    }

    @Test fun officialNumbersStayVisibleWhenTheyDiffer() {
        val report = obj("""{"calculatedTime":"2026-11-04 17:08:11","fanbx":{"creditsRequired":150,"creditsTaken":0,"creditsEarned":11,
            "children":[{"courseGroupId":"e","creditsRequired":11,"creditsTaken":0,"sftg":"0","children":[]}]}}""")
        val p = DegreeBuilder.build(electives.copy(report = report))
        val e = p.node("e")
        assertEquals(0.0, e.officialTaken)
        assertEquals(false, e.officialPassed)
        assertTrue(e.differsFromOfficial)
        assertEquals("2026-11-04 17:08:11", p.calculatedAt)
    }

    @Test fun positiveRequirementsCapWhatReachesTheParentButKeepTheRealTotal() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("p", "-1", "平台", 10.0), group("c", "p", "科研", 4.0)),
            courses = listOf(course("c", "C1", "甲", 3.0), course("c", "C2", "乙", 3.0)),
            grades = listOf(grade("C1", "甲", 3.0), grade("C2", "乙", 3.0)),
        ))
        assertEquals(6.0, p.node("c").earned)
        assertEquals(4.0, p.node("c").counted)
        assertEquals(4.0, p.node("p").earned)
        assertFalse(p.node("p").satisfied)
    }

    @Test fun orphansAndCyclesNeverLoseNodes() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(
                group("orphan", "missing", "孤儿课组", 1.0),
                group("x", "y", "环甲", 0.0),
                group("y", "x", "环乙", 0.0),
                group("self", "self", "自指", 0.0),
            ),
            courses = listOf(course("x", "X1", "环里的课", 2.0), course("nope", "N1", "没有课组的课", 1.0)),
            grades = listOf(grade("X1", "环里的课", 2.0), grade("N1", "没有课组的课", 1.0)),
        ))
        val names = p.nodes.flatMap { it.flattened }.map { it.name }
        assertTrue(listOf("孤儿课组", "环甲", "环乙", "自指", "未归类课程").all { it in names }, names.toString())
        assertEquals(2.0, p.node("x").earned + p.node("y").earned - p.node("y").children.sumOf { it.counted } - p.node("x").children.sumOf { it.counted })
        assertTrue(p.warnings.isNotEmpty())
    }

    @Test fun passedRetakeWinsAndFailureIsOnlyListedWhenNeverPassed() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("g", "-1", "必修", 8.0)),
            courses = listOf(course("g", "M1", "数学", 4.0), course("g", "M2", "力学", 4.0)),
            grades = listOf(
                grade("M1", "数学", 4.0, passed = false, score = "50", term = "2023-2024-1"),
                grade("M1", "数学", 4.0, passed = true, score = "70", term = "2024-2025-1"),
                grade("M2", "力学", 4.0, passed = false, score = "40"),
            ),
        ))
        assertEquals(4.0, p.node("g").earned)
        assertEquals(listOf("力学"), p.failedCourses)
    }

    @Test fun sameCourseInTwoGroupsCountsOnce() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("a", "-1", "甲组", 0.0), group("b", "-1", "乙组", 0.0)),
            courses = listOf(course("a", "S1", "同一门", 2.0), course("b", "S1", "同一门", 2.0)),
            report = obj("""{"fanbx":{"children":[
                {"courseGroupId":"a","data":[{"KCH":"S1","KCM":"同一门","XF":2,"ZT":"01"}]},
                {"courseGroupId":"b","data":[{"KCH":"S1","KCM":"同一门","XF":2,"ZT":"01"}]}]}}"""),
        ))
        assertEquals(2.0, p.earned)
    }

    @Test fun renamedCodeMatchesByExactNameAndIsMarked() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("g", "-1", "核心", 4.0)),
            courses = listOf(course("g", "0HYS303500", "固体物理", 4.0)),
            grades = listOf(grade("PHYS303500", "固体物理", 4.0)),
        ))
        val c = p.node("g").courses.single()
        assertTrue(c.matchedByName)
        assertEquals(DegreeCourseStatus.PASSED, c.status)
        assertTrue(p.unplaced.isEmpty())
    }

    @Test fun unmatchedCoursesWaitForTheUserAndConfirmationIsMarked() {
        val data = raw(
            groups = listOf(group("en", "-1", "大学英语", 2.0)),
            courses = listOf(course("en", "E1", "大学英语Ⅰ", 2.0)),
            report = obj("""{"fawbx":[{"courseId":"ENGL2","courseName":"通用学术英语","credit":2.0,"point":"83","passed":true,"studentId":"000"}],"fanbx":{"children":[]}}"""),
        )
        val before = DegreeBuilder.build(data)
        assertEquals(listOf("通用学术英语"), before.unplaced.map { it.name })
        assertFalse(before.node("en").satisfied)

        val after = DegreeBuilder.build(data, mapOf("ENGL2" to "en"))
        assertTrue(after.unplaced.isEmpty())
        assertTrue(after.node("en").satisfied)
        assertTrue(after.node("en").courses.first { it.code == "ENGL2" }.userConfirmed)
    }

    @Test fun confirmingAFailedOrUnknownCourseChangesNothing() {
        val data = raw(
            groups = listOf(group("en", "-1", "大学英语", 2.0)),
            grades = listOf(grade("F1", "挂了的课", 2.0, passed = false)),
        )
        val p = DegreeBuilder.build(data, mapOf("F1" to "en", "NOPE" to "en", "F1 " to "missing"))
        assertEquals(0.0, p.node("en").earned)
        assertEquals(listOf("挂了的课"), p.unplaced.map { it.name })
    }

    @Test fun generalEducationGoesToItsGroupByCodePrefix() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("core", "-1", "基础通识类核心课", 2.0), group("sel", "-1", "基础通识类选修课", 2.0)),
            grades = listOf(grade("CORE1", "文化传承", 2.0), grade("GNED1", "信息哲学", 2.0)),
        ))
        assertEquals(2.0, p.node("core").earned)
        assertEquals(2.0, p.node("sel").earned)
        assertTrue(p.unplaced.isEmpty())
    }

    @Test fun minCoursesIsChecked() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("pe", "-1", "体育目标教学", 0.0, ""","ZSXDMS":1""")),
            courses = listOf(course("pe", "P1", "游泳", 0.0)),
        ))
        assertFalse(p.node("pe").satisfied)
    }

    @Test fun letterGradesAreKeptAsWritten() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("g", "-1", "课", 1.0)),
            courses = listOf(course("g", "L1", "导读", 1.0)),
            grades = listOf(grade("L1", "导读", 1.0, score = "B＋")),
        ))
        assertEquals("B＋", p.node("g").courses.single().score)
    }

    @Test fun overviewKeepsEveryNodeAndNoIdentity() {
        val report = obj("""{"fawbx":[{"studentId":"2233000000","courseId":"Z1","courseName":"方案外","credit":1,"passed":true}],"fanbx":{"children":[]}}""")
        val json = DegreeAgentJson.overview(DegreeBuilder.build(electives.copy(report = report)))
        val root = obj(json)
        val top = root["nodes"] as JsonArray
        assertEquals(1, top.size)
        assertTrue("光电模块" in json && "理论模块" in json)
        assertFalse("2233000000" in json)
        assertEquals(0, (root["rules"] as JsonArray).size)
    }

    @Test fun detailPassesOddFieldsThroughButStripsPrivateOnes() {
        val p = DegreeBuilder.build(raw(
            groups = listOf(group("g", "-1", "奇怪的组", 1.0, ""","WEIRD":"模型能读懂","XH":"2233000000","CZRXM":"某老师","EMPTY":"" """)),
            courses = listOf(obj("""{"KZH":"g","KCH":"K1","KCM":"课","XF":"1.0","ODD":"也留着","XM":"某同学"}""")),
        ))
        val json = assertNotNull(DegreeAgentJson.detail(p, "奇怪"))
        assertTrue("模型能读懂" in json && "也留着" in json)
        assertFalse("2233000000" in json || "某老师" in json || "某同学" in json || "EMPTY" in json)
        assertEquals(null, DegreeAgentJson.detail(p, "不存在的组"))
    }
}
