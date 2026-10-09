// ⚠️ `main()` 必须留在**根包**（文件不写 package 声明）：Kotlin/Wasm 的 executable 从模块根部找 main()。
// 参考：miuix-ref/example/web 与 J1900 上已跑通的 campus-web。

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.web.ToolboxWebApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeApp") {
        // 与 Android 端**同一个主题包裹**（`:core` 的 XJTUToolBoxTheme：深浅色覆盖 + 系统栏切口）。
        // 上一版这里是裸的 `MiuixTheme(ThemeController(ColorSchemeMode.System))` —— MIUIX 虽然同源，
        // 但少了 :core 那层包裹，界面风格选项（浅色/深色/跟随系统）与系统栏处理就跟 App 不是一条路。
        XJTUToolBoxTheme {
            ToolboxWebApp()
        }
    }
}
