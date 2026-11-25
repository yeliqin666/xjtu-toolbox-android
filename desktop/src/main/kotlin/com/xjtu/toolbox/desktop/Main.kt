package com.xjtu.toolbox.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.xjtu.toolbox.desktop.media.openWithSystemProgram
import com.xjtu.toolbox.platform.showBriefMessage
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.xjtu.toolbox.desktop.archive.DesktopArchiveHost
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.platform.InstanceLock
import com.xjtu.toolbox.platform.StoreWriteGate
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import io.ktor.client.HttpClient
import java.awt.Desktop
import java.net.URI


/** 免登录接口用的共享 Ktor 客户端（现在只有黄页）。 */
internal val yellowPageClient: HttpClient by lazy { createToolboxClient() }
/** `./gradlew :desktop:run` —— 真窗口。 */
fun main() {
    // ── 单实例闸门（B-13）──
    //
    // 键值存储是「init 整份读进内存、每次改动整份回写」的，两个进程同开就是互相覆盖（桌面窗口 +
    // `:server` 的 serve，或者两个桌面窗口）。所以**在碰任何存储之前**先抢数据根目录的进程锁：
    // 抢不到就把写闸合上（`StoreWriteGate`），顶栏挂一条横幅说明这一端只读。
    // 抢锁必须在这里、而不是在某个屏里：下面 `DesktopArchiveHost.maybeImportPendingArchive()` 就要写盘了。
    val lock = InstanceLock.acquire("desktop")
    if (!lock.isOwner) StoreWriteGate.goReadOnly(lock.readOnlyReason.orEmpty())
    val readOnlyReason = lock.readOnlyReason

    // 启动时只跑一次的事放在组合之外：写在 application {} 里每次重组都会再跑
    val archiveError = runCatching { DesktopArchiveHost.maybeImportPendingArchive() }.exceptionOrNull()
    DesktopFeedback.install()
    // 小游戏全服榜：桌面没有 BuildConfig，直接用正式环境（公开地址，不是密钥）
    com.xjtu.toolbox.game.board.LeaderboardApi.install(com.xjtu.toolbox.game.board.LeaderboardApi.DEFAULT_BASE_URL)
    val initialSize = initialWindowSize()

    application {
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
            // 只读那一档在窗口标题上也要看得出来（横幅可能被滚走，标题栏一直在）
            title = "岱宗盒子" + if (readOnlyReason == null) "" else "（只读）",
            state = rememberWindowState(size = initialSize, position = WindowPosition(Alignment.Center)),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT) }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                XJTUToolBoxTheme {
                    ToolboxDesktopApp(auth, readOnlyReason = readOnlyReason)
                }
            }
            LaunchedEffect(archiveError) {
                archiveError?.let { showBriefMessage("待导入的数据存档没有导入成功：${it.message ?: it::class.simpleName}") }
            }
        }

    }
}
private const val MIN_WINDOW_WIDTH = 360
private const val MIN_WINDOW_HEIGHT = 520

/** 默认 520×900，但不超过屏幕可用区域（768p、高缩放的屏上 900 会把底栏挤出屏幕）。 */
private fun initialWindowSize(): DpSize {
    val usable = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val height = usable?.height?.minus(16)?.coerceIn(MIN_WINDOW_HEIGHT, 900) ?: 900
    return DpSize(520.dp, height.dp)
}

/** 用系统浏览器打开外链；AWT 打不开就换系统命令，再不行把链接放进剪贴板并说一句。 */
internal fun openInBrowser(url: String) {
    val opened = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            false
        }
    }.getOrDefault(false) || openWithSystemProgram(url)
    if (!opened) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
        }
        showBriefMessage("没能打开浏览器，链接已复制，请粘贴到浏览器打开")
    }
}
