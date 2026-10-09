package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.library.LibraryScreen
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * `./gradlew :desktop:renderScreens` —— 把 `:core` 的屏**离屏**渲染成 PNG。
 *
 * ## 它回答哪条验收
 *
 * 阶段 0 的验收之一是「`:core` 的屏在 Linux 桌面**真渲染**；MIUIX 桌面观感/鼠标/输入法可接受」。
 * 这台机器没有显示服务器（`DISPLAY` 为空），真窗口起不来；而「能编译」证明不了渲染。
 * 所以这里用 [ImageComposeScene]——Compose Desktop 自己的截图设施：同一个 skiko/Skia 渲染栈、
 * 同一套 MIUIX 组件、同一份布局代码，只是把结果画进位图而不是画到屏幕上。
 *
 * ## ⚠️ 为什么每次 render 都必须**在 AWT 事件队列上**调用
 *
 * `:core` 的屏一律用 `viewModel { }` + `viewModelScope`，而 viewModelScope 跑在
 * `Dispatchers.Main.immediate` 上 —— 桌面端的 Main 就是 AWT 事件队列（EDT）。
 * 真窗口里这没问题：Compose Desktop 的渲染循环本身就在 EDT 上。
 * 但离屏场景是在**调用线程**上渲染的，如果那个线程不是 EDT，就会出现「VM 在 EDT 上更新状态、
 * 渲染线程同时在 measure/layout」⇒ Skia 直接抛
 * `performMeasureAndLayout called during measure layout`（实测踩到，且表现为「屏上永远没有数据」）。
 *
 * 所以这里的每一条 `render()` 都通过 `invokeAndWait` 排到 EDT 上执行，帧与帧之间在调用线程上
 * `sleep` —— 那段空档正是 EDT 跑 VM 协程的时间。
 *
 * ## 四张图
 *
 * | 文件 | 内容 | 证明什么 |
 * |---|---|---|
 * | `library-demo.png` | 图书馆屏 + [DemoLibrarySource] 的固定数据 | 有数据的屏能真画出来，且可复现（不依赖网络/会话） |
 * | `library-live.png` | 同一个图书馆屏，取数换成 campus-api（生产这条路） | 这一端接**真**图书馆数据的样子 |
 * | `shell-live.png` | 整个桌面外壳（默认落在校历屏） | 外壳 + MIUIX 底栏 + 真数据 |
 * | `routes.png` | 「全部页面」索引页 | 导航与 MIUIX 文本按钮的观感 |
 *
 * `exitProcess(0)` 是必要的：skiko 与 AWT 的事件线程不是守护线程，不主动退出这个 JavaExec 会挂着。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }

    fun shot(name: String, width: Int = 520, height: Int = 900, frames: Int = 12, content: @Composable () -> Unit) {
        // 每个场景一个独立的 ViewModelStoreOwner：:core 的屏用 `viewModel { }` 建 VM，
        // 桌面不像 Android 那样自带一个（与 Main.kt 里给真窗口装的是同一个东西）。
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val scene = onEdt {
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    XJTUToolBoxTheme { content() }
                }
            }
        }
        try {
            var image = onEdt { scene.render(0L) }
            repeat(frames) { i ->
                // 帧间让出调用线程：EDT 就在这段空档里把 VM 的取数协程跑完
                Thread.sleep(400)
                image = onEdt { scene.render((i + 1) * 400_000_000L) }
            }
            val bytes = requireNotNull(image.encodeToData()) { "PNG 编码失败：$name" }.bytes
            val file = File(outDir, name)
            file.writeBytes(bytes)
            println("wrote ${file.absolutePath} (${bytes.size} bytes)")
        } finally {
            onEdt { scene.close() }
        }
    }

    // ① 图书馆屏（假数据）：布局、座位格、区域标签、「我的预约」卡都该在图里
    shot("library-demo.png") {
        LibraryScreen(
            source = DemoLibrarySource(),
            onBack = {},
            showFirstUseHint = false,
            onFirstUseHintRead = {},
        )
    }

    // ② 图书馆屏（真数据：本机 campus-api 的只读图书馆模块）—— 同一屏、同一份代码，
    //    区别只是取数换成了这一端在生产里用的那条路
    shot("library-live.png") {
        LibraryScreen(
            source = com.xjtu.toolbox.core.net.CampusLibraryApi(desktopCampusClient(), SCAFFOLD_API_BASE),
            onBack = {},
            showFirstUseHint = false,
            onFirstUseHintRead = {},
        )
    }

    // ③ 整个外壳（真数据；默认落在校历屏）
    shot("shell-live.png") { ToolboxDesktopApp() }

    // ④ 路由索引页（底栏那 6 格的最后一格）
    shot("routes.png") {
        RoutesPage(desktopCampusClient()) { }
    }

    exitProcess(0)
}

/**
 * 在 AWT 事件队列上执行并等结果（见文件头的 KDoc：**所有 Compose 调用都必须落在 EDT 上**）。
 *
 * `SwingUtilities.invokeAndWait` 自己会把异常包进 `InvocationTargetException`，
 * 这里解开一层，好让失败的堆栈指到真正的出错位置。
 */
private fun <T> onEdt(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            failure = t
        }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
