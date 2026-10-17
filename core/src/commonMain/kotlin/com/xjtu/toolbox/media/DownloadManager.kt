package com.xjtu.toolbox.media

import com.xjtu.toolbox.error.FriendlyError
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** 并发上限：最多同时下载 3 个。 */
private const val MAX_CONCURRENT_DOWNLOADS = 3

/** 下载管理器：任务队列 / 状态机 / 进度广播。 */
class DownloadManager(
    private val store: DownloadTaskStore,
    private val engine: DownloadEngine,
    private val fileSystem: DownloadFileSystem,
    /** 跑下载任务的作用域（Android 传 `Dispatchers.IO + SupervisorJob()`）。 */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    /** 回放视频的保存目录，供界面展示用。别在 UI 里另写一份字符串，改了目录就会不一致。 */
    val videoDirPath: String get() = fileSystem.downloadRoot

    /** 正在运行的下载任务。 */
    private val activeDownloads = mutableMapOf<Long, Job>()
    private val registryLock = Mutex()

    /** 并发限制。 */
    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /** 进度广播。 */
    private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow.asSharedFlow()

    init {
        // 启动时：将之前正在下载的任务标记为暂停
        scope.launch {
            store.getByStatus(DownloadStatus.DOWNLOADING).forEach { task ->
                store.updateStatus(task.id, DownloadStatus.PAUSED)
            }
        }
    }

    /** 全表（屏上那个列表读的就是它，创建时间倒序）。 */
    suspend fun getAllTasks(): List<DownloadTask> = store.getAll()

    /** 批量创建下载任务。 */
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
            // `File(downloadDir, safeCourseName)` 的等价物：目录名为空时就是根本身（不留一条多余的 `/`）
            val courseDir = joinPath(fileSystem.downloadRoot, safeCourseName)
            fileSystem.ensureDirectory(courseDir)
            val filePath = joinPath(courseDir, fileName)

            val task = DownloadTask(
                activityId = activityId,
                courseName = courseName,
                activityTitle = activityTitle,
                cameraType = videoInfo.cameraType,
                videoUrl = videoUrl,
                audioSource = videoInfo.cameraType,
                filePath = filePath,
                fileSize = -1,
                downloadedSize = 0,
                status = DownloadStatus.PENDING,
                createTime = nowMillis(),
                completeTime = null,
                errorMessage = null,
            )
            val taskId = store.insert(task)
            taskIds.add(taskId)
        }

        // 启动下载
        taskIds.forEach { taskId ->
            launchDownload(taskId)
        }

        return taskIds
    }

    /** 启动单个下载任务。 */
    private suspend fun launchDownload(taskId: Long) {
        if (activeJob(taskId)?.isActive == true) return
        // 先登记再启动：任务瞬间结束时，它自己的清理不会赶在登记之前
        val job = scope.launch(start = CoroutineStart.LAZY) {
            semaphore.acquire()
            try {
                executeDownload(taskId)
            } finally {
                semaphore.release()
                registryLock.withLock {
                    if (activeDownloads[taskId] === currentCoroutineContext().job) {
                        activeDownloads.remove(taskId)
                    }
                }
            }
        }
        registryLock.withLock { activeDownloads[taskId] = job }
        job.start()
    }

    /** 执行实际下载逻辑（断点续传）—— 搬运本身交给 [engine]，这里只管状态与进度。 */
    private suspend fun executeDownload(taskId: Long) {
        val task = store.getAll().find { it.id == taskId } ?: return

        try {
            store.updateStatus(taskId, DownloadStatus.DOWNLOADING)
            emitProgress(taskId, 0, -1, DownloadStatus.DOWNLOADING)

            val existingBytes = fileSystem.fileSize(task.filePath)

            var lastSpeedCalcTime = nowMillis()
            var lastCountedBytes = existingBytes

            val outcome = engine.download(
                DownloadRequest(url = task.videoUrl, filePath = task.filePath, resumeFromBytes = existingBytes),
            ) { downloaded, total ->
                store.updateProgress(taskId, downloaded, total)
                emitProgress(taskId, downloaded, total, DownloadStatus.DOWNLOADING)
                val now = nowMillis()
                if (now - lastSpeedCalcTime > 1000) {
                    store.updateSpeed(taskId, (downloaded - lastCountedBytes) * 1000 / (now - lastSpeedCalcTime))
                    lastSpeedCalcTime = now
                    lastCountedBytes = downloaded
                }
            }

            store.updateStatus(taskId, DownloadStatus.COMPLETED)
            store.updateProgress(taskId, outcome.downloadedBytes, outcome.totalBytes)
            emitProgress(taskId, outcome.downloadedBytes, outcome.totalBytes, DownloadStatus.COMPLETED)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 暂停 / 取消：状态由发起方写（pauseDownload 写 paused，cancelDownload 删记录），
            // 这里只把取消原样抛出去，绝不能落到下面的「失败」。
            throw e
        } catch (e: Exception) {
            store.updateStatus(taskId, DownloadStatus.FAILED, FriendlyError.of(e, "下载"))
            emitProgress(taskId, task.downloadedSize, task.fileSize, DownloadStatus.FAILED)
        }
    }

    /** 暂停下载。 */
    suspend fun pauseDownload(taskId: Long) {
        store.updateStatus(taskId, DownloadStatus.PAUSED)
        cancelActive(taskId)
    }

    /** 恢复下载。 */
    fun resumeDownload(taskId: Long) {
        scope.launch { launchDownload(taskId) }
    }

    /** 取消下载（删除文件）。 */
    suspend fun cancelDownload(taskId: Long) {
        cancelActive(taskId)

        val task = store.getAll().find { it.id == taskId }
        if (task != null) {
            fileSystem.deleteFile(task.filePath)
            store.deleteById(taskId)
        }
    }

    /** 暂停所有下载。 */
    suspend fun pauseAll() {
        val activeTasks = store.getActiveTasks()
        activeTasks.forEach { task ->
            if (task.status == DownloadStatus.DOWNLOADING) {
                pauseDownload(task.id)
            }
        }
    }

    /** 恢复所有下载。 */
    fun resumeAll() {
        scope.launch {
            val pausedTasks = store.getActiveTasks()
                .filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.PENDING }
            pausedTasks.forEach { task ->
                launchDownload(task.id)
            }
        }
    }

    /** 取消所有下载。 */
    suspend fun cancelAll() {
        store.getActiveTasks().forEach { task ->
            cancelDownload(task.id)
        }
    }

    /** 删除已完成的任务记录（不动文件）。 */
    suspend fun deleteCompleted() {
        store.getCompletedTasks().forEach { task ->
            store.deleteById(task.id)
        }
    }

    /** 删除单个任务记录。 */
    suspend fun deleteTask(taskId: Long, deleteFile: Boolean = true) {
        val task = store.getAll().find { it.id == taskId }
        if (task != null && task.status != DownloadStatus.DOWNLOADING) {
            // 暂停/等待中的任务：取消活跃下载并始终删除文件
            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.PENDING) {
                cancelActive(taskId)
                fileSystem.deleteFile(task.filePath)
            } else {
                // 已完成/失败/取消的任务：按 deleteFile 参数决定是否删除文件
                if (deleteFile) {
                    fileSystem.deleteFile(task.filePath)
                }
            }
            store.deleteById(taskId)
        }
    }

    /** 获取下载统计信息。 */
    suspend fun getDownloadStats(): DownloadStats {
        val downloading = store.getDownloadingCount()
        val completed = store.getCompletedCount()
        val active = store.getActiveCount()
        return DownloadStats(downloading, completed, active)
    }

    /** 取消一个任务的活跃下载（调用方决定随后写什么状态）。`cancel()` 在锁外调，见类 KDoc。 */
    private suspend fun cancelActive(taskId: Long) {
        val job = registryLock.withLock { activeDownloads[taskId] }
        job?.cancel()
    }

    private suspend fun activeJob(taskId: Long): Job? = registryLock.withLock { activeDownloads[taskId] }

    /** 发出进度更新。 */
    private suspend fun emitProgress(taskId: Long, downloaded: Long, total: Long, status: String) {
        val progress = if (total > 0) downloaded.toFloat() / total else 0f
        _progressFlow.emit(
            DownloadProgress(
                taskId = taskId,
                downloadedBytes = downloaded,
                totalBytes = total,
                progress = progress,
                status = status,
            ),
        )
    }

    /** 清理文件名中的非法字符。 */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** `File(base, name).absolutePath` 的等价物：目录段为空时不留一条多余的 `/`。 */
private fun joinPath(base: String, name: String): String =
    if (name.isEmpty()) base else "$base/$name"

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
