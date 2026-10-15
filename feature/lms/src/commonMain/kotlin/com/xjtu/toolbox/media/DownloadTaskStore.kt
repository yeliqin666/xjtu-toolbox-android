package com.xjtu.toolbox.media

/** 下载任务的持久化端口 —— [DownloadManager]（状态机 / 队列）只认这一组方法，「存在哪」由各端自己填。 */
interface DownloadTaskStore {

    /** 插入一条任务，返回自增主键。 */
    suspend fun insert(task: DownloadTask): Long

    /** 全表（创建时间倒序）。 */
    suspend fun getAll(): List<DownloadTask>

    /** 活跃任务：`downloading` / `paused` / `pending`（创建时间倒序）。 */
    suspend fun getActiveTasks(): List<DownloadTask>

    /** 已完成任务（创建时间倒序）。 */
    suspend fun getCompletedTasks(): List<DownloadTask>

    suspend fun getDownloadingCount(): Int

    suspend fun getCompletedCount(): Int

    suspend fun getActiveCount(): Int

    /** 某个状态的任务（创建时间倒序）。启动时把残留的 `downloading` 置成 `paused` 用它。 */
    suspend fun getByStatus(status: String): List<DownloadTask>

    suspend fun deleteById(id: Long)

    /** 删掉所有已完成任务的记录（**不动文件**）。 */
    suspend fun deleteCompleted()

    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null)

    suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long)

    suspend fun updateSpeed(id: Long, speed: Long)
}
