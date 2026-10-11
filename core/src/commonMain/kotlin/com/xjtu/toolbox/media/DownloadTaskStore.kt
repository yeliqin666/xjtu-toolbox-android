package com.xjtu.toolbox.media

/**
 * 下载任务的**持久化端口** —— [DownloadManager]（状态机 / 队列）只认这一组方法，
 * 「存在哪」由各端自己填。
 *
 * ## 为什么方法形状与 Android 的 DAO 一模一样
 *
 * 搬迁前这套状态机是直接调 Room 的 `DownloadTaskDao` 的。端口的每个方法都对应 DAO 上一条**已有的**
 * 查询，于是 Android 那份实现（`:app` 的 `AppDownloadTaskStore`）只是把同一句话转发给同一个 DAO ——
 * 表结构、列名、SQL、升级路径**一个字都没动**，「行为不变」是逐条可核的，不是「应该差不多」。
 *
 * ## 各端实现
 *
 *  - **Android（`:app`）**：`AppDownloadTaskStore` —— Room 表 `download_tasks`（原样）；
 *  - **桌面（`:desktop`）**：`DesktopDownloadTaskStore` —— 一份 JSON 落盘（`:data` 的
 *    密钥键值缝，权限 0600），**没有** SQL 索引/事务/多进程可见性（单实例应用的正常取舍，
 *    见那个类的 KDoc 里那张差异表）。
 *
 * ## 与 Android 的差异（如实）
 *
 * 端口只承诺上面的语义，「排序 / 计数在数据库里做还是在这里做」是实现方的自由：
 * Android 那条走 SQL（`ORDER BY createTime DESC`、`COUNT(*)`），桌面那条在内存里做同一件事。
 * 两边都遵守同一条口径：`getAll` / `getActiveTasks` / `getCompletedTasks` / `getByStatus`
 * 一律**创建时间倒序**，`getActive*` = `status IN (downloading, paused, pending)`。
 */
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
