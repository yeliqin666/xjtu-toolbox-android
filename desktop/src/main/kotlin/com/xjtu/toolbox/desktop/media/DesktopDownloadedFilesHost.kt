package com.xjtu.toolbox.desktop.media

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.media.DownloadedFile
import com.xjtu.toolbox.media.DownloadedFilesHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLConnection

/**
 * 「文件类下载」（成绩单 / 思源课件 / 资料站文件）在桌面端的宿主 —— 与 `:app` 的
 * `AppDownloadedFilesHost` 对位。
 *
 * ### 为什么不是登记表
 * Android 那份要登记表，是因为 `MediaStore` 里躺着全机的文件，不登记就分不清哪个是我们存的。
 * 桌面这边落盘的位置是**我们自己的一个目录**（`~/Downloads`，见 `DesktopLmsSaver` /
 * `DesktopTranscriptSaver`），目录里有什么就是下载了什么 —— 直接在磁盘上扫，比再养一份登记表
 * 老实（也就不会出现「文件删了但登记还在」或相反的那种半截状态）。
 *
 * ### 列什么、不列什么
 * - 递归扫到 [MAX_DEPTH] 层：思源课件会按课程分文件夹（`~/Downloads/<课程>/<文件>`）。
 * - 跳过点开头的隐藏目录/文件，跳过 `ClassReplay`（那是**回放视频**，下载页另有一组「排队中的回放」
 *   与它的任务登记表，混进来会把同一节课列两遍）。
 * - 最多 [MAX_LIMIT] 条、按修改时间倒序：`~/Downloads` 是用户的目录，可能塞着几个 G 的别的东西，
 *   没有上限的话这一页会被别人的文件淹掉。
 */
object DesktopDownloadedFilesHost : DownloadedFilesHost {

    /**
     * 当前账号（见 [DownloadedFilesHost.accountId]）。桌面这一端**必须有**：
     * `DesktopDownloads` 是按账号一份管理器（`download_tasks<账号后缀>`），换账号之后下载页要整套重读
     * —— 共享屏把这个值当 `LaunchedEffect` 的 key。
     * 文件这一组本身（`~/Downloads`）与账号无关：给的是「这一页服务哪个账号」，不是「列哪些文件」。
     */
    override val accountId: String? get() = AccountContext.activeAccountId

    /** 扫几层子目录。思源课件一般就一层（课程名），留一层余量。 */
    private const val MAX_DEPTH = 3

    /** 最多列几条。 */
    private const val MAX_LIMIT = 300

    /** 用户的下载目录：`$HOME/Downloads`（与两个 saver 用的是同一个约定值）。 */
    private val saveDirectory: File
        get() = File(System.getProperty("user.home") ?: ".", "Downloads")

    override suspend fun list(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        val root = saveDirectory
        if (!root.isDirectory) return@withContext emptyList()
        collect(root, 0)
            .sortedByDescending { it.savedAt }
            .take(MAX_LIMIT)
    }

    override fun open(record: DownloadedFile) {
        // 用系统默认程序打开（xdg-open / open / start，最后退到 AWT 的 Desktop.open）
        openWithSystemProgram(record.uri)
    }

    override suspend fun delete(record: DownloadedFile): Unit = withContext(Dispatchers.IO) {
        runCatching { File(record.uri).delete() }
        Unit
    }

    private fun collect(dir: File, depth: Int): List<DownloadedFile> {
        val entries = dir.listFiles() ?: return emptyList()
        val out = mutableListOf<DownloadedFile>()
        for (e in entries) {
            val name = e.name
            if (name.startsWith(".")) continue
            if (e.isDirectory) {
                if (name == REPLAY_DIR) continue
                if (depth + 1 < MAX_DEPTH) out += collect(e, depth + 1)
                continue
            }
            if (!e.isFile) continue
            out += DownloadedFile(
                name = name,
                mimeType = mimeOf(name),
                // 与 Android 那份不同：这里的 `uri` 是**绝对路径**（桌面没有 content:// 那一套）
                uri = e.absolutePath,
                savedAt = e.lastModified(),
                // 一层子目录 = 思源课件按课程分的夹；根目录下的（成绩单等）算「其他」
                category = if (depth > 0) DownloadedFile.CATEGORY_LMS else DownloadedFile.CATEGORY_OTHER,
            )
        }
        return out
    }

    /** 回放视频目录（下载页那一组自己列，见目录信息弹窗的第二条）。 */
    private const val REPLAY_DIR = "ClassReplay"

    private fun mimeOf(name: String): String =
        runCatching { URLConnection.guessContentTypeFromName(name) }.getOrNull()
            ?: "application/octet-stream"
}
