package com.xjtu.toolbox.media

import com.xjtu.toolbox.network.HttpClients
import com.xjtu.toolbox.platform.dataRootOverride
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 下载执行器在 JVM（桌面）侧的填法 —— `:core` 的 [DownloadEngine] 实现。 */
class JvmDownloadEngine : DownloadEngine {

    private val httpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 无限制
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun download(
        request: DownloadRequest,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val file = File(request.filePath)
        // 目录一般由 DownloadFileSystem 在排队时就建好了；这里再保一手（文件直接写不进去时，
        // 报出来的会是「HTTP/IO 失败」，看不出真正原因是目录不在）
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val existingBytes = request.resumeFromBytes

        val requestBuilder = Request.Builder()
            .url(request.url)
            .header("Accept", "*/*")
            .header("User-Agent", "XJTUToolbox/1.0")
        if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            // 本地已经是完整文件：Range 越界，服务器回 416，这不是失败
            if (response.code == 416 && existingBytes > 0) {
                return@withContext DownloadOutcome(existingBytes, existingBytes)
            }
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            // 服务器不认 Range 会回整个文件（200），这时必须从头写，不能接在旧内容后面
            val resumed = existingBytes > 0 && response.code == 206
            val startBytes = if (resumed) existingBytes else 0L
            val totalSize = response.header("Content-Range")
                ?.substringAfter('/', "")?.toLongOrNull()
                ?: response.body.contentLength().takeIf { it >= 0 }?.let { startBytes + it }
                ?: -1L
            // 先报一次起点：屏上进度条的初始值来自它
            onProgress(startBytes, totalSize)

            val channel = java.nio.channels.FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            channel.use {
                if (resumed) channel.position(startBytes) else channel.truncate(0L)
                // 不额外包 BufferedOutputStream：一块 8 KB 直接落到 channel，进度与「暂停能停住」都更直接
                Channels.newOutputStream(channel).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var downloaded = startBytes
                        var lastProgressTime = 0L

                        while (true) {
                            // 暂停 / 取消时在这里退出（协程取消 → 状态机那边不会落到「完成」）
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime > PROGRESS_INTERVAL_MS) {
                                onProgress(downloaded, totalSize)
                                lastProgressTime = now
                            }
                        }

                        DownloadOutcome(downloaded, totalSize)
                    }
                }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 8192
        const val PROGRESS_INTERVAL_MS = 500L
    }
}

/** 下载落盘能力在 JVM（桌面）侧的填法 —— `:core` 的 [DownloadFileSystem] 实现。 */
class JvmDownloadFileSystem(
    private val root: File = defaultDownloadRoot(),
) : DownloadFileSystem {

    override val downloadRoot: String get() = root.absolutePath

    override suspend fun ensureDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(path)
        runCatching { Files.createDirectories(dir.toPath()) }
        dir.isDirectory
    }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        val file = File(path).toPath()
        if (Files.isRegularFile(file)) runCatching { Files.size(file) }.getOrDefault(0L) else 0L
    }

    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Files.deleteIfExists(File(path).toPath())
            true
        }.getOrDefault(false)
    }
}

/** 桌面端的下载根目录。 */
internal fun defaultDownloadRoot(): File {
    val override = dataRootOverride
    if (override != null) return File(File(override, "Downloads"), "ClassReplay")
    return File(File(System.getProperty("user.home") ?: ".", "Downloads"), "ClassReplay")
}
