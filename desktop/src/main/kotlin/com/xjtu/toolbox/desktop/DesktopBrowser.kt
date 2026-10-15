package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.browser.shareableUrl
import com.xjtu.toolbox.webvpn.WebVpnCodec
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * `AppRoute.Browser` 在桌面端的**等价物** —— 外链中转页（把地址交给系统浏览器）。
 *
 * ## 为什么是这一页
 *
 * 这条路由的屏在 `:app` 是 `browser/BrowserScreen.kt`（629 行）：一个 Android WebView，外面套
 * 自己的顶栏（地址栏 / 关闭 / 更多菜单：刷新、前进、输入网址、复制链接、分享、在外部浏览器打开）、
 * cookie 注入（把 App 已登好的校园会话同步进 WebView）、`then=` 的「登录完自动去下一站」、
 * 夜间模式注入（[com.xjtu.toolbox.browser.WebViewNightMode]）。桌面端没有 WebView 这一层
 * —— Compose Desktop 的渲染栈是 skiko/Skia，依赖里也没有 JCEF / JavaFX WebView
 *（`gradle/libs.versions.toml` 里没有），整个域里只有 `shareableUrl` 那条纯规则搬得动（见下）。
 *
 * 所以这一端只做那条路由**真正交出去**的那一件事：**地址交给系统浏览器**，本窗给去向。
 * 与另两处同一套口径：`DesktopPayPage`（宿舍电费：桌面没有内置浏览器 ⇒ 交系统浏览器）、
 * `AppRoute.WebVpnConverter` 的「用 WebVPN 打开」。
 *
 * ## 三处如实，不假装
 *
 *  1. **没有内嵌浏览器**：地址栏、前进后退、cookie 注入、夜间模式、`then=` 那一跳，这一端一个字
 *     都不画 —— 它们全都长在 WebView 上。
 *  2. **不自己弹浏览器**：这一屏要人点「在系统浏览器打开」才交出去（`DesktopPayPage` 那种
 *     「拿到地址就打开」是因为那是缴费流程的下一跳；这条路是深链/索引页点进来的，一进屏就劫走
 *     用户的焦点不是这条路由的意思）。
 *  3. **交出去的地址与 `:app` 同一条口径**：`:app` 顶栏那颗「在外部浏览器打开」用的是
 *     `shareableUrl`（WebVPN 代理地址换回原始地址 —— 网关链路对方没有会话打不开，也看不出是哪个
 *     网站）。那条规则本轮按五步法搬进了 `:core`（`browser/ShareableUrl.kt`，判据
 *     `:core:commonTest` 的 `ShareableUrlTest`），本页与 Android 用的是**同一份**。
 *
 * @param url 路由带的地址（`AppRoute.Browser.url`；可能为空 —— 深链写坏了、或从索引页点进来）。
 * @param then 路由带的 `then`（`:app` 在第一个页面加载完、离开登录页之后自动跳过去）。这一端做不到，
 *   页上如实说明；不为它编一个做不到的按钮。
 * @param codec 判定 + 改写的那一半（`:data` 的 `WebVpnUtil`，与 `WebVpnConverterScreen` 那一屏注入的
 *   是同一个对象）。
 * @param onNavigate 这一端没有返回栈（底栏就是导航）⇒ 页脚照 `NotPortedScreen` 的写法给「回全部页面」。
 */
@Composable
internal fun DesktopBrowserScreen(
    url: String,
    then: String,
    codec: WebVpnCodec,
    onNavigate: (DesktopTarget) -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    // 交给系统浏览器的地址（`:app` 的「在外部浏览器打开」同一条口径）；`url` 原样留在页上，
    // 两者不同时（网关地址）两句都画出来 —— 用户看得见「我复制的/交出去的是哪一个」。
    val external = remember(url, codec) { shareableUrl(url, codec) }
    val clipboard = LocalClipboardManager.current
    var copied by remember(url) { mutableStateOf(false) }

    Column(
        // 自己画底：离屏证据图里没有窗口，不画的话深色模式下会变成「浅字透明底」（与
        // `HostUnavailableScreen` 同一条理由）。
        modifier = Modifier.fillMaxSize().background(cs.background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("浏览器", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("桌面端没有内嵌浏览器", color = cs.primary, fontSize = 13.sp)
        Text(
            "这条路由在 :app 是 WebView 里的内置浏览器（地址栏 / 前进后退 / 注入 App 的校园会话 / " +
                "夜间模式），桌面端没有 WebView 这一层（Compose Desktop 的渲染栈是 skiko/Skia，" +
                "依赖里也没有 JCEF / JavaFX WebView）⇒ 这一端只做「交出去」那件事：" +
                "把地址交给系统浏览器，本窗给去向。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )

        if (url.isBlank()) {
            Text("这条路由没带目标网址", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "AppRoute.Browser 的 url 是空的（深链里没写 url=、或从索引页直接点进来）。" +
                    "正常进来的路是通知里的外链、教师主页的「在浏览器中打开」、消息与屁岱里的链接。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        } else {
            Text("目标网址", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(url, color = cs.onBackgroundVariant, fontSize = 12.sp)
            if (external != url) {
                Text(
                    "这是 WebVPN 网关地址（webvpn.xjtu.edu.cn）—— 系统浏览器没有本 App 的网关会话，" +
                        "所以交给它的是还原后的原始地址（与 :app 那颗「在外部浏览器打开」同一条口径）：",
                    color = cs.onBackgroundVariant,
                    fontSize = 12.sp,
                )
                Text(external, color = cs.primary, fontSize = 12.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "在系统浏览器打开",
                    onClick = { openInBrowser(external) },
                    minWidth = 160.dp,
                )
                TextButton(
                    text = if (copied) "已复制" else "复制链接",
                    onClick = {
                        clipboard.setText(AnnotatedString(external))
                        copied = true
                    },
                    minWidth = 120.dp,
                )
            }
            Text(
                // 「不会自己弹浏览器」写出来：`openInBrowser` 那一枪交给系统（xdg-open / open / start），
                // 成功与否由用户机器上的默认浏览器说了算（与 `SystemOpen.kt` 同一条口径）。
                "这一屏不会自己弹浏览器：点上面那颗按钮才把地址交出去，之后由系统的默认浏览器接手。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        }

        if (then.isNotBlank()) {
            Text(
                "这条路由还带了 then=…（:app 在页面加载完、走完登录之后自动跳下一站）。" +
                    "那个动作发生在内置浏览器里 —— 本端没有那一层，跳不过去，如实说明。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        }

        TextButton(
            text = "回全部页面",
            onClick = { onNavigate(DesktopTarget.Routes) },
            minWidth = 120.dp,
        )
    }
}

/**
 * 「带 then 的」跳转，该把哪一个地址交给系统浏览器（纯函数：宿主与单测同一份判据）。
 *
 * `:app` 的语义是**两跳**：先把第一站（站点 OAuth 入口）交给内置浏览器，页面加载完、`isAuthHop` 判明
 * 已经离开登录中转页之后，再 `normalizeUrl(then)` 落到支付页。桌面端做不到那两跳：`openInBrowser`
 * 一次只把一个地址交给系统浏览器，而且**收不到任何回调**（页面在别人的进程里，跳没跳、跳去哪
 * 都一样看不见）⇒ 这一端只能挑一个地址。
 *
 * 挑的是 `then`（第二跳，也就是用户真要去的支付页 / 深链想去的那一页）：`then` 非空就交它 —— 那正是
 * Android 走完两跳之后停下来的那一页。站点在系统浏览器里没有会话时会**自己**先要一次登录（`:web`
 * 那一端把同一条写成「不担保」的第三条，见 `WebVenueHost.kt` 的文件头），本端不代它做这件事。
 *
 * 三种写法（顺序就是优先级）：
 *  - `then` 非空 ⇒ 交它（界面上的「去支付」就是要那一页）；
 *  - 否则第一站非空 ⇒ 交它（只剩下登录站可去时也得有个动作 —— 把这一枪吞掉就是静默没动作）；
 *  - 两个都空 ⇒ `null`，由调用方决定 —— 本端**不编**一个假地址。
 *
 * ## 四处调用它（同一个判据，不许再在那里丢掉 `then`）
 *
 * | 那一处 | 交的是谁 |
 * |---|---|
 * | 场馆「去支付」（`VenueScreen.onOpenBrowser(url, then)`） | 两跳的**第二跳**（支付页）—— 这是它最初的由来（f38c2fc8） |
 * | 日程 / 消息收纳 / 通知公告里 `is AppRoute.Browser ->` 那三处 `onNavigate` | 路由自带的那个 `then`（`AppRoute.Browser(url, then)`） |
 *
 * 那三处今天收到的路由 `then` 都是空串：课表那一族根本不造 `AppRoute.Browser`，而消息收纳 / 通知公告 / 屁岱的链接 / `OwnInbox` 造它时都只传 url。所以那三处现在是**行为不变、口径统一**
 * —— 哪天上游带上 `then`，这里不会再把它丢掉（那正是 f38c2fc8 修掉的那个偏差）。
 */
internal fun desktopBrowserTarget(url: String, then: String): String? = when {
    then.isNotBlank() -> then
    url.isNotBlank() -> url
    else -> null
}
