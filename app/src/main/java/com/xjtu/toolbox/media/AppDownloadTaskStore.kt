package com.xjtu.toolbox.media

/** 下载任务存储的 Android 侧填法 —— `:core` 的 [DownloadTaskStore] 实现。 */
class AppDownloadTaskStore(private val dao: DownloadTaskDao) : DownloadTaskStore {

    override suspend fun insert(task: DownloadTask): Long = dao.insert(task.toEntity())

    override suspend fun getAll(): List<DownloadTask> = dao.getAll().map { it.toModel() }

    override suspend fun getActiveTasks(): List<DownloadTask> = dao.getActiveTasks().map { it.toModel() }

    override suspend fun getCompletedTasks(): List<DownloadTask> = dao.getCompletedTasks().map { it.toModel() }

    override suspend fun getDownloadingCount(): Int = dao.getDownloadingCount()

    override suspend fun getCompletedCount(): Int = dao.getCompletedCount()

    override suspend fun getActiveCount(): Int = dao.getActiveCount()

    override suspend fun getByStatus(status: String): List<DownloadTask> =
        dao.getByStatus(status).map { it.toModel() }

    override suspend fun deleteById(id: Long) = dao.deleteById(id)

    override suspend fun deleteCompleted() = dao.deleteCompleted()

    override suspend fun updateStatus(id: Long, status: String, errorMessage: String?) =
        dao.updateStatus(id, status, errorMessage)

    override suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long) =
        dao.updateProgress(id, downloadedSize, fileSize)

    override suspend fun updateSpeed(id: Long, speed: Long) = dao.updateSpeed(id, speed)
}

private fun DownloadTask.toEntity(): DownloadTaskEntity = DownloadTaskEntity(
    id = id,
    activityId = activityId,
    courseName = courseName,
    activityTitle = activityTitle,
    cameraType = cameraType,
    videoUrl = videoUrl,
    audioSource = audioSource,
    filePath = filePath,
    fileSize = fileSize,
    downloadedSize = downloadedSize,
    status = status,
    createTime = createTime,
    completeTime = completeTime,
    errorMessage = errorMessage,
    downloadSpeed = downloadSpeed,
)

private fun DownloadTaskEntity.toModel(): DownloadTask = DownloadTask(
    id = id,
    activityId = activityId,
    courseName = courseName,
    activityTitle = activityTitle,
    cameraType = cameraType,
    videoUrl = videoUrl,
    audioSource = audioSource,
    filePath = filePath,
    fileSize = fileSize,
    downloadedSize = downloadedSize,
    status = status,
    createTime = createTime,
    completeTime = completeTime,
    errorMessage = errorMessage,
    downloadSpeed = downloadSpeed,
)
