package com.xjtu.toolbox.media

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 下载任务的共享模型（字段与 Room 表 `download_tasks` 逐列同名同序）。 */
data class DownloadTask(
    val id: Long = 0,
    val activityId: Int,
    val courseName: String,
    val activityTitle: String,
    /** "instructor" / "encoder" */
    val cameraType: String,
    val videoUrl: String,
    val audioSource: String,
    /** 本地文件路径 */
    val filePath: String,
    /** 总大小（字节），-1 = 未知 */
    val fileSize: Long,
    /** 已下载大小（字节） */
    val downloadedSize: Long,
    /** 见 [DownloadStatus]。 */
    val status: String,
    /** 创建时间戳（毫秒） */
    val createTime: Long,
    /** 完成时间戳（毫秒），null = 未完成 */
    val completeTime: Long? = null,
    /** 失败原因 */
    val errorMessage: String? = null,
    /** 实时下载速度（字节/秒） */
    val downloadSpeed: Long = 0,
) {
    /** 下载进度（0.0 ~ 1.0）。 */
    val progress: Float
        get() = if (fileSize > 0) downloadedSize.toFloat() / fileSize else 0f

    /** 用户友好的状态标签。 */
    val statusLabel: String
        get() = when (status) {
            DownloadStatus.PENDING -> "等待中"
            DownloadStatus.DOWNLOADING -> "下载中"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.COMPLETED -> "已完成"
            DownloadStatus.FAILED -> "失败"
            DownloadStatus.CANCELLED -> "已取消"
            else -> status
        }

    /** 是否活跃（正在下载、已暂停或等待中）。 */
    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.PAUSED ||
            status == DownloadStatus.PENDING
}

/** 任务状态的字符串取值（直接落库，不能改）。 */
object DownloadStatus {
    const val PENDING = "pending"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/** 下载进度数据类（[DownloadManager.progressFlow] 的事件）。 */
data class DownloadProgress(
    val taskId: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** 0.0 ~ 1.0 */
    val progress: Float,
    /** 见 [DownloadStatus]。 */
    val status: String,
)

/** 一条待下载的视频。 */
data class DownloadItem(
    /** "instructor" / "encoder"，用于命名与分类 */
    val cameraType: String,
    /** 可直接 GET 的视频地址；HLS(m3u8) 不受支持，调用方需自行排除 */
    val url: String,
)

/** 下载统计信息。 */
data class DownloadStats(
    val downloadingCount: Int,
    val completedCount: Int,
    val activeCount: Int,
)

/** 下载目录信息弹窗里要显示的两样东西 —— 各端事实不同，所以由宿主填（见 [DownloadManagerScreen]）。 */
data class DownloadDirInfo(
    val filesLocation: String,
    val notes: List<String>,
)

/** 一条「文件类下载」（成绩单 / 思源课件 / 资料站文件） */
data class DownloadedFile(
    val name: String,
    val mimeType: String,
    val uri: String,
    val savedAt: Long,
    val category: String = CATEGORY_LMS,
) {
    /** 屏上分组用的一行小字。 */
    val categoryLabel: String
        get() = when (category) {
            CATEGORY_TRANSCRIPT -> "成绩单"
            CATEGORY_LMS -> "思源文件"
            CATEGORY_ZYXF -> "仲英学辅"
            CATEGORY_OTHER -> "其他"
            else -> "已下载"
        }

    companion object {
        const val CATEGORY_LMS = "lms"
        const val CATEGORY_TRANSCRIPT = "transcript"
        const val CATEGORY_OTHER = "other"

        /** 仲英学辅资料站下载的文件。与思源课件分开，它们来源和用途都不一样。 */
        const val CATEGORY_ZYXF = "zyxf"
    }
}

/** 时间戳 → `MM/dd HH:mm`（本地时区）。 */
internal fun formatDownloadTime(timestamp: Long): String {
    val at = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
    fun pad(value: Int) = value.toString().padStart(2, '0')
    return "${pad(at.monthNumber)}/${pad(at.dayOfMonth)} ${pad(at.hour)}:${pad(at.minute)}"
}

/** 文件大小的人类可读写法。 */
internal fun formatDownloadSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024.0 * 1024 * 1024)} GB"
}
