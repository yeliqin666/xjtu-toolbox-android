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
        // 与 Android 端**同一个主题包裹**（`:core` 的 XJTUToolBoxTheme：深浅色覆盖 + 系统栏切口）。
        // 上一版这里是裸的 `MiuixTheme(ThemeController(ColorSchemeMode.System))` —— MIUIX 虽然同源，
        // 但少了 :core 那层包裹，界面风格选项（浅色/深色/跟随系统）与系统栏处理就跟 App 不是一条路。
        //
        // 深色模式跟着 `:core` 的 `SettingsStore` 走（wasm 上落 localStorage，键与 :app 的
        // `app_settings` 逐字相同）：设置屏（底栏第五格「我的」→ 共享设置屏）改完会调
        // `onAppearanceChanged`，这里重新读一次 —— 键值缝没有监听器，所以由这一戳叫醒，
        // 与 `:desktop` 那份（`ToolboxDesktopApp` 的 `appearanceTick`）同一个写法。
        var appearanceTick by remember { mutableIntStateOf(0) }
        val settings = remember { SettingsStore() }
        XJTUToolBoxTheme(
            darkModeOverride = remember(appearanceTick) { settings.darkMode },
            content = { ToolboxWebApp(onAppearanceChanged = { appearanceTick++ }) },
        )
    }
}
