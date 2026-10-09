package com.xjtu.toolbox.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import java.awt.Desktop
import java.net.URI

/**
 * `./gradlew :desktop:run` —— 真窗口。
 *
 * 与 `:web` 的 `Main.kt` 一样，这里只做三件事：开窗、给一个 `ViewModelStoreOwner`
 *（`:core` 的屏用 `viewModel { }` 建 ViewModel，桌面不像 Android 那样自带一个）、
 * 套上与 App/Web **同一个** `XJTUToolBoxTheme`。
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
    Window(
        onCloseRequest = ::exitApplication,
        title = "西交工具箱（桌面预览 · 阶段 0）",
        // 手机比例起步：这一轮的屏都是按竖屏布局写的（阶段 A 起才会为桌面做布局）
        state = rememberWindowState(size = DpSize(520.dp, 900.dp)),
    ) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            XJTUToolBoxTheme {
                ToolboxDesktopApp()
            }
        }
    }
}

/**
 * 用系统浏览器打开外链。
 *
 * 与 `:web` 的 `openInNewTab` 同义 —— 桌面端「内置浏览器」这条路不存在（`:app` 那个 WebView
 * 带着站点会话，桌面没有），所以只做浏览器做得到的那件：把网址交出去。
 * `Desktop.browse` 在没有桌面环境的机器上会抛 `UnsupportedOperationException`，静默吞掉
 *（与 Web 那边「新标签被弹窗拦截器拦下 = 什么都没发生」同一种如实降级）。
 */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
