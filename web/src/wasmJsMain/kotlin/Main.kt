// ⚠️ `main()` 必须留在**根包**（文件不写 package 声明）：Kotlin/Wasm 的 executable 从模块根部找 main()。
// 参考：miuix-ref/example/web 与 J1900 上已跑通的 campus-web。

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.xjtu.toolbox.web.ToolboxWebApp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeApp") {
        // 与 Android 端同一个主题控制器：观感一致是结构性的（同一套 MIUIX）
        MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
            ToolboxWebApp()
        }
    }
}
