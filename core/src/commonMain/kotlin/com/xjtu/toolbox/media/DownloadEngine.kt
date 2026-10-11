package com.xjtu.toolbox.media

/**
 * 一次传输请求 —— [DownloadEngine.download] 的参数。
 */
data class DownloadRequest(
    val url: String,
    /** 本地目标文件（含目录）。目录由 [DownloadFileSystem.ensureDirectory] 在排队时就建好。 */
    val filePath: String,
    /** 本地已有的字节数；> 0 时实现方应当带 `Range` 续传（服务器不认就从头写）。 */
    val resumeFromBytes: Long,
)

/**
 * 一次传输的结果。
 *
 * [downloadedBytes] 是**跑完之后本地文件应有的字节数**（续传时 = 已有字节 + 新收字节），
 * [totalBytes] 是总大小（-1 = 未知）。「本地已经是完整文件、服务器回 416」也走这条路
 * （两个值都给已有的字节数）—— 搬迁前那种情况被判成「已完成」，不是失败。
 */
data class DownloadOutcome(
    val downloadedBytes: Long,
    val totalBytes: Long,
)

/**
 * **执行器端口**：[DownloadManager] 负责排队 / 状态 / 进度广播 / 暂停恢复，
 * 「把字节真搬到本地文件」这一件事由本端实现。
 *
 * ## 契约（实现方必须守住，否则状态机与屏上的进度就不可信）
 *
 *  1. **取消**：随协程取消走（实现方在搬运循环里检查取消）。上游 `pauseDownload` / `cancelDownload`
 *     都是 `Job.cancel()`，实现方吞掉取消会让「暂停」变成「继续下载」。
 *  2. **先报一次起点**：在开始搬运字节**之前**，用 `(resumeFromBytes, totalBytes)` 报一次
 *     （搬迁前 `dao.updateProgress(taskId, startBytes, totalSize)` 就在那个位置 —— 屏上
 *     进度条的初始值来自它）。之后**每 ~500 ms** 报一次即可（节流是实现方的责任：
 *     搬一块就报一次会把存储打爆）。
 *  3. **失败抛异常**：非 2xx（416 那种「本地已完整」除外）与 IO 错误都直接抛，由状态机转成
 *     `failed` 并写进 `errorMessage`（`:core` 的 `FriendlyError`）。实现方不要自己吞掉或改写状态。
 *
 * ## 各端实现
 *
 *  - **Android（`:app`）**：`AppDownloadEngine` —— 搬迁前那段 okhttp + `RandomAccessFile` 的搬运
 *    循环（`Range`、206/200/416、8 KB 缓冲、500 ms 节流）**原样搬出**；
 *  - **桌面（`:data`）**：`JvmDownloadEngine` —— 同一套语义的另一份实现（okhttp + `java.nio.file`），
 *    与 Android 那份的差异见那个类的 KDoc 里那张表。
 */
interface DownloadEngine {

    suspend fun download(
        request: DownloadRequest,
        /** (已下载字节, 总字节)，总字节 -1 = 未知。 */
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome
}

/**
 * **落盘端口**：下载目录的根部、建目录、读文件大小、删文件。
 *
 * 为什么和 [DownloadEngine] 分成两个：它们回答的是两个不同的问题 ——「文件放哪儿 / 还在不在」
 * 与「字节怎么搬过来」。前者是纯本机事实（Android 要 `Context` 才能拿到应用专属外部目录，
 * 桌面看 `$HOME`），后者是网络 + IO。合成一个端口会让「只想算一下文件还有多少字节」也得
 * 端着一个 HTTP 客户端。
 */
interface DownloadFileSystem {

    /**
     * 回放视频的保存根目录（屏上「保存位置」那一行、以及每个任务的 `filePath` 都从它拼）。
     * Android = 应用专属外部目录下的 `ClassReplay/`；桌面 = `~/Downloads/ClassReplay`。
     */
    val downloadRoot: String

    /** 建出 [path] 及父目录（搬迁前 `enqueueDownloads` 里那句 `mkdirs()` 的对应物）。 */
    suspend fun ensureDirectory(path: String): Boolean

    /** 文件现有字节数；不存在 = 0（断点续传的起点）。 */
    suspend fun fileSize(path: String): Long

    /** 删掉一个文件（不存在算成功）。 */
    suspend fun deleteFile(path: String): Boolean
}
