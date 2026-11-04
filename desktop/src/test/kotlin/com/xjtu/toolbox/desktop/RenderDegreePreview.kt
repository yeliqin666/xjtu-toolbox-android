package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.xjtu.toolbox.degree.DegreeGrade
import com.xjtu.toolbox.degree.DegreePlanPage
import com.xjtu.toolbox.degree.DegreePlanSummary
import com.xjtu.toolbox.degree.DegreeRaw
import com.xjtu.toolbox.degree.DegreeScreen
import com.xjtu.toolbox.degree.DegreeSource
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.util.AppJson
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 学业进度页离线预览。课程和成绩都是编的。 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "build/degree-preview").apply { mkdirs() }
    fun obj(json: String) = AppJson.parseToJsonElement(json).jsonObject
    fun group(id: String, parent: String, name: String, min: Double) = obj("""{"KZH":"$id","FKZH":"$parent","KZM":"$name","ZSXDXF":$min}""")
    fun course(g: String, code: String, name: String, credit: Double) = obj("""{"KZH":"$g","KCH":"$code","KCM":"$name","XF":"$credit","XNXQ":"2025-2026-1"}""")
    val groups = listOf(
        group("t", "-1", "课程教学", 122.0),
        group("c", "t", "专业核心课程", 24.0),
        group("e", "t", "专业选修课程", 11.0),
        group("m1", "e", "光电信息科学模块", 0.0),
        group("m2", "e", "理论物理模块", 0.0),
        group("b", "t", "基础科学类课程", 54.0),
        group("b1", "b", "数学基础课程", 20.0),
        group("b2", "b", "物理学基础课程", 23.0),
        group("p", "-1", "集中实践类课程", 20.0),
        group("p1", "p", "实践训练", 16.0),
        group("p2", "p", "科研训练课程", 4.0),
        group("x", "-1", "课外实践", 8.0),
    )
    val courses = listOf(
        course("c", "C1", "量子力学", 4.0), course("c", "C2", "电动力学", 4.0), course("c", "C3", "创新物理实验", 2.0),
        course("m1", "A1", "光电子学", 4.0), course("m1", "A2", "信息光学", 3.0), course("m1", "A3", "非线性光学", 2.0),
        course("m2", "B1", "核物理导论", 4.0), course("m2", "B2", "宇宙学", 2.0),
        course("b1", "D1", "高等数学", 20.0), course("b2", "E1", "普通物理", 15.0), course("b2", "E2", "力学", 8.0),
        course("p1", "P1", "军事训练", 6.0), course("p2", "R1", "科研训练", 6.0), course("x", "X1", "社会实践", 8.0),
    )
    val grades = listOf(
        DegreeGrade("C1", "量子力学", 4.0, "91", true, 4.0, "2025-2026-1"),
        DegreeGrade("C2", "电动力学", 4.0, "79", true, 3.0, "2024-2025-2"),
        DegreeGrade("A1", "光电子学", 4.0, "97", true, 4.3, "2025-2026-1"),
        DegreeGrade("A2", "信息光学", 3.0, "94", true, 4.0, "2025-2026-2"),
        DegreeGrade("B1", "核物理导论", 4.0, "96", true, 4.3, "2025-2026-2"),
        DegreeGrade("D1", "高等数学", 20.0, "90", true, 4.0, "2023-2024-1"),
        DegreeGrade("E1", "普通物理", 15.0, "88", true, 3.7, "2023-2024-2"),
        DegreeGrade("P1", "军事训练", 6.0, "89", true, 3.7, "2023-2024-1"),
        DegreeGrade("R1", "科研训练", 6.0, "优", true, 4.0, "2024-2025-1"),
        DegreeGrade("Z1", "通用学术英语", 2.0, "83", true, 3.3, "2023-2024-1"),
        DegreeGrade("Z2", "经典力学", 3.0, "85", true, 3.7, "2023-2024-1"),
    )
    val report = obj("""{"calculatedTime":"2026-11-04 17:08:11","fanbx":{"creditsRequired":150,"creditsTaken":75,"creditsEarned":86,
        "children":[{"courseGroupId":"t","creditsRequired":122,"creditsTaken":43,"sftg":"0","children":[
            {"courseGroupId":"e","creditsRequired":11,"creditsTaken":0,"sftg":"0","children":[]}]}]}}""")
    val current = listOf(
        com.xjtu.toolbox.degree.DegreeCurrentCourse("C3", "创新物理实验", "2026-2027-1"),
        com.xjtu.toolbox.degree.DegreeCurrentCourse("E2", "力学", "2026-2027-1"),
        com.xjtu.toolbox.degree.DegreeCurrentCourse("Q9", "方案外的选修课", "2026-2027-1"),
    )
    val mine = DegreeRaw("mine", "2023级0940物理学（国家拔尖计划）主修培养方案", 150.0, "", groups, courses, report, grades, grade = "2023", currentCourses = current)
    val plans = listOf(
        DegreePlanSummary("p1", "2023级0903应用物理学（理论物理）主修培养方案", "物理学院", "2023"),
        DegreePlanSummary("p2", "2023级0922光电信息科学与工程主修培养方案", "物理学院", "2023"),
        DegreePlanSummary("p3", "2023级0105车辆工程主修培养方案", "机械工程学院", "2023"),
    )
    val source = object : DegreeSource {
        override suspend fun fetch() = mine
        override suspend fun recalculate() = true
        override suspend fun searchPlans(keyword: String, grade: String) = DegreePlanPage(plans, plans.size)
        // 别的方案：课程不一样，成绩还是这个人的，一部分对不上
        override suspend fun fetchPlan(plan: DegreePlanSummary) = mine.copy(
            planCode = plan.code, planName = plan.name, required = 165.0, report = null,
            courses = courses.filter { it["KCH"].toString().trim('"') !in setOf("A1", "A2", "B1", "C2") },
        )
    }
    fun find(root: SemanticsNode, text: String): SemanticsNode? =
        if (root.config.getOrNull(SemanticsActions.OnClick) != null &&
            root.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text }) root
        else root.children.firstNotNullOfOrNull { find(it, text) }

    fun capture(name: String, width: Int, height: Int, dark: Boolean = false, clicks: List<String> = emptyList()) {
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(width, height, Density(1f)) {
                XJTUToolBoxTheme(darkModeOverride = if (dark) "dark" else "light") {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) { DegreeScreen(source, onBack = {}) }
                }
            }
            scene.render(0)
        }
        repeat(8) { frame ->
            Thread.sleep(100)
            SwingUtilities.invokeAndWait { scene.render((frame + 1) * 200_000_000L) }
        }
        clicks.forEachIndexed { step, label ->
            SwingUtilities.invokeAndWait {
                val entry = requireNotNull(scene.semanticsOwners.reversed().firstNotNullOfOrNull { find(it.rootSemanticsNode, label) }) { "找不到 $label" }
                entry.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
            }
            repeat(10) { frame ->
                Thread.sleep(100)
                SwingUtilities.invokeAndWait { scene.render(2_000_000_000L + step * 1_000_000_000L + frame * 100_000_000L) }
            }
        }
        SwingUtilities.invokeAndWait {
            File(output, name).writeBytes(requireNotNull(scene.render(9_000_000_000L).encodeToData()).bytes)
            scene.close()
        }
    }
    capture("mine.png", 412, 915)
    capture("mine-open.png", 412, 915, clicks = listOf("专业核心课程", "专业选修课程", "光电信息科学模块"))
    capture("mine-dark.png", 412, 915, dark = true)
    capture("picker.png", 412, 915, clicks = listOf("换方案"))
    capture("browse.png", 412, 915, clicks = listOf("换方案", "车辆工程"))
    capture("landscape.png", 915, 412)
    exitProcess(0)
}
