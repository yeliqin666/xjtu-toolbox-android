package com.xjtu.toolbox.media

/** 一次传输请求 —— [DownloadEngine.download] 的参数。 */
data class DownloadRequest(
    val url: String,
    /** 本地目标文件（含目录）。目录由 [DownloadFileSystem.ensureDirectory] 在排队时就建好。 */
    val filePath: String,
    /** 本地已有的字节数；> 0 时实现方应当带 `Range` 续传（服务器不认就从头写）。 */
    val resumeFromBytes: Long,
)

/** 一次传输的结果。 */
data class DownloadOutcome(
    val downloadedBytes: Long,
    val totalBytes: Long,
)

/** 执行器端口：[DownloadManager] 管排队 / 状态 / 进度，本端负责把字节写到本地文件。 */
interface DownloadEngine {

    suspend fun download(
        request: DownloadRequest,
        /** (已下载字节, 总字节)，总字节 -1 = 未知。 */
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome
}

/** 落盘端口：下载目录的根部、建目录、读文件大小、删文件。 */
interface DownloadFileSystem {

    /** 回放视频的保存根目录（屏上「保存位置」那一行、以及每个任务的 `filePath` 都从它拼）。 */
    val downloadRoot: String

    /** 建出 [path] 及父目录。 */
    suspend fun ensureDirectory(path: String): Boolean

    /** 文件现有字节数；不存在 = 0（断点续传的起点）。 */
    suspend fun fileSize(path: String): Long

    /** 删掉一个文件（不存在算成功）。 */
    suspend fun deleteFile(path: String): Boolean
}
