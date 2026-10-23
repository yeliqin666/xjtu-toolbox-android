package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.browser.shareableUrl
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.webvpn.WebVpnUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内置浏览器（`AppRoute.Browser`）在桌面端的装配验收。 */
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

        assertEquals(
            original.trimEnd('/'),
            shareableUrl(gateway, WebVpnUtil).trimEnd('/'),
            "网关地址要还原成原始地址：$gateway",
        )
    }

    @Test
    fun `去支付带了 then 也只交第一跳 —— 不替用户跳过登录`() {
        val login = "https://org.example.invalid/web/cas/oauth2url.html?appId=1659"
        val pay = "https://venue.example.invalid/pay/show.html?id=VT-假-0001"

        // ⚠️ 这条以前断言「交出去的是支付页」。`then` 的约定是**先登录、再跳**：桌面把地址交出去之后收不到
        // 任何回调，直开 then 就是跳过登录去敲支付页 —— 正是 then 想避免的事。所以现在只交第一跳。
        assertEquals(
            login,
            desktopBrowserTarget(login),
            "桌面收不到回调 ⇒ 只交 OAuth 入口那一跳；第二跳（支付页）由用户在那个站里登录后自己点，屏上如实说明",
        )
        assertNotEquals(pay, desktopBrowserTarget(login), "不许把 then 直开出去（那样等于绕过登录）")
        assertEquals(login, desktopBrowserTarget(login))
        // 空 / 全空白 ⇒ null：本端不编一个假地址，也不假弹一次浏览器（调用方自己决定怎么办）
        assertNull(desktopBrowserTarget(""))
        assertNull(desktopBrowserTarget("   "))
    }

    /** 四处 `is AppRoute.Browser ->` 用的是同一个判据（纯源码断言：这一条不渲染、不弹浏览器）。 */
    @Test
    fun `四处都用同一个判据 —— 没有哪一处又写回 r_url_isNotBlank`() {
        val sites = Regex("""is AppRoute\.Browser ->[^\n]*""")
            .findAll(desktopAppSource())
            .map { it.value.trim() }
            .toList()

        assertEquals(
            4,
            sites.size,
            "`ToolboxDesktopApp.kt` 里 `is AppRoute.Browser ->` 应当是四处" +
                "（日程 / 消息收纳 / 通知公告那三处 onNavigate + 顶层那条挂 `DesktopBrowserScreen` 的分支），" +
                "现在是 ${sites.size} 处：$sites",
        )
        val offenders = sites.filterNot { it.contains("desktopBrowserTarget(") || it.contains("DesktopBrowserScreen(") }
        assertTrue(
            offenders.isEmpty(),
            "这些 `is AppRoute.Browser ->` 分支没有走 `desktopBrowserTarget(...)`（或 `DesktopBrowserScreen(...)`），" +
                "也就是把路由带的 `then` 丢掉了：$offenders",
        )
    }

    /** 仓库根目录：从工作目录往上找 `settings.gradle.kts`（Gradle 的 Test 任务以模块目录为工作目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（settings.gradle.kts）：工作目录是 ${File(".").absolutePath}")
    }

    /** 外壳的源码 —— `is AppRoute.Browser ->` 那四处的判据从它读（与渲染无关，纯文本断言）。 */
    private fun desktopAppSource(): String = File(
        repoRoot(),
        "desktop/src/main/kotlin/com/xjtu/toolbox/desktop/ToolboxDesktopApp.kt",
    ).readText()
}
