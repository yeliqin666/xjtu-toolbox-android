package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `WebVenueHost.venuePayTarget` 的判据测试。 */
class WebVenuePayTargetTest {

    /** serve 形态：`CampusVenueApi.paymentUrl` 给的是 serve 自己的代跳地址（同源相对路径）。 */
    private val servePayHop = "/api/venue/pay?orderId=VT20261013001"

    /** `:data` 的 `VenueApi.BROWSER_LOGIN_URL` 那个 OAuth 入口（serve 从 `/api/venue/status` 投影它）。 */
    private val venueOauthEntry =
        "https://org.xjtu.edu.cn/openplatform/oauth/authorize?responseType=code&scope=user_info&appId=1659"

    @Test
    fun `两跳都在时优先支付页（浏览器只开一个标签）`() {
        assertEquals(
            servePayHop,
            venuePayTarget(loginUrl = venueOauthEntry, then = servePayHop),
            "serve 形态要落在支付页那一跳上：先过登录、再跳支付页这件事浏览器做不到，登录由站点自己那次跳转去要",
        )
    }

    @Test
    fun `只有登录站可去时开登录站（不把这一枪吞掉）`() {
        assertEquals(
            venueOauthEntry,
            venuePayTarget(loginUrl = venueOauthEntry, then = ""),
            "没有支付页地址时，登录站也是动作 —— 静默返回 null 就是「点了没反应」",
        )
    }

    @Test
    fun `两个地址都空时返回 null（由宿主如实说一句，而不是静默）`() {
        assertNull(venuePayTarget(loginUrl = "", then = ""))
        assertNull(venuePayTarget(loginUrl = "   ", then = "\n"), "空白串与空串同一条判据")
    }
}
