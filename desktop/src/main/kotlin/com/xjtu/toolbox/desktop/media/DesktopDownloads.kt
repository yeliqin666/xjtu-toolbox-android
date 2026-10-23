package com.xjtu.toolbox.desktop.media

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.lms.DesktopLmsSaver
import com.xjtu.toolbox.media.DownloadDirInfo
import com.xjtu.toolbox.media.DownloadManager
import com.xjtu.toolbox.media.JvmDownloadEngine
import com.xjtu.toolbox.media.JvmDownloadFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 桌面端把 `:core` 的 [DownloadManager] 装起来的那一处（与 `:app` 的 `AppDownloadManager` 对位）。
 *
 * ⚠️ **一个账号一份**：登记表落在 `download_tasks<账号后缀>`（见 [DesktopDownloadTaskStore]），
 * 而 [DownloadManager] 构造时把登记表读成内存快照、之后不再重读 —— 所以「全进程一份单例」
 * 会让切账号后的下载页继续读写**上一个账号**的登记表。这里改成按账号取：
 * [manager] 每次读的是当前账号对应的那一份（账号换了就换成新账号那一份，新动作走新账号）。
 *
 * 为什么不「账号一变就把旧实例丢掉」：正在跑的任务还挂在旧实例的作用域里，它跑完要**落回原账号**
 * 的登记表；丢掉实例、之后再切回原账号又新建一份，就会有两份实例各持一份内存快照互相覆盖落盘
 * （丢记录）。所以每个账号的实例留着（数量 = 用过的账号数，不是任务数），切账号只换「当前用哪一份」。
 */
object DesktopDownloads {

    /** 账号 ID（`null` 归一成空串 = 匿名空间）→ 那个账号的那一份管理器。 */
    private val managers = mutableMapOf<String, DownloadManager>()

    /** 当前账号的那一份（下载页读的就是它）。 */
    val manager: DownloadManager get() = managerFor()

    /** 某个账号的那一份；同一个账号反复问拿到的始终是同一个实例（内存快照与在跑的任务只有一份）。 */
    fun managerFor(accountId: String? = AccountContext.activeAccountId): DownloadManager =
        synchronized(managers) {
            managers.getOrPut(accountId.orEmpty()) { newManager(accountId) }
        }

    private fun newManager(accountId: String?) = DownloadManager(
        // 账号在建这一份时**定死**：store 的文件名是 `download_tasks<账号后缀>`，
        // 显式传 null 得到的是 `download_tasksdefault`（不跟着「建实例那一刻」的 activeAccountId 变，见 AccountContext.suffixFor）。
        store = DesktopDownloadTaskStore(accountId),
        engine = JvmDownloadEngine(),
        fileSystem = JvmDownloadFileSystem(),
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    /** 下载目录信息弹窗的桌面文案（与 `:app` 的 `appDownloadDirInfo()` 对位）。 */
    val dirInfo: DownloadDirInfo get() = DownloadDirInfo(
        filesLocation = DesktopLmsSaver.publicPathLabel(),
        notes = listOf(
            "成绩单、思源课件、资料站文件保存到 ~/Downloads 下（与系统约定的默认下载目录一致）",
            "这一页的文件组列的正是 ~/Downloads 里现有的文件（含课程子目录），最多 300 个；同级同名文件会互相覆盖，与文件夹里看到的一致",
            "课堂回放视频保存在 ~/Downloads/ClassReplay（目录名与 Android 那条一样），可以用任意播放器打开；它不混在文件组里，由上面那组任务列",
            "删除记录只删除这里的记录，不删除视频文件",
            "清理模式下可勾选\"同时删除文件\"彻底删除；文件组里的\"删除\"删的是磁盘上那份文件本身",
        ),
    )
    /** 用系统默认程序打开一个已下载到本地的文件。 */
    fun openDownloadedFile(path: String): Boolean {
        if (path.isBlank() || !java.io.File(path).exists()) return false
        return openWithSystemProgram(path)
    }
}
