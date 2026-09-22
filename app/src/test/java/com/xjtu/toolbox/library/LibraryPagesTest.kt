package com.xjtu.toolbox.library

import com.xjtu.toolbox.library.LibraryPages.ActionVerdict
import com.xjtu.toolbox.library.LibraryPages.MyPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图书馆页面解析。HTML 夹具照 `/my/` 的真实结构写（参考 yan-xiaoo/XJTUToolBox 的
 * `test/library/test_booking_parse.py`）：当前预约是 well 卡、历史是 notwell，
 * 座位行是第一个 `<hr>` 后面的「区域名&nbsp;座位号」。
 */
class LibraryPagesTest {

    private val base = LibraryPages.BASE_URL
    private val myUrl = "$base/my/"

    private fun card(area: String, seat: String, status: String, buttons: String = "", cls: String = "well") = """
        <html><body>
        <div class="$cls">
        <div class="row">
          <div class="col-md-9 cta-contents">
            <h4>2026-08-22</h4><h4>创新港图书资料中心</h4><br>
            <h3 class="cta-title">业务类型:&nbsp;预约座位<br>
            <hr>$area&nbsp;$seat
            <h4><a href="/updateseat">我想换座</a></h4>
          </div>
          <div class="col-md-3 cta-button"><center>
            <h4>预约状态:</h4><h3>$status</h3>
            $buttons
          </center></div>
        </div></div>
        </body></html>
    """.trimIndent()

    private fun booked(html: String, url: String = myUrl): MyBookingInfo {
        val page = LibraryPages.parseMyPage(html, url)
        assertTrue("应解析出预约，实际 $page", page is MyPage.Booked)
        return (page as MyPage.Booked).info
    }

    @Test
    fun `当前预约卡片解析出座位区域状态和动作`() {
        val html = card("北楼二层外文库（东）", "A101", "已预约") + """
            <script>showConfirmModal('确认', 'cancel', '88')</script>
            <script>showConfirmModal('确认', 'ruguan1', '88')</script>
        """
        val b = booked(html)
        assertEquals("A101", b.seatId)
        assertEquals("北楼二层外文库（东）", b.area)
        assertEquals("已预约", b.statusText)
        assertEquals(
            mapOf("取消预约" to "$base/my/?cancel=1&ri=88", "入馆签到" to "$base/my/?firstruguan=1&ri=88"),
            b.actionUrls,
        )
    }

    @Test
    fun `WebVPN 实体化引号的动作也能解析`() {
        val html = card("北楼二层外文库（东）", "D004", "待入馆") + """
            <a href="#" onclick="var vpn_return;eval(vpn_rewrite_js((function () { showConfirmModal(&#39;确认取消申请?&#39;, &#39;cancel&#39;, &#39;4953117&#39;); }).toString().slice(14, -2), 2));return vpn_return;">取消预约</a>
            <a href="#" onclick="var vpn_return;eval(vpn_rewrite_js((function () { showConfirmModal(&#39;确认您已到馆?&#39;, &#39;ruguan1&#39;, &#39;4953117&#39;); }).toString().slice(14, -2), 2));return vpn_return;">线上签到</a>
        """
        val b = booked(html, "https://webvpn.xjtu.edu.cn/http/xxx/my/")
        assertEquals("D004", b.seatId)
        assertEquals(
            mapOf("取消预约" to "$base/my/?cancel=1&ri=4953117", "入馆签到" to "$base/my/?firstruguan=1&ri=4953117"),
            b.actionUrls,
        )
    }

    @Test
    fun `只给页面上真有的按钮`() {
        val html = card("北楼二层外文库（东）", "D004", "待入馆") + """
            <script>showConfirmModal('确认取消申请?', 'cancel', '77')</script>
            <script>showConfirmModal('确认您已到馆?', 'ruguan1', '77')</script>
        """
        assertEquals(setOf("取消预约", "入馆签到"), booked(html).actionUrls.keys)
    }

    @Test
    fun `已入馆只剩离开和返回也认作当前预约`() {
        val html = card("创新港图书资料中心一层阅览区（西）", "098", "使用中") + """
            <script>showConfirmModal('确认', 'leave', '77')</script>
            <script>showConfirmModal('确认', 'return', '77')</script>
        """
        val b = booked(html)
        assertEquals("098", b.seatId)
        assertEquals("使用中", b.statusText)
        assertEquals(
            mapOf("中途离开" to "$base/my/?midleave=1&ri=77", "中途返回" to "$base/my/?midreturn=1&ri=77"),
            b.actionUrls,
        )
    }

    @Test
    fun `只有历史卡片就是没有预约`() {
        val html = card("北楼二层外文库（东）", "D004", "超时未入馆", cls = "notwell")
        assertEquals(MyPage.NoBooking, LibraryPages.parseMyPage(html, myUrl))
    }

    @Test
    fun `暂无预约页面里的统计数字不当成座位号`() {
        val html = "<html><body><div>今日累计 120 人次，暂无预约</div>" + " ".repeat(60) + "</body></html>"
        assertEquals(MyPage.NoBooking, LibraryPages.parseMyPage(html, myUrl))
    }

    @Test
    fun `认不出的页面交给调用方换地址`() {
        val html = "<html><body><h1>Not Found</h1>" + " ".repeat(60) + "</body></html>"
        assertEquals(MyPage.Unrecognized, LibraryPages.parseMyPage(html, myUrl))
    }

    @Test
    fun `没有内联 JS 时从地址里取预约号`() {
        val out = LibraryPages.actionsFromConfirmModal("<div></div>", "$base/my/?cancel=1&ri=42")
        assertEquals(mapOf("取消预约" to "$base/my/?cancel=1&ri=42"), out)
    }

    // ── 平面图 ──

    @Test
    fun `平面图坐标和状态码`() {
        val body = """
            {"A101": ["10.5", "20", "30", "40", "2"],
             "A102": ["50", "60", "30", "40", "0"],
             "A103": ["90", "100", "30", "40", "-1"],
             "cancel": ["999", "999", "1", "1", "-2"],
             "spacecancel": ["1", "1", "1", "1", "-2"],
             "": ""}
        """
        val layout = LibraryPages.parseSeatLayout(body)
        val byId = layout.seats.associateBy { it.seatId }
        assertEquals(setOf("A101", "A102", "A103"), byId.keys)
        val a101 = byId.getValue("A101")
        assertEquals(10.5f, a101.left)
        assertEquals(40f, a101.height)
        assertTrue(a101.available)
        assertFalse(byId.getValue("A102").available)
        assertEquals(PlanSeat.CANCELLED, byId.getValue("A103").status)
    }

    @Test
    fun `平面图坐标不全或宽高为零的项丢掉`() {
        val layout = LibraryPages.parseSeatLayout("""{"A1": ["1", "2", "x", "4", "2"], "A2": ["1", "2", "0", "4", "2"], "A3": ["1","2","3","4"]}""")
        assertEquals(listOf("A3"), layout.seats.map { it.seatId })
        assertEquals(PlanSeat.FREE, layout.seats.single().status)
    }

    // ── 结果复核 ──

    @Test
    fun `座位号严格相等，不做子串匹配`() {
        assertFalse(LibraryPages.sameSeat("A011", "A01"))
        assertTrue(LibraryPages.sameSeat(" a01 ", "A01"))
    }

    private fun info(status: String, vararg actions: String) =
        MyBookingInfo("A101", "区", status, actions.associateWith { "$base/my/?x=1" })

    @Test
    fun `取消以预约是否消失为准`() {
        assertEquals(ActionVerdict.DONE, LibraryPages.actionVerdict("取消预约", null, fetched = true))
        assertEquals(ActionVerdict.DONE, LibraryPages.actionVerdict("取消预约", info("已取消"), fetched = true))
        assertEquals(ActionVerdict.NOT_DONE, LibraryPages.actionVerdict("取消预约", info("已预约", "取消预约"), fetched = true))
        // 没查到预约页不能当成「已取消」
        assertEquals(ActionVerdict.UNKNOWN, LibraryPages.actionVerdict("取消预约", null, fetched = false))
    }

    @Test
    fun `签到离开返回看剩下的按钮`() {
        assertEquals(ActionVerdict.DONE, LibraryPages.actionVerdict("入馆签到", info("使用中", "中途离开"), true))
        assertEquals(ActionVerdict.NOT_DONE, LibraryPages.actionVerdict("入馆签到", info("待入馆", "取消预约", "入馆签到"), true))
        assertEquals(ActionVerdict.DONE, LibraryPages.actionVerdict("中途离开", info("暂离", "中途返回"), true))
        assertEquals(ActionVerdict.NOT_DONE, LibraryPages.actionVerdict("中途离开", info("使用中", "中途离开"), true))
        assertEquals(ActionVerdict.DONE, LibraryPages.actionVerdict("中途返回", info("使用中", "中途离开"), true))
        assertEquals(ActionVerdict.NOT_DONE, LibraryPages.actionVerdict("中途返回", info("暂离", "中途返回"), true))
    }

    @Test
    fun `失败原因`() {
        assertEquals("该座位已被他人预约\n‣ 已自动刷新座位列表", LibraryPages.bookingFailureReason("<div>该座位已被预约</div>"))
        assertEquals("系统维护中，请稍后再试", LibraryPages.bookingFailureReason("<div>系统维护中</div>"))
        assertNull(LibraryPages.bookingFailureReason("<div>未知错误</div>"))
        // 提示框里的关闭按钮 × 不拼进原因
        assertEquals("入馆签到成功！", LibraryPages.bookingFailureReason("""<div class="alert"><button class="close">×</button>入馆签到成功！</div>"""))
    }
}
