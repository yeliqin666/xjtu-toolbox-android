package com.xjtu.toolbox.media

import com.xjtu.toolbox.network.HttpClients
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
        // 目录名沿用 class 课程回放时代的 ClassReplay：改名会让用户已下载的文件「消失」，
        // file_provider_paths.xml 里的路径也要跟着对上。
        File(base, "ClassReplay").also { it.mkdirs() }
    }

    /** 视频保存目录，供界面展示用。别在 UI 里另写一份字符串，改了目录就会不一致。 */
    val videoDirPath: String get() = downloadDir.absolutePath

    // OkHttp 客户端 (不设置超时以支持大文件下载)
    private val httpClient: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 无限制
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // 数据库 DAO (internal 以便页面访问)
    internal val dao by lazy {
        com.xjtu.toolbox.data.AppDatabase.getInstance(context).downloadTaskDao()
    }

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 正在运行的下载任务
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Long, Job>()

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
     * 用中立类型（机位 + 直链），调用方负责拿到可下载的直链。原先 class 课程回放也共用这条队列，
     * class 平台已移除，现在只有思源学堂在用。
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
        if (activeDownloads[taskId]?.isActive == true) return
        // 先登记再启动：任务瞬间结束时，它自己的清理不会赶在登记之前
        val job = scope.launch(start = CoroutineStart.LAZY) {
            semaphore.acquire()
            try {
                executeDownload(taskId)
            } finally {
                semaphore.release()
                activeDownloads.remove(taskId, coroutineContext.job)
            }
        }
        activeDownloads[taskId] = job
        job.start()
    }

    /**
     * 执行实际下载逻辑（断点续传）
     */
    private suspend fun executeDownload(taskId: Long) {
        val task = dao.getAll().find { it.id == taskId } ?: return

        try {
            dao.updateStatus(taskId, "downloading")
            emitProgress(taskId, 0, -1, "downloading")

            val file = File(task.filePath)
            val existingBytes = if (file.exists()) file.length() else 0L

            val requestBuilder = Request.Builder()
                .url(task.videoUrl)
                .header("Accept", "*/*")
                .header("User-Agent", "XJTUToolbox/1.0")
            if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                // 本地已经是完整文件：Range 越界，服务器回 416，这不是失败
                if (response.code == 416 && existingBytes > 0) {
                    dao.updateStatus(taskId, "completed")
                    dao.updateProgress(taskId, existingBytes, existingBytes)
                    emitProgress(taskId, existingBytes, existingBytes, "completed")
                    Log.d(TAG, "Already complete: taskId=$taskId, size=$existingBytes")
                    return
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
                dao.updateProgress(taskId, startBytes, totalSize)

                RandomAccessFile(file, "rw").use { output ->
                    if (resumed) output.seek(startBytes) else output.setLength(0)
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var downloaded = startBytes
                        var lastProgressTime = 0L
                        var lastSpeedCalcTime = System.currentTimeMillis()
                        var lastDownloadedBytes = downloaded

                        while (true) {
                            // 暂停 / 取消时在这里退出，不能落到下面的「完成」
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime > 500) {
                                dao.updateProgress(taskId, downloaded, totalSize)
                                emitProgress(taskId, downloaded, totalSize, "downloading")
                                lastProgressTime = now
                            }
                            if (now - lastSpeedCalcTime > 1000) {
                                dao.updateSpeed(taskId, (downloaded - lastDownloadedBytes) * 1000 / (now - lastSpeedCalcTime))
                                lastSpeedCalcTime = now
                                lastDownloadedBytes = downloaded
                            }
                        }

                        dao.updateStatus(taskId, "completed")
                        dao.updateProgress(taskId, downloaded, totalSize)
                        emitProgress(taskId, downloaded, totalSize, "completed")
                        Log.d(TAG, "Download completed: taskId=$taskId, file=${file.name}, size=$downloaded")
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled: taskId=$taskId")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: taskId=$taskId", e)
            dao.updateStatus(taskId, "failed", e.message)
            emitProgress(taskId, task.downloadedSize, task.fileSize, "failed")
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
