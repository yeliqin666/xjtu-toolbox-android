package com.xjtu.toolbox.server

import com.xjtu.toolbox.notification.AppNoticeSource
import com.xjtu.toolbox.notification.NotificationFakeUpstream
import com.xjtu.toolbox.library.LibraryFakeUpstream
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * 通知公告两端点的契约测试（`/api/notification/sources` · `/api/notification/list`）。
 *
 * 免登录：`:data` 的 [AppNoticeSource]（okhttp + jsoup 爬 29 个站）不碰任何站点会话，
 * 三个代表性源（教务处 JWC / 化工学院 CLET / OA）由 `:testkit` 的 [NotificationFakeUpstream]
 * 扮演（https 域，走假代理的 CONNECT 隧道）。
 *
 * 断言形状逐字段；条目标题/日期/链接的 expected 值全部来自夹具常量。
 */
class ServeNotificationTest {

    private var started: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @AfterTest
    fun stopServer() {
        started?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        started = null
    }

    @Test
    fun `通知源清单：29 个源逐字段，与 NotificationSource 枚举一致`() = withFakeProxy(casEnabled = false) { _ ->
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { notificationRoutes(AppNoticeSource()) }
        started = server

        val response = http.get("/api/notification/sources")
        assertEquals(200, response.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(response))
        val data = envelopeData(response)
        assertEquals(29, data.optInt("total"), "29 个源（NotificationSource.entries.size）")
        val sources = data.arrOf("sources")
        assertEquals(29, sources.size)
        assertTrue(sources.any { it.let { o -> o as JsonObject }.optString("code") == "JWC" }, "综合类第一个教务处")
        assertTrue(sources.any { it.let { o -> o as JsonObject }.optString("code") == "MARX" }, "人文经管最后一个马院")
        for (element in sources) {
            val s = element as JsonObject
            assertEquals(setOf("code", "displayName", "category"), s.keys, "每个源只投影三个字段")
            assertTrue(s.optString("code")!!.isNotBlank())
            assertTrue(s.optString("displayName")!!.isNotBlank())
            assertTrue(
                s.optString("category") in setOf("综合", "工学", "理学", "人文经管"),
                "分类名取 SourceCategory.displayName",
            )
        }
        // 教务处那条的 displayName / 分类
        val jwc = sources.first { it.let { o -> o as JsonObject }.optString("code") == "JWC" } as JsonObject
        assertEquals("教务处", jwc.optString("displayName"))
        assertEquals("综合", jwc.optString("category"))

        // 红线 + 闸门
        assertNoIdentity(response.body(), "GET /api/notification/sources", LOGIN_IDENTITY)
        assertEquals(401, http.get("/api/notification/sources", bearer = null).statusCode())
        assertEquals(401, http.get("/api/notification/sources", bearer = "wrong").statusCode())
    }

    @Test
    fun `通知列表：单页与 all 拉全页，条目逐字段，未知来源 400`() = withFakeProxy(casEnabled = false) { _ ->
        val token = AccessToken.newToken()
        val (server, http) = startEndpointServer(token) { notificationRoutes(AppNoticeSource()) }
        started = server

        // ── 缺省只查默认源（JWC，与屏的默认一致）──
        val single = http.get("/api/notification/list")
        assertEquals(200, single.statusCode())
        assertEquals(ApiEnvelope.CODE_OK, envelopeCode(single))
        val singleData = envelopeData(single)
        assertEquals(1, singleData.optInt("page"))
        assertEquals(3, singleData.optInt("size"), "JWC 第一页 3 条（夹具原文）")
        assertEquals(3, singleData.optInt("total"))
        assertTrue(singleData.optBoolean("hasMore") == true, "JWC 第一页有下一页")
        assertEquals(0, singleData.arrOf("skipped").size)
        val jwcItems = singleData.arrOf("items")
        assertTrue(jwcItems.all { it.let { o -> o as JsonObject }.optString("source") == "JWC" })
        val firstJwc = jwcItems.first() as JsonObject
        assertEquals(NotificationFakeUpstream.JWC_TITLE_1, firstJwc.optString("title"), "按日期倒序：最新一条在最前")
        assertEquals(NotificationFakeUpstream.JWC_LINK_1, firstJwc.optString("link"))
        assertEquals(NotificationFakeUpstream.JWC_DATE_1, firstJwc.optString("date"))
        assertEquals(listOf(NotificationFakeUpstream.JWC_TAG), firstJwc.arrOf("tags").map { it.toString().trim('"') }, "标签抠成「通知」")
        assertTrue(firstJwc.optString("description") != null, "description 必须有（可能是空串）")

        // ── 三个源合并 ──
        val merged = http.get("/api/notification/list?sources=JWC,CLET,OA")
        assertEquals(200, merged.statusCode())
        val mergedData = envelopeData(merged)
        assertEquals(9, mergedData.optInt("size"), "JWC 3 + CLET 3 + OA 3（三源各一页）")
        val items = mergedData.arrOf("items")
        assertEquals(9, items.size)
        assertEquals(0, mergedData.arrOf("skipped").size, "三个源都爬到了 ⇒ 无降级")
        val newest = items.first() as JsonObject
        assertEquals(NotificationFakeUpstream.CLET_DATE_3, newest.optString("date"))
        assertEquals(NotificationFakeUpstream.CLET_TITLE_3, newest.optString("title"))
        val oldest = items.last() as JsonObject
        assertEquals(NotificationFakeUpstream.OA_DATE_3, oldest.optString("date"), "OA 第一页三日（09-19/17/14）的最旧那条")
        assertEquals(NotificationFakeUpstream.OA_TITLE_3, oldest.optString("title"))
        // 逐条形状
        for (element in items) {
            val n = element as JsonObject
            assertEquals(setOf("title", "link", "source", "description", "tags", "date"), n.keys, "条目只投影这六个字段")
            assertTrue(n.optString("title")!!.isNotBlank())
            assertTrue(n.optString("link")!!.startsWith("https://"), "链接是绝对地址")
            assertTrue(n.optString("source") in setOf("JWC", "CLET", "OA"))
            assertEquals(10, n.optString("date")!!.length, "date 是 YYYY-MM-DD")
        }

        // ── all=1 拉全页：page 归一成 1，条数比单页多 ──
        val all = http.get("/api/notification/list?sources=JWC,CLET,OA&all=1")
        assertEquals(200, all.statusCode())
        val allData = envelopeData(all)
        assertEquals(1, allData.optInt("page"))
        assertTrue(allData.optInt("size")!! > 9, "all=1 应拉超过第一页的条数")
        assertEquals(allData.arrOf("items").size, allData.optInt("size"))
        assertTrue(allData.optBoolean("hasMore") == false, "拉到最后一页后 hasMore=false")

        // ── 未知来源代码 → 400（信封，中文短句）──
        val bad = http.get("/api/notification/list?sources=NOPE")
        assertEquals(400, bad.statusCode())
        assertEquals(ApiErrors.BAD_REQUEST, envelopeCode(bad))
        assertTrue(envelopeMessage(bad).orEmpty().any { it.code in 0x4e00..0x9fff })

        // 红线 + 闸门
        assertNoIdentity(merged.body(), "GET /api/notification/list", LOGIN_IDENTITY)
        assertEquals(401, http.get("/api/notification/list", bearer = null).statusCode())
        assertEquals(401, http.get("/api/notification/list", bearer = "wrong").statusCode())
    }

    private companion object {
        /** 红线：登录用户本人的身份（通知是公开公告，姓名/学号都不该出现）。 */
        val LOGIN_IDENTITY = listOf(
            LibraryFakeUpstream.USERNAME,
            "password",
            "username",
            "studentId",
            "学号",
            "手机号",
        )
    }
}