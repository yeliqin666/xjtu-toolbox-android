package com.xjtu.toolbox.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.media.DownloadDirInfo
import com.xjtu.toolbox.media.DownloadStats
import com.xjtu.toolbox.media.DownloadsHost
import com.xjtu.toolbox.media.DownloadsSnapshot
import com.xjtu.toolbox.media.ServeDownloadsApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * 下载管理（`AppRoute.DownloadManager`）在**浏览器这一端**的宿主。
 *
 * 屏是 `:core` 那份 [com.xjtu.toolbox.media.DownloadManagerScreen]，宿主走契约 §5.13 的 `/api/downloads`：
 * **队列、进度、落盘都真的发生在运行 serve 的那台机器上**，浏览器只是界面（会议 #9 = 丙「做法①」，
 * 所以这里**没有**影子引擎 —— 见 [DownloadsHost] 的 KDoc）。
 *
 * 三处如实差异（都写在契约 §5.13 与 [DownloadsHost] 的 KDoc 里）：
 *
 * 1. **没有推送** ⇒ [changes] 是轮询（契约给的就是「屏上轮询 `/progress`」那一档）；
 * 2. **[dirInfo] 要等第一次列表回来才有值**（它来自响应的 `dir` 那一格）；
 * 3. **播放按钮不画**（文件在那台机器上，浏览器打不开它）—— 由调用方传 `onPlayTask = null` 决定。
 */
internal class WebDownloadsHost(private val api: ServeDownloadsApi) : DownloadsHost {

    /** 要告诉用户的话（屏上那张一次性弹窗读它）：`409` 的文案 / 网络故障。 */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override val notices: Flow<String> = _notices.asSharedFlow()

    /** 下载目录那一格（屏上的弹窗用它）。首次列表之前是空串 + 空说明。 */
    var dirInfo by mutableStateOf(DownloadDirInfo(filesLocation = "", notes = emptyList()))
        private set

    override val videoDirPath: String get() = dirInfo.filesLocation

    /** 轮询凑出「有变化」这个信号（serve 那一侧没有推送，见类 KDoc 第 1 条）。 */
    override val changes: Flow<Unit> = flow {
        while (true) {
            delay(POLL_INTERVAL_MS)
            emit(Unit)
        }
    }

    override suspend fun snapshot(): DownloadsSnapshot {
        // 取数失败也要有说法（serve 那边会话过期 / 没登录都会落这里）：发一句提示 + 回空快照，
        // 屏上就只剩那一句「没做成」，而不是一个看起来像「一个下载都没有」的空列表。
        val list = runCatching { api.list() }.getOrElse { error ->
            _notices.tryEmit(FriendlyError.of(error, "读下载列表"))
            return DownloadsSnapshot(tasks = emptyList(), stats = DownloadStats(0, 0, 0))
        }
        // 目录那一格跟着列表一起回来（同一个响应），顺手更新给屏上的弹窗用
        dirInfo = list.dir
        return DownloadsSnapshot(tasks = list.tasks, stats = list.stats)
    }

    override suspend fun pauseDownload(taskId: Long) = action("暂停", api.pause(taskId))

    override suspend fun resumeDownload(taskId: Long) = action("恢复", api.resume(taskId))

    override suspend fun cancelDownload(taskId: Long) = action("取消", api.cancel(taskId))

    override suspend fun deleteTask(taskId: Long, deleteFile: Boolean) =
        action("删除", api.delete(taskId, deleteFile))

    /**
     * 三个「全部」动作：serve 那一族**没有**批量端点（桌面那一屏是本机循环调的）⇒ 这里也循环。
     *
     * ⚠️ 逐条调的好处是**失败只影响那一条**（例如「已完成」那一条会回 409，其余照常）；坏处是 N 发请求。
     * 批量端点要不要开，见契约 §5.13 末尾那条待拍项。
     */
    override suspend fun pauseAll() {
        val tasks = api.list().tasks
        tasks.filter { it.status == STATUS_DOWNLOADING || it.status == STATUS_PENDING }
            .forEach { runCatching { api.pause(it.id) } }
    }

    override suspend fun resumeAll() {
        val tasks = api.list().tasks
        tasks.filter { it.status == STATUS_PAUSED || it.status == STATUS_PENDING }
            .forEach { runCatching { api.resume(it.id) } }
    }

    override suspend fun cancelAll() {
        val tasks = api.list().tasks.filter { it.isActive }
        tasks.forEach { runCatching { api.cancel(it.id) } }
    }

    /**
     * 一次动作：服务端明确拒绝（`409` + 中文短句）时**发一句提示**（不抛）。
     *
     * 为什么不抛：屏上那七个动作点都写在 `scope.launch { }` 里，抛出去就是一条未捕获异常（Compose
     * 的协程作用域里会直接断掉那一支）—— 而「这条任务是『已完成』，现在这个动作对它没有意义」是
     * **正常的业务结果**，不是异常。屏收到 [notices] 就弹一个一次性提示（见 `DownloadNoticeDialog`）。
     */
    private fun action(what: String, outcome: com.xjtu.toolbox.core.net.ServeActionOutcome) {
        if (!outcome.ok) _notices.tryEmit("$what：${outcome.message}")
    }

    private companion object {
        /** 轮询间隔：屏上那一行进度不必更密，5 秒够用（也免得白耗电）。 */
        const val POLL_INTERVAL_MS = 5_000L

        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PENDING = "pending"
        const val STATUS_PAUSED = "paused"
    }
}

