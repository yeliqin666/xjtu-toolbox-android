package com.xjtu.toolbox.library

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusLibraryApi
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `CampusLibraryApi` 的 **serve 模式**（`ApiMode.SERVE`，契约 §5.3 / §5.3.1）写路径测试。
 *
 * 默认（campus-api）那一侧的行为在 [CampusLibraryMappingTest] 钉着（`canBook=false`、写方法抛）；
 * 这一份钉的是 serve 那一条新路：`canBook` 翻 true、`my.actionUrls` 投影、`action(url)` 把地址认成
 * 动作码、四个写方法真的 POST 到 `/api/library/action`、业务拒绝（409）原样变成 `BookResult(false, …)`。
 */
class CampusLibraryServeWriteTest {

    /** 一个会把「打出去的动作请求体」记下来的假 serve 后端。 */
    private class ServeMock {
        val postedBodies = mutableListOf<String>()
        var reject = false

        val client: HttpClient = createToolboxClient(
            engine = MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/api/library/campus") -> campusPayload
                    path.endsWith("/api/library/my") && request.method == HttpMethod.Get -> myPayload
                    path.endsWith("/api/library/action") && request.method == HttpMethod.Post -> {
                        postedBodies += (request.body as TextContent).text
                        if (reject) actionRejectedPayload else actionOkPayload
                    }
                    else -> error("测试没有为 ${request.method} $path 准备响应")
                }
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )
            },
        )
    }

    @Test
    fun serveModeCanBookTurnsOnAndProjectsActionUrls() = runTest {
        val mock = ServeMock()
        val api = CampusLibraryApi(mock.client, mode = ApiMode.SERVE)

        // canBook 翻 true：campus() 拉到服务端如实报的开关后，两件事的「与」就是 true
        assertEquals(LibraryCampus.XINGQING, api.campus())
        assertTrue(api.canBook, "写端点已落地（SERVE_WRITE_ENDPOINTS_READY=true）⇒ canBook 随服务端亮")
        assertFalse(api.hasSeatPlan, "布局/底图端点不在 §5 ⇒ hasSeatPlan 仍是 false")

        // my：actionUrls 投影（label → 上游地址），地址是「不透明句柄」
        val booked = api.myBooking().getOrThrow()!!
        assertEquals("A01", booked.seatId)
        assertEquals("北楼二层外文库（东）", booked.area)
        assertEquals(
            mapOf(
                "取消预约" to "http://rg.lib.xjtu.edu.cn:8086/my/?cancel=1&ri=88",
                "入馆签到" to "http://rg.lib.xjtu.edu.cn:8086/my/?firstruguan=1&ri=88",
            ),
            booked.actionUrls,
        )
    }

    @Test
    fun writesPostTheRightActionCode() = runTest {
        val mock = ServeMock()
        val api = CampusLibraryApi(mock.client, mode = ApiMode.SERVE)
        api.campus()


        // 屏上的顺序：先拉过一次「我的预约」才有按钮地址可认（actionUrl 反表来自那一发）
        api.myBooking()
        // 屏上「取消预约」那条按钮的地址 → 动作码 cancel
        val cancel = api.action("http://rg.lib.xjtu.edu.cn:8086/my/?cancel=1&ri=88")
        assertTrue(cancel.success)
        assertEquals("cancel", sentAction(mock))
        assertTrue(cancel.message.contains("预约成功"), "成功文案原样从 data.message 带回")

        // 约座 / 换座 / 切校区各自发出自己的动作码 + 参数
        api.bookSeat("A01", "north2east", autoSwap = false)
        val seat = sentJson(mock)
        assertEquals("seat", seat["action"]!!.jsonPrimitive.content)
        assertEquals("A01", seat["seatId"]!!.jsonPrimitive.content)
        assertEquals("north2east", seat["area"]!!.jsonPrimitive.content)
        assertEquals("false", seat["autoSwap"]!!.jsonPrimitive.content)

        api.swapSeat("A02", "north2east")
        assertEquals("change", sentAction(mock))

        assertTrue(api.switchCampus(LibraryCampus.YANTA))
        val switchJson = sentJson(mock)
        assertEquals("switch_campus", switchJson["action"]!!.jsonPrimitive.content)
        assertEquals("west", switchJson["campus"]!!.jsonPrimitive.content)
    }

    @Test
    fun businessRejectionBecomesBookResultFalseAndForeignUrlThrows() = runTest {
        val mock = ServeMock()
        val api = CampusLibraryApi(mock.client, mode = ApiMode.SERVE)
        api.campus()

        // 座位被占（409 + 中文短句）：BookResult(false, 原文)，不是异常 —— 屏上的横幅要显示这句
        mock.reject = true
        val taken = api.bookSeat("A01", "north2east", autoSwap = true)
        assertFalse(taken.success)
        assertTrue(taken.message.contains("已被他人预约"))

        // 不是「我的预约」报出来的地址 ⇒ 响亮失败（那不是本端该打的按钮）
        assertFailsWith<IllegalStateException> { api.action("http://rg.lib.xjtu.edu.cn:8086/other") }
    }

    private fun sentAction(mock: ServeMock): String =
        sentJson(mock)["action"]!!.jsonPrimitive.content

    private fun sentJson(mock: ServeMock): kotlinx.serialization.json.JsonObject =
        AppJson.parseToJsonElement(mock.postedBodies.last()).jsonObject

    private companion object {
        val campusPayload = """
            {"code":0,"data":{
              "current":{"code":"east","name":"兴庆校区 钱学森图书馆"},
              "campuses":[{"code":"east","name":"兴庆校区 钱学森图书馆"},{"code":"west","name":"雁塔校区图书馆"}],
              "queryableFloors":["xingqing2floor","xingqing3floor","xingqing4floor"],
              "canBook":true,"hasSeatPlan":true}}
        """.trimIndent()

        val myPayload = """
            {"code":0,"data":{"my":{
              "seatId":"A01","area":"北楼二层外文库（东）","statusText":"已预约",
              "actionUrls":{
                "取消预约":"http://rg.lib.xjtu.edu.cn:8086/my/?cancel=1&ri=88",
                "入馆签到":"http://rg.lib.xjtu.edu.cn:8086/my/?firstruguan=1&ri=88"}}}}
        """.trimIndent()

        val actionOkPayload = """{"code":0,"data":{"ok":true,"message":"✓ 座位 A01 预约成功！"}}"""
        val actionRejectedPayload = """{"code":409,"message":"该座位已被他人预约\n‣ 已自动刷新座位列表"}"""
    }
}