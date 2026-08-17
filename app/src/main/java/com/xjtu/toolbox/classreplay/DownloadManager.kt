package com.xjtu.toolbox.classreplay

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

private const val TAG = "DownloadManager"

/**
 * 下载进度数据类
 */
data class DownloadProgress(
    val taskId: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Float, // 0.0 ~ 1.0
    val status: String,  // downloading/paused/completed/failed
    val speedBytesPerSec: Long = 0  // 下载速度(字节/秒)
)

/**
 * 下载管理器 - 单例
 * 负责所有课程回放下载任务的调度、断点续传、进度跟踪
 */
class DownloadManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // 下载目录：应用专属外部目录，不需要存储权限。
    //
    // 不能用 Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)：
    // 分区存储（Android 10+）下 App 无权往公共 Downloads 写，而清单里只有 INTERNET
    // 权限，mkdirs() 会静默失败、写文件抛异常。
    // 需要落到公共目录的走 MediaStore（见 LmsDownloadStore）。
    private val downloadDir: File by lazy {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        File(base, "ClassReplay").also { it.mkdirs() }
    }

    /** 视频保存目录，供界面展示用。别在 UI 里另写一份字符串，改了目录就会不一致。 */
    val videoDirPath: String get() = downloadDir.absolutePath

    // OkHttp 客户端 (不设置超时以支持大文件下载)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 无限制
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // 数据库 DAO (internal 以便页面访问)
    internal val dao by lazy {
        com.xjtu.toolbox.util.AppDatabase.getInstance(context).downloadTaskDao()
    }

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 正在运行的下载任务
    private val activeDownloads = mutableMapOf<Long, Job>()

    // 并发限制
    private val semaphore = Semaphore(3) // 最多同时下载3个

    // 进度广播
    private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow.asSharedFlow()

    init {
        // 启动时：将之前正在下载的任务标记为暂停
        scope.launch {
            val downloadingTasks = dao.getByStatus("downloading")
            downloadingTasks.forEach { task ->
                dao.updateStatus(task.id, "paused")
                Log.d(TAG, "Auto-paused task ${task.id} on startup")
            }
        }
    }

    /**
     * 一条待下载的视频。
     *
     * 用中立类型而非 [ReplayVideo]，是为了让 class 与思源学堂两个模块共用下载队列：
     * 前者的地址要先经 resolveVideoUrl 解析，后者的 download_url 本身就是直链，
     * 差异留在各自的调用方，下载管理这边只认「机位 + 直链」。
     */
    data class DownloadItem(
        /** "instructor" / "encoder"，用于命名与分类 */
        val cameraType: String,
        /** 可直接 GET 的视频地址；HLS(m3u8) 不受支持，调用方需自行排除 */
        val url: String,
    )

    /**
     * 批量创建下载任务
     */
    suspend fun enqueueDownloads(
        courseName: String,
        activityTitle: String,
        activityId: Int,
        videos: List<DownloadItem>,
    ): List<Long> {
        val taskIds = mutableListOf<Long>()

        for (item in videos) {
            val videoInfo = item
            val videoUrl = item.url
            // 生成文件路径
            val safeCourseName = sanitizeFileName(courseName)
            val safeActivityTitle = sanitizeFileName(activityTitle)
            val cameraLabel = when (videoInfo.cameraType) {
                "instructor" -> "教师直播"
                "encoder" -> "电脑屏幕"
                else -> videoInfo.cameraType
            }
            val fileName = "${safeActivityTitle}_${cameraLabel}.mp4"
            val courseDir = File(downloadDir, safeCourseName).also { it.mkdirs() }
            val filePath = File(courseDir, fileName).absolutePath

            // 创建数据库记录
            val task = DownloadTaskEntity(
                activityId = activityId,
                courseName = courseName,
                activityTitle = activityTitle,
                cameraType = videoInfo.cameraType,
                videoUrl = videoUrl,
                // 每个机位本身就是独立 mp4、自带音轨，分开下载时「选音源」没有落点，
                // 该能力从未实现（此字段过去只写库、从不参与下载）。UI 已移除，
                // 这里保留列名并填占位，避免动 Room schema 触发迁移。
                audioSource = videoInfo.cameraType,
                filePath = filePath,
                fileSize = -1,
                downloadedSize = 0,
                status = "pending",
                createTime = System.currentTimeMillis(),
                completeTime = null,
                errorMessage = null
            )
            val taskId = dao.insert(task)
            taskIds.add(taskId)

            Log.d(TAG, "Enqueued download task $taskId: $fileName")
        }

        // 启动下载
        taskIds.forEach { taskId ->
            launchDownload(taskId)
        }

        return taskIds
    }

    /**
     * 启动单个下载任务
     */
    private fun launchDownload(taskId: Long) {
        val job = scope.launch {
            semaphore.acquire()
            try {
                executeDownload(taskId)
            } finally {
                semaphore.release()
                activeDownloads.remove(taskId)
            }
        }
        activeDownloads[taskId] = job
    }

    /**
     * 执行实际下载逻辑
     */
    private suspend fun executeDownload(taskId: Long) {
        val task = dao.getAll().find { it.id == taskId } ?: return

        try {
            // 更新状态为下载中
            dao.updateStatus(taskId, "downloading")
            emitProgress(taskId, 0, -1, "downloading")

            val file = File(task.filePath)
            val existingBytes = if (file.exists()) file.length() else 0L

            // 构建请求 (支持断点续传)
            val requestBuilder = Request.Builder()
                .url(task.videoUrl)
                .header("Accept", "*/*")
                .header("User-Agent", "XJTUToolbox/1.0")



            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val request = requestBuilder.build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            // 解析文件大小
            val contentRange = response.header("Content-Range")
            val contentLength = response.body?.contentLength() ?: -1

            val totalSize = if (contentRange != null) {
                // Content-Range: bytes 100-499/500
                val parts = contentRange.split("/")
                if (parts.size > 1) parts[1].toLongOrNull() ?: -1 else -1
            } else {
                existingBytes + contentLength
            }

            // 更新文件大小信息
            dao.updateProgress(taskId, existingBytes, totalSize)

            // 写入文件
            val outputStream = RandomAccessFile(file, "rw")
            if (existingBytes > 0) {
                outputStream.seek(existingBytes)
            }

            response.body?.byteStream()?.use { inputStream ->
                val buffer = ByteArray(8192)
                var downloaded = existingBytes
                var lastProgressTime = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var lastDownloadedBytes = downloaded

                while (currentCoroutineContext().isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    // 更新数据库 (每500ms更新一次进度)
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 500) {
                        dao.updateProgress(taskId, downloaded, totalSize)
                        val progress = if (totalSize > 0) downloaded.toFloat() / totalSize else 0f
                        emitProgress(taskId, downloaded, totalSize, "downloading")
                        lastProgressTime = now
                    }
                    
                    // 计算下载速度 (每秒更新一次)
                    if (now - lastSpeedCalcTime > 1000) {
                        val speedBytes = downloaded - lastDownloadedBytes
                        val speedPerSecond = speedBytes * 1000 / (now - lastSpeedCalcTime)
                        dao.updateSpeed(taskId, speedPerSecond)
                        lastSpeedCalcTime = now
                        lastDownloadedBytes = downloaded
                    }
                }

                // 下载完成
                dao.updateStatus(taskId, "completed")
                dao.updateProgress(taskId, downloaded, totalSize)
                emitProgress(taskId, downloaded, totalSize, "completed")

                Log.d(TAG, "Download completed: taskId=$taskId, file=${file.name}, size=$downloaded")
            }

            outputStream.close()

        } catch (e: Exception) {
            if (e is CancellationException) {
                // 被取消,保持当前状态
                Log.d(TAG, "Download cancelled: taskId=$taskId")
            } else {
                Log.e(TAG, "Download failed: taskId=$taskId", e)
                dao.updateStatus(taskId, "failed", e.message)
                emitProgress(taskId, task.downloadedSize, task.fileSize, "failed")
            }
        }
    }

    /**
     * 暂停下载
     */
    suspend fun pauseDownload(taskId: Long) {
        dao.updateStatus(taskId, "paused")
        activeDownloads[taskId]?.cancel()
        Log.d(TAG, "Paused download: taskId=$taskId")
    }

    /**
     * 恢复下载
     */
    fun resumeDownload(taskId: Long) {
        launchDownload(taskId)
        Log.d(TAG, "Resumed download: taskId=$taskId")
    }

    /**
     * 取消下载 (删除文件)
     */
    suspend fun cancelDownload(taskId: Long) {
        activeDownloads[taskId]?.cancel()
        activeDownloads.remove(taskId)

        val task = dao.getAll().find { it.id == taskId }
        if (task != null) {
            File(task.filePath).delete()
            dao.deleteById(taskId)
            Log.d(TAG, "Cancelled and deleted download: taskId=$taskId")
        }
    }

    /**
     * 暂停所有下载
     */
    suspend fun pauseAll() {
        val activeTasks = dao.getActiveTasks()
        activeTasks.forEach { task ->
            if (task.status == "downloading") {
                pauseDownload(task.id)
            }
        }
        Log.d(TAG, "Paused all downloads (${activeTasks.size} tasks)")
    }

    /**
     * 恢复所有下载
     */
    fun resumeAll() {
        scope.launch {
            val pausedTasks = dao.getActiveTasks().filter { it.status == "paused" || it.status == "pending" }
            pausedTasks.forEach { task ->
                resumeDownload(task.id)
            }
            Log.d(TAG, "Resumed all downloads (${pausedTasks.size} tasks)")
        }
    }

    /**
     * 取消所有下载
     */
    suspend fun cancelAll() {
        val activeTasks = dao.getActiveTasks()
        activeTasks.forEach { task ->
            cancelDownload(task.id)
        }
        Log.d(TAG, "Cancelled all downloads")
    }

    /**
     * 删除已完成的任务记录
     */
    suspend fun deleteCompleted() {
        val completedTasks = dao.getCompletedTasks()
        completedTasks.forEach { task ->
            dao.deleteById(task.id)
        }
        Log.d(TAG, "Deleted ${completedTasks.size} completed tasks")
    }

    /**
     * 删除单个任务记录
     * @param deleteFile 是否同时删除视频文件，默认 true
     */
    suspend fun deleteTask(taskId: Long, deleteFile: Boolean = true) {
        val task = dao.getAll().find { it.id == taskId }
        if (task != null && task.status != "downloading") {
            // 暂停/等待中的任务：取消活跃下载并始终删除文件
            if (task.status == "paused" || task.status == "pending") {
                activeDownloads[taskId]?.cancel()
                activeDownloads.remove(taskId)
                File(task.filePath).delete()
            } else {
                // 已完成/失败/取消的任务：按 deleteFile 参数决定是否删除文件
                if (deleteFile) {
                    File(task.filePath).delete()
                }
            }
            dao.deleteById(taskId)
            Log.d(TAG, "Deleted task record: taskId=$taskId, deleteFile=$deleteFile")
        }
    }

    /**
     * 发出进度更新
     */
    private suspend fun emitProgress(taskId: Long, downloaded: Long, total: Long, status: String) {
        val progress = if (total > 0) downloaded.toFloat() / total else 0f
        _progressFlow.emit(
            DownloadProgress(
                taskId = taskId,
                downloadedBytes = downloaded,
                totalBytes = total,
                progress = progress,
                status = status
            )
        )
    }

    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 获取下载统计信息
     */
    suspend fun getDownloadStats(): DownloadStats {
        val downloading = dao.getDownloadingCount()
        val completed = dao.getCompletedCount()
        val active = dao.getActiveCount()
        return DownloadStats(downloading, completed, active)
    }

    data class DownloadStats(
        val downloadingCount: Int,
        val completedCount: Int,
        val activeCount: Int
    )
}
