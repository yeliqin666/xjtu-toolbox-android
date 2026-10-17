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
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import io.ktor.client.HttpClient
import java.awt.Desktop
import java.net.URI


/** 免登录接口用的共享 Ktor 客户端（现在只有黄页）。 */
internal val yellowPageClient: HttpClient by lazy { createToolboxClient() }
/** `./gradlew :desktop:run` —— 真窗口。 */
fun main() = application {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    runCatching { DesktopArchiveHost.maybeImportPendingArchive() }
    // 会话管家 + 凭据存储 + 登录状态。建在这里（而不是某个屏里）是因为它必须活得比任何一屏长：
    // 登录发生在登录页，取数发生在图书馆屏，MFA 弹窗挂在整棵树上，三者共用同一个管家。
    val auth = remember { DesktopAuth() }
    DesktopFeedback.install()
    Window(
        onCloseRequest = ::exitApplication,
        title = "西交工具箱（桌面预览 · Stage A）",
        state = rememberWindowState(size = DpSize(520.dp, 900.dp)),
    ) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            XJTUToolBoxTheme {
                ToolboxDesktopApp(auth)
            }
        }
    }
}
/** 用系统浏览器打开外链（教师检索那一屏的「在浏览器中打开主页」用它）。 */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
