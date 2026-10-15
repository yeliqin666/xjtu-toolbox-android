package com.xjtu.toolbox.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `WebVenueHost.venuePayTarget` 的判据测试 —— 场馆「去支付」在浏览器这一端**该打开哪个地址**
 * （契约 §5.3.2 第 6 条 + §5.4 的「点了有动作」）。
 *
 * 为什么这一格值得单独钉住：屏（`:core` 的 `VenueScreen`）把一个**两跳**的意图交给宿主 ——
 * 第一站是站点登录页、之后才是支付页。Android 的内置浏览器能自己串（`BrowserScreen` 的
 * `onPageFinished` + `isAuthHop`），浏览器**不能**（跨源跳转看不见、也接不住）⇒ 宿主必须自己定
 * 一个先后，而且**不许**在拿不到地址时什么都不做（从前 serve 形态就是把空串交给宿主，
 * 点下去无声无息 —— 那一格正是本工单要清掉的）。
 *
 * 三支的判据都在这里：serve 形态（`then` = `/api/venue/pay` 的代跳地址）优先支付页；
 * 只剩登录站时开登录站；两个都空时**返回 null**（由宿主在屏上如实写一句，不是静默）。
 */
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
