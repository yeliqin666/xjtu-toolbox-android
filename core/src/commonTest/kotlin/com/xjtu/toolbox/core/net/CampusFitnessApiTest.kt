package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.fitness.FitnessSource
import com.xjtu.toolbox.fitness.fitnessItemName
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * `CampusFitnessApi`（Web 端的体测取数）的**口径测试**。
 *
 * 为什么值得写：体测是「同一个屏、两端两套取数」的第一批屏之一，最容易出的事故不是崩，
 * 而是**两端算出的数不一样**（分项名随性别换、`--`/`未测`/`缺项` 三个占位、总分两位小数）。
 * 这里用 MockEngine 喂 campus-api 的**文档形状**（`modules/fitness.js` 的 `parseYears`/`parseScore`），
 * 把映射逐条钉死，不需要真服务器 —— 也不会因为学校体测系统 502 就失去回归保护
 * （2026-10-08 实测：`/api/fitness/years` 直接回 `HTTP 502`，上游应用自身故障，与账号无关）。
 */
class CampusFitnessApiTest {

    private val yearsPayload = """
        {"http":200,"url":"/api/fitness/years","code":0,"data":{
          "years":[
            {"year":2026,"yearNum":"2026","name":"2026-2027学年","checked":true},
            {"year":2025,"yearNum":"2025","name":"2025-2026学年","checked":false}
          ],
          "showYears":["2026","2025"],
          "currentYear":"2026",
          "env":{"systemStatus":1,"showStatus":1}
        }}
    """.trimIndent()

    private val scorePayload = """
        {"http":200,"url":"/api/fitness/score","code":0,"data":{
          "hasScore":true,"reason":null,"info":"查询成功","upstreamStatus":1,
          "score":{"total":83.400000000000006,"totalGrade":"良好","reportType":"正常",
                   "reportDesc":null,"reportStatus":"已审核","scoreStatus":1,
                   "studentYear":"大二成绩","sex":"女","grade":"2025"},
          "items":[
            {"key":"bmi","name":"身高 / 体重","value":"165.0cm/52.0kg","valueAlt":null,"grade":"正常","gradeClass":"green"},
            {"key":"vc","name":"肺活量","value":2800,"valueAlt":null,"grade":"良好","gradeClass":"green"},
            {"key":"jump","name":"立定跳远","value":176.40000000000003,"valueAlt":null,"grade":"及格","gradeClass":null},
            {"key":"sit_and_reach","name":"坐位体前屈","value":null,"valueAlt":null,"grade":null,"gradeClass":null},
            {"key":"pull_and_sit","name":"仰卧起坐","value":42,"valueAlt":null,"grade":"优秀","gradeClass":"green"},
            {"key":"50m","name":"50 米","value":8.2,"valueAlt":null,"grade":"良好","gradeClass":"green"},
            {"key":"run","name":"800 米","value":"4'05\"","valueAlt":null,"grade":"及格","gradeClass":null}
          ]
        }}
    """.trimIndent()

    private val emptyScorePayload = """
        {"http":200,"url":"/api/fitness/score","code":0,"data":{
          "hasScore":false,"reason":"no-score","info":"未查询到该学年体测成绩","upstreamStatus":1,
          "score":null,"items":[]}}
    """.trimIndent()

    private fun apiFor(vararg bodies: Pair<String, String>): FitnessSource {
        val byPath = bodies.toMap()
        val engine = MockEngine { request ->
            val body = byPath.entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        return CampusFitnessApi(createToolboxClient(engine = engine))
    }

    @Test
    fun yearsMappingKeepsUpstreamFields() = runTest {
        val years = apiFor("/api/fitness/years" to yearsPayload).years()
        assertEquals(2, years.size)
        assertEquals("2026", years[0].yearNum)
        assertEquals("2026-2027学年", years[0].name)
        assertEquals(true, years[0].checked)
        assertEquals(false, years[1].checked)
    }

    @Test
    fun yearsWithoutYearNumAreDropped() = runTest {
        val payload = """{"code":0,"data":{"years":[{"year":2026,"yearNum":null,"name":"无名"},{"yearNum":"2025","name":"2025-2026学年"}]}}"""
        val years = apiFor("/api/fitness/years" to payload).years()
        assertEquals(listOf("2025"), years.map { it.yearNum })
        assertEquals("2025-2026学年", years[0].name)
    }

    @Test
    fun scoreMappingFollowsSharedRules() = runTest {
        val score = apiFor("/api/fitness/score" to scorePayload).score("2026")
        // 总分：浮点噪声 → 两位小数
        assertEquals("83.40", score.totalScore)
        assertEquals("良好", score.totalGrade)
        assertEquals("已审核", score.reportStatus)
        assertEquals("女", score.sex)
        // 隐私口径：campus-api 不投影姓名/学号
        assertEquals("", score.studentName)
        assertEquals("", score.studentNumber)
        // 分项：按共享表定序（7 项）、按性别换名、三个占位各就各位
        assertEquals(
            listOf("身高 / 体重", "肺活量", "立定跳远", "坐位体前屈", "仰卧起坐", "50 米", "800 米"),
            score.items.map { it.name },
        )
        assertEquals("165.0cm/52.0kg", score.items[0].value, message = "非数字原样保留")
        assertEquals("2800.00", score.items[1].value, message = "数字也要两位小数（与 :app 的 formatScore 同口径）")
        assertEquals("176.40", score.items[2].value)
        assertEquals("未测", score.items[3].value, message = "缺值 → 未测")
        assertEquals("缺项", score.items[3].grade, message = "缺等级 → 缺项")
        assertEquals("", score.items[3].tone)
        assertEquals("4'05\"", score.items[6].value, message = "成绩文本原样保留")
        assertEquals("green", score.items[0].tone)
    }

    @Test
    fun sexNamesUseTheSameBranchAsApp() = runTest {
        // ⚠️ 这条是两端一致性的关键：campus-api 在 sex 缺失时会给中性名
        //（「力量（引体向上/仰卧起坐）」），但 :app 老实现是 `if (sex == "女") … else …`。
        // 这里钉住「缺失 = 默认支」，否则同一个人的同一项在两端会叫两个名字。
        assertEquals("引体向上", fitnessItemName("pull_and_sit", ""))
        assertEquals("仰卧起坐", fitnessItemName("pull_and_sit", "女"))
        assertEquals("1000 米", fitnessItemName("run", ""))
        assertEquals("800 米", fitnessItemName("run", "女"))
        assertEquals("50 米", fitnessItemName("50m", "女"))
    }

    @Test
    fun noScoreReportsUpstreamInfo() = runTest {
        val api = apiFor("/api/fitness/score" to emptyScorePayload)
        val e = assertFailsWith<RuntimeException> { api.score("2025") }
        assertEquals("未查询到该学年体测成绩", e.message)
    }

    @Test
    fun envelopeErrorIsSurfacedNotSwallowed() = runTest {
        val payload = """{"code":1,"error":"体测 /fitness/fitnessYear HTTP 502","msg":"体测 /fitness/fitnessYear HTTP 502"}"""
        val api = apiFor("/api/fitness/years" to payload)
        val e = assertFailsWith<IllegalStateException> { api.years() }
        assertEquals(true, e.message?.contains("HTTP 502"))
    }
}
