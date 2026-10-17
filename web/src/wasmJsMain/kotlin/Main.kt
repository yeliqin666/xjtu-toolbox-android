// ⚠️ `main()` 必须留在**根包**（文件不写 package 声明）：Kotlin/Wasm 的 executable 从模块根部找 main()。
// 参考：miuix-ref/example/web 与 J1900 上已跑通的 campus-web。

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import com.xjtu.toolbox.web.ToolboxWebApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeApp") {
        var appearanceTick by remember { mutableIntStateOf(0) }
        val settings = remember { SettingsStore() }
        XJTUToolBoxTheme(
            darkModeOverride = remember(appearanceTick) { settings.darkMode },
            content = { ToolboxWebApp(onAppearanceChanged = { appearanceTick++ }) },
        )
    }
}
