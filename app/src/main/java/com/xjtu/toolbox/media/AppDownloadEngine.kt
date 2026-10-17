package com.xjtu.toolbox.media

import android.content.Context
import android.os.Environment
import com.xjtu.toolbox.network.HttpClients
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 下载执行器在 Android 侧的填法 —— `:core` 的 [DownloadEngine] 实现。 */
class AppDownloadEngine : DownloadEngine {

    // OkHttp 客户端（不设置超时以支持大文件下载）
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
            onProgress(startBytes, totalSize)

            RandomAccessFile(file, "rw").use { output ->
                if (resumed) output.seek(startBytes) else output.setLength(0)
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
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
                        if (now - lastProgressTime > 500) {
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

/** 下载落盘能力在 Android 侧的填法 —— `:core` 的 [DownloadFileSystem] 实现。 */
class AppDownloadFileSystem(context: Context) : DownloadFileSystem {

    private val appContext = context.applicationContext

    // 应用专属外部目录：Android 10+ 分区存储下没有权限写公共 Downloads（mkdirs 会静默失败）。
    private val downloadDir: File by lazy {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "Download")
        // 目录名沿用 class 课程回放时代的 ClassReplay：改名会让用户已下载的文件「消失」，
        // file_provider_paths.xml 里的路径也要跟着对上。
        File(base, "ClassReplay").also { it.mkdirs() }
    }

    override val downloadRoot: String get() = downloadDir.absolutePath

    override suspend fun ensureDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(path)
        dir.mkdirs() || dir.isDirectory
    }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.exists()) file.length() else 0L
    }

    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        !file.exists() || file.delete()
    }
}
