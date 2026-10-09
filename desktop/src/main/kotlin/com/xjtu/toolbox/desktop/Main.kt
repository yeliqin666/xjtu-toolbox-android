package com.xjtu.toolbox.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import io.ktor.client.HttpClient
import java.awt.Desktop
import java.net.URI


/**
 * **免登录接口用的共享 Ktor 客户端**（现在只有黄页）。
 *
 * 黄页是**匿名可读**的接口（`:core` 的 `YellowPageApi` 里不带任何凭据），所以它不需要走会话内核
 * 那条链路（cookie jar / WebVPN 改写 / 重认证）—— 与 `:app` 的 `appYellowPageApi` 同一条口径：
 * 一条不带 CookieJar 的 `createToolboxClient()`。
 *
 * 进程级一份（`by lazy`）：别每次重组都新建一个连接池 —— `:app` 那份也是进程级一份。
 * 后面再接免登录的屏（教师检索 / 公告正文）时，它们共用这一条。
 */
internal val yellowPageClient: HttpClient by lazy { createToolboxClient() }
/**
 * `./gradlew :desktop:run` —— 真窗口。
 *
 * 与 `:web` 的 `Main.kt` 一样，这里只做三件事：开窗、给一个 `ViewModelStoreOwner`
 *（`:core` 的屏用 `viewModel { }` 建 ViewModel，桌面不像 Android 那样自带一个）、
 * 套上与 App/Web **同一个** `XJTUToolBoxTheme`。数据与会话装配在 [DesktopAuth] 里。
 *
 * 没有显示服务器的机器上这个 main 起不来（`DISPLAY` 为空时 AWT 直接失败）——
 * 那种场合要看渲染结果，用 `./gradlew :desktop:renderScreens`（离屏 · 同一套 skiko 渲染栈）。
 */
fun main() = application {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    // 会话管家 + 凭据存储 + 登录状态。建在这里（而不是某个屏里）是因为它必须活得比任何一屏长：
    // 登录发生在登录页，取数发生在图书馆屏，MFA 弹窗挂在整棵树上，三者共用同一个管家。
    val auth = remember { DesktopAuth() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "西交工具箱（桌面预览 · Stage A）",
        // 手机比例起步：共享屏大多还是按竖屏布局写的（桌面的宽布局属于后面的阶段）
        state = rememberWindowState(size = DpSize(520.dp, 900.dp)),
    ) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            XJTUToolBoxTheme {
                ToolboxDesktopApp(auth)
            }
        }
    }
}
/**
 * 用系统浏览器打开外链（教师检索那一屏的「在浏览器中打开主页」用它）。
 *
 * 与 `:web` 的 `openInNewTab` 同义 —— 桌面端「内置浏览器」这条路不存在（`:app` 那个 WebView
 * 带着站点会话，桌面没有），所以只做浏览器做得到的那件：把网址交出去。
 * `Desktop.browse` 在没有桌面环境的机器上会抛 `UnsupportedOperationException`，静默吞掉
 *（与 Web 那边「新标签被弹窗拦截器拦下 = 什么都没发生」同一种如实降级）。
 *
 * ⚠️ 它曾经在 Stage A 第二步被删过（当时它的调用点全在 campus-api 脚手架屏上，那些屏接不上
 * 数据源 ⇒ 成了死代码）；教师检索搬进来之后它又有了真实调用点，所以又回来了。
 */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
