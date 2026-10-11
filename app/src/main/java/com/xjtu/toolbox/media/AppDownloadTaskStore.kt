package com.xjtu.toolbox.media

/**
 * 下载任务存储的 **Android 侧填法** —— `:core` 的 [DownloadTaskStore] 实现。
 *
 * ## 它做的事就是把端口转发回原来那张 Room 表
 *
 * 搬迁前 `:core` 这套状态机是直接调 [DownloadTaskDao] 的；搬走之后端口上的每个方法对应 DAO 上
 * **同名的、本来就有的**那一句，所以这里没有一行新逻辑：
 *
 * | 端口 | Room |
 * |---|---|
 * | [insert] | `dao.insert(entity)` |
 * | [getAll] / [getActiveTasks] / [getCompletedTasks] / [getByStatus] | 同名的 `@Query`（`ORDER BY createTime DESC` 也在 SQL 里，没搬进内存） |
 * | [getDownloadingCount] / [getCompletedCount] / [getActiveCount] | 同名的 `COUNT(*)` |
 * | [updateStatus] / [updateProgress] / [updateSpeed] | 同名的窄 `UPDATE`（**不是**整行覆盖，写的列与搬迁前逐列一致） |
 * | [deleteById] / [deleteCompleted] | 同名的 `DELETE` |
 *
 * 也就是说：**表名、列、SQL、`@PrimaryKey(autoGenerate = true)` 的自增 id、升级路径一个字都没动**。
 * 老的 `download_tasks` 表照旧被读写，用户已经下载到一半的队列在升级后接着用（那正是搬动时
 * 最该守住的东西）。两个类型之间的映射只有下面两个私有扩展函数。
 *
 * ⚠️ 与桌面那份（`DesktopDownloadTaskStore`，一份 JSON）**不等价的地方**：这里排序/计数由 SQLite
 * 做（有索引、有事务），桌面那份在内存里做同一件事（单文件、单进程）。共享层只承诺语义，
 * 见 [DownloadTaskStore] 的 KDoc。
 */
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
