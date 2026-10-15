package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.browser.shareableUrl
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 内置浏览器（`AppRoute.Browser`）在桌面端的**装配验收**。
 *
 * 这一条路由的屏在 `:app` 是 `browser/BrowserScreen.kt`（629 行）：WebView + 自己的顶栏
 * （地址栏 / 前进后退 / 复制 / 分享 / 在外部浏览器打开）+ cookie 注入 + `then=` 那一跳 + 夜间模式。
 * 桌面端没有 WebView 这一层，等于物是**外链中转页**（`DesktopBrowser.kt`：目标网址 + 交给系统浏览器 +
 * 复制），所以这里钉的是三件装配事实：
 *
 *  1. **进得了、列得出**：`AppRoute.Browser` 在 `DESKTOP_SUPPORTED_ROUTES` 里（索引页那一栏
 *     「真能用」会列出它），名字照 `:app` 那一屏自己的标题（`BrowserScreen` 的 `pageTitle` 初值
 *     「浏览器」）；它也不落 `NotPortedScreen`（`DesktopPage` 的顶层 `when` 有 `is AppRoute.Browser ->`
 *     真分支 —— 分支覆盖的静态对比见提交说明，渲染那半边由证据运行 `browser.png` 的 `hasText` 钉住）。
 *  2. **交出去的地址是同一份口径**：桌面点「在系统浏览器打开」用的是 `:app` 顶栏那颗
 *     「在外部浏览器打开」同一条规则 —— `:core` 的 `browser/ShareableUrl.kt`（本轮按五步法从
 *     `:app` 搬进来），注入的实现是 `:data` 的 [WebVpnUtil]（Android 与桌面**同一份**）。
 *  3. **带 `then` 的跳转交出去的是第二跳**：「去支付」那一枪（`VenueScreen.onOpenBrowser(登录站, 支付页)`）
 *     `:app` 是在内置浏览器里串两跳，而 `openInBrowser` 一次只交一个地址、也收不到任何回调 ⇒
 *     桌面按 [desktopBrowserTarget] 的判据交 `then`（支付页）出去，见底下那一条用例。
 *
 * ⚠️ 夹具全是编造的：域名用 `.invalid`（保留 TLD，永远解析不出来），一个请求都不发 ——
 * 这一条链只做字符串改写，`openInBrowser` 那一枪（`java.awt.Desktop.browse` / `xdg-open`）在
 * 测试里不点，绝不真去弹一台浏览器。
 */
class DesktopBrowserJvmTest {

    @Test
    fun `中转页在「真能用」那张表里（名字照 app 那一屏的标题）`() {
        val entry = assertNotNull(
            DESKTOP_SUPPORTED_ROUTES.firstOrNull { it.first == AppRoute.Browser() },
            "AppRoute.Browser 必须进 DESKTOP_SUPPORTED_ROUTES，否则索引页列不出它",
        )
        assertEquals(
            "浏览器",
            entry.second,
            "名字照 :app 侧那一屏自己的标题（`BrowserScreen` 的 `pageTitle` 初值就是「浏览器」）",
        )
        assertTrue(
            AppRoute.Browser().id in DESKTOP_OPENABLE_ROUTE_IDS,
            "首页宫格那张「真能打开」的表也要有它（它由 DESKTOP_SUPPORTED_ROUTES 长出来）",
        )
    }

    @Test
    fun `普通网址原样交出去`() {
        val plain = "https://notice.example.invalid/2026/summer-camp"
        assertEquals(plain, shareableUrl(plain, WebVpnUtil))
        // 空串（`:app` 的深链写坏了、或索引页直接点进来）也原样 —— 不把一个假的地址交出去
        assertEquals("", shareableUrl("", WebVpnUtil))
    }

    @Test
    fun `WebVPN 网关地址换回原始地址 —— 与 app 侧的「在外部浏览器打开」同一条规则`() {
        val original = "https://notice.example.invalid/2026/summer-camp"
        val gateway = WebVpnUtil.toVpnUrl(original)
        assertNotEquals(original, gateway, "夹具网址应当编得出网关地址（否则这一支什么都没测到）")
        assertTrue(gateway.startsWith("https://webvpn.xjtu.edu.cn/"), "网关地址形状不对：$gateway")

        // 网关链路对方没有会话打不开、也看不出是哪个网站 ⇒ 交出去的是还原后的原始地址。
        // 比到「路径结尾的那个 /」为止：根路径的尾斜杠会掉，那是搬迁前就有的口径（见
        // `DesktopWebVpnCodecJvmTest` 里那条同样的说明）。
        assertEquals(
            original.trimEnd('/'),
            shareableUrl(gateway, WebVpnUtil).trimEnd('/'),
            "网关地址要还原成原始地址：$gateway",
        )
    }

    @Test
    fun `去支付带了 then 时走第二跳 —— 交出去的是支付页`() {
        val login = "https://org.example.invalid/web/cas/oauth2url.html?appId=1659"
        val pay = "https://venue.example.invalid/pay/show.html?id=VT-假-0001"

        assertEquals(
            pay,
            desktopBrowserTarget(login, pay),
            "`:app` 是两跳（先 OAuth 入口，`isAuthHop` 判完再 loadUrl(支付页)）；" +
                "`openInBrowser` 只交一个地址、收不到回调 ⇒ 桌面的等价物是第二跳那个地址",
        )
        // 没有 then 时才回第一站：只剩登录站可去时也得有个动作（把这一枪吞掉就是静默没动作）
        assertEquals(login, desktopBrowserTarget(login, ""))
        // 两个都空 ⇒ null：本端不编一个假地址，也不假弹一次浏览器（调用方自己决定怎么办）
        assertNull(desktopBrowserTarget("", ""))
        assertNull(desktopBrowserTarget("   ", "\t"))
    }
}
