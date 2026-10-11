package com.xjtu.toolbox.media

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 下载任务的**共享模型** —— 搬迁前它是 `:app` 的 Room 实体 `DownloadTaskEntity`（表 `download_tasks`），
 * 字段与列**逐列同名同序**，只是去掉了 `androidx.room` 的注解。
 *
 * 为什么把实体升成共享模型、而不是给 Room 的实体加一个「共享版」：
 * 「一条下载任务有哪些字段、它的状态标签是什么」本来就是业务口径，与「存在哪」无关。
 * 后端存储由 [DownloadTaskStore] 端口各自填（Android = Room 表，桌面 = 一份 JSON 落盘），
 * 屏与状态机只认这一个类型。
 */
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

/**
 * 任务状态的字符串口径。**取值与搬迁前逐字相同**（它们直接写进 Room 列 / 桌面那份 JSON，
 * 改了就等于换了一套存储口径）：`pending` / `downloading` / `paused` / `completed` /
 * `failed` / `cancelled`。
 */
object DownloadStatus {
    const val PENDING = "pending"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/**
 * 下载进度数据类（[DownloadManager.progressFlow] 的事件）。
 */
data class DownloadProgress(
    val taskId: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** 0.0 ~ 1.0 */
    val progress: Float,
    /** 见 [DownloadStatus]。 */
    val status: String,
)

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

/** 下载统计信息。 */
data class DownloadStats(
    val downloadingCount: Int,
    val completedCount: Int,
    val activeCount: Int,
)

/**
 * 下载目录信息弹窗里要显示的两样东西 —— **各端事实不同**，所以由宿主填（见 [DownloadManagerScreen]）。
 *
 * [filesLocation] 是「文件类下载」的展示位置（Android = 公共下载目录，桌面 = `~/Downloads/`）；
 * [notes] 是下面那几句说明 —— 搬迁前它们写在屏上，其中「卸载 App 会一并清除」这种话说的是
 * **Android 的落盘位置**，桌面照抄就是假话，所以文案跟着实现走、不跟着屏走。
 */
data class DownloadDirInfo(
    val filesLocation: String,
    val notes: List<String>,
)

/**
 * 一条「文件类下载」（成绩单 / 思源课件 / 资料站文件）—— 与 [DownloadTask] 那种**排队下载的视频**
 * 不是一回事：它们下载完就落到公共下载目录、由系统文件管理器管，屏上只列出来。
 *
 * 搬迁前这个类型叫 `LmsDownloadRecord`（`:app` 的 `LmsDownloadStore` 里那份），
 * 因为它还有 MediaStore 的实现（`:app`）所以只把**模型**搬上来 —— 屏要画它，就得三端看得见。
 */
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

/**
 * 时间戳 → `MM/dd HH:mm`（本地时区）。
 *
 * 搬迁前是 `SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())` —— `java.text` 是 JVM 专属，
 * `:core` 还要编到 wasm。数字与顺序逐字一致（`InboxScreen` / `SchoolCalendarScreen` 那边
 * 也是这么手写的）。
 */
internal fun formatDownloadTime(timestamp: Long): String {
    val at = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
    fun pad(value: Int) = value.toString().padStart(2, '0')
    return "${pad(at.monthNumber)}/${pad(at.dayOfMonth)} ${pad(at.hour)}:${pad(at.minute)}"
}

/** 文件大小的人类可读写法（搬迁前那份 `formatFileSize`，逐字搬）。 */
internal fun formatDownloadSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024.0 * 1024 * 1024)} GB"
}
