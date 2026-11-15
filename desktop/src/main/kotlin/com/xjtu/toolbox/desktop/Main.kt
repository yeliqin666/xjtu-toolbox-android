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
    // `:server` 的 serve，或者两个桌面窗口）。所以**在碰任何存储之前**先抢 `<数据根>/.lock`：
    // 抢不到就把写闸合上（`StoreWriteGate`），顶栏挂一条横幅说明这一端只读。
    // 抢锁必须在这里、而不是在某个屏里：下面 `DesktopArchiveHost.maybeImportPendingArchive()` 就要写盘了。
    val lock = InstanceLock.acquire("desktop")
    if (!lock.isOwner) StoreWriteGate.goReadOnly(lock.readOnlyReason.orEmpty())
    val readOnlyReason = lock.readOnlyReason

    application {
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
        // 小游戏全服榜：桌面没有 BuildConfig，直接用正式环境（公开地址，不是密钥）
        remember { com.xjtu.toolbox.game.board.LeaderboardApi.install(com.xjtu.toolbox.game.board.LeaderboardApi.DEFAULT_BASE_URL) }
        Window(
            onCloseRequest = ::exitApplication,
            // 只读那一档在窗口标题上也要看得出来（横幅可能被滚走，标题栏一直在）
            title = "西交工具箱（桌面预览 · Stage A）" + if (readOnlyReason == null) "" else " · 只读",
            state = rememberWindowState(size = DpSize(520.dp, 900.dp)),
        ) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                XJTUToolBoxTheme {
                    ToolboxDesktopApp(auth, readOnlyReason = readOnlyReason)
                }
            }
        }

        // 窗口全关了才走到这里：把锁还回去（进程被 kill 时锁会留下，下次启动靠 pid 判僵尸抢回来）
        lock.release()
    }
}
/** 用系统浏览器打开外链（教师检索那一屏的「查看主页原网页」用它）。 */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
