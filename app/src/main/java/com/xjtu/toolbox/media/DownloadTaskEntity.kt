package com.xjtu.toolbox.media

import androidx.room.*

/**
 * 下载任务实体 (Room 数据库)
 * 用于跟踪课程回放的下载进度
 */
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val activityId: Int,
    val courseName: String,
    val activityTitle: String,
    val cameraType: String,           // "instructor" / "encoder"
    val videoUrl: String,
    val audioSource: String,          // "instructor" / "encoder" / "both" / "mute"
    val filePath: String,             // 本地文件路径
    val fileSize: Long,               // 总大小(字节), -1 表示未知
    val downloadedSize: Long,         // 已下载大小(字节)
    val status: String,               // pending/downloading/paused/completed/failed/cancelled
    val createTime: Long,             // 创建时间戳(ms)
    val completeTime: Long?,          // 完成时间戳(ms), null表示未完成
    val errorMessage: String?,        // 失败原因
    val downloadSpeed: Long = 0       // 实时下载速度(字节/秒)
) {
    /** 下载进度 (0.0 ~ 1.0) */
    val progress: Float
        get() = if (fileSize > 0) downloadedSize.toFloat() / fileSize else 0f

    /** 用户友好的状态标签 */
    val statusLabel: String
        get() = when (status) {
            "pending" -> "等待中"
            "downloading" -> "下载中"
            "paused" -> "已暂停"
            "completed" -> "已完成"
            "failed" -> "失败"
            "cancelled" -> "已取消"
            else -> status
        }

    /** 是否可恢复 (暂停或失败的任务可以恢复) */
    val isResumable: Boolean
        get() = status == "paused" || status == "failed"

    /** 是否活跃 (正在下载或暂停) */
    val isActive: Boolean
        get() = status == "downloading" || status == "paused" || status == "pending"
}

/** DAO 接口 */
@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks ORDER BY createTime DESC")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE activityId = :activityId ORDER BY createTime DESC")
    suspend fun getByActivity(activityId: Int): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN ('downloading', 'paused', 'pending') ORDER BY createTime DESC")
    suspend fun getActiveTasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'completed' ORDER BY createTime DESC")
    suspend fun getCompletedTasks(): List<DownloadTaskEntity>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = 'downloading'")
    suspend fun getDownloadingCount(): Int

    @Query("SELECT * FROM download_tasks WHERE status = :status")
    suspend fun getByStatus(status: String): List<DownloadTaskEntity>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = 'completed'")
    suspend fun getCompletedCount(): Int

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('downloading', 'paused', 'pending')")
    suspend fun getActiveCount(): Int

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_tasks WHERE status = 'completed'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM download_tasks WHERE activityId = :activityId")
    suspend fun deleteByActivity(activityId: Int)

    @Query("UPDATE download_tasks SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null)

    @Query("UPDATE download_tasks SET downloadedSize = :downloadedSize, fileSize = :fileSize WHERE id = :id")
    suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long)

    @Query("UPDATE download_tasks SET downloadSpeed = :speed WHERE id = :id")
    suspend fun updateSpeed(id: Long, speed: Long)
}
