package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.jwapp.NoScoreDetailException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * serve 契约 §5.8 的**客户端那一侧**：一门课的**分项**成绩
 *（`GET /api/jwapp/score-detail?courseId=`，[CampusJwappScoreApi.getScoreDetail]）。
 *
 * 这一格在 §5.6 那一批里还是「刻意不端」（开关报 `false`、卡片点了不展开），2026-10-14 按 D13
 * 端了 ⇒ 本类钉住四件事：
 *
 *  1. **请求形状**：`?courseId=` 必填、原样发（它就是 `/api/jwapp/scores` 那一行的 `id`）；
 *  2. **`itemPercent` 是比例**（`0.3` = 30%）：`:data` 已经把上游的 `"30%"` 除过 100，本端不除第二次；
 *  3. ⚠️ **「这门课没有分项」= `detail:null` ⇒ 抛 [NoScoreDetailException]**（与 `:data` 的
 *     `JwappApi.getDetail` 同一个异常，屏据此拼 `ScoreItem.asEmptyDetail()`），**不是** 加载失败；
 *  4. **两种形态的开关不一样**：serve 形态 `canFetchScoreDetail = true`，campus-api 形态 `false`
 *     （移动教务在那一头没有这条端点）⇒ 屏在那形态下不展开那一格。
 */
class ServeJwappScoreDetailApiTest {

    private class Call(val path: String, val url: String)

    private fun engineFor(vararg bodies: Pair<String, String>, calls: MutableList<Call>? = null): MockEngine =
        MockEngine { request ->
            calls?.add(Call(request.url.encodedPath, request.url.toString()))
            val body = bodies.toMap().entries.firstOrNull { request.url.encodedPath.endsWith(it.key) }?.value
                ?: error("测试没有为 ${request.url.encodedPath} 准备响应")
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }

    private val detailBody = """
        {"code":0,"data":{"courseId":"S1","detail":{
          "courseName":"高等数学（上）","coursePoint":5.0,"examType":"考试","majorFlag":"必修",
          "examProp":"初修","replaceFlag":false,"score":"95","scoreValue":95.0,"gpa":4.0,
          "passFlag":true,"specificReason":null,
          "itemList":[
            {"itemName":"平时成绩","itemPercent":0.3,"itemScore":"88","itemScoreValue":88.0},
            {"itemName":"期末考试","itemPercent":0.7,"itemScore":"98","itemScoreValue":null}]}}}
    """.trimIndent()

    /** 分项那一发：`courseId` 原样发、十二格逐字段、比例不当百分数用。 */
    @Test
    fun serveScoreDetailReadsTheEndpoint() = runTest {
        val calls = mutableListOf<Call>()
        val api = CampusJwappScoreApi(
            createToolboxClient(engine = engineFor("/api/jwapp/score-detail" to detailBody, calls = calls)),
            "",
            ApiMode.SERVE,
        )

        assertTrue(api.canFetchScoreDetail, "§5.8 端了分项成绩 ⇒ serve 形态下屏上那一格能展开")

        val detail = api.getScoreDetail("S1")
        assertEquals("/api/jwapp/score-detail", calls.single().path)
        assertTrue(calls.single().url.endsWith("?courseId=S1"), "课程 id 原样发：${calls.single().url}")
        assertEquals("高等数学（上）", detail.courseName)
        assertEquals(95.0, detail.scoreValue)
        assertEquals(4.0, detail.gpa)
        assertNull(detail.specificReason)
        assertEquals(2, detail.itemList.size)
        // ⚠️ 0.3 就是 30%：服务端除过 100 了，本端不除第二次（屏上乘 100 再显示）
        assertEquals(0.3, detail.itemList.first().itemPercent)
        assertEquals(88.0, detail.itemList.first().itemScoreValue)
        // 解析不出来的分项分数是 null（`itemScore` 原文照旧在）—— 不拿 0 冒充
        assertNull(detail.itemList.last().itemScoreValue)
        assertEquals("98", detail.itemList.last().itemScore)
    }

    /**
     * 「这门课没有分项」是**上游明确说没有**（§4：`detail` 显式为 `null`）⇒ 抛 [NoScoreDetailException]，
     * 屏的 `loadDetail` 拿它走「自己拼一份空明细」那条老分支，而不是弹「加载分项成绩失败」。
     */
    @Test
    fun serveDetailNullMeansNoItemsNotAFailure() = runTest {
        val api = CampusJwappScoreApi(
            createToolboxClient(
                engine = engineFor(
                    "/api/jwapp/score-detail" to """{"code":0,"data":{"courseId":"S2","detail":null}}""",
                ),
            ),
            "",
            ApiMode.SERVE,
        )
        assertFailsWith<NoScoreDetailException> { api.getScoreDetail("S2") }
    }

    /** `courseId` 缺 / 空白 ⇒ 抛错且**不打上游**（服务端那一侧同样是 400，本端不替它猜一个 id）。 */
    @Test
    fun serveScoreDetailRefusesBlankCourseId() = runTest {
        val calls = mutableListOf<Call>()
        val api = CampusJwappScoreApi(
            createToolboxClient(engine = engineFor("/api/jwapp/score-detail" to detailBody, calls = calls)),
            "",
            ApiMode.SERVE,
        )
        assertFailsWith<IllegalArgumentException> { api.getScoreDetail("   ") }
        assertEquals(0, calls.size, "空 id 不该发请求")
    }

    /**
     * campus-api 形态：那一头没有这条端点（那个上游就没有它）⇒ 开关报 `false`，方法抛 `serveOnly` 那句错
     * —— 屏据开关不展开那一格（不是画一个点了会报错的控件）。
     */
    @Test
    fun campusApiModeStillReportsNoScoreDetail() = runTest {
        val api = CampusJwappScoreApi(
            createToolboxClient(engine = engineFor("/api/jwapp/score-detail" to detailBody)),
            "",
        )
        assertFalse(api.canFetchScoreDetail, "campus-api 那一头没有这条端点 ⇒ 不许许诺能展开")
        val e = assertFailsWith<UnsupportedOperationException> { api.getScoreDetail("S1") }
        assertTrue(e.message?.contains("serve") == true, "实际：${e.message}")
    }
}
