package com.xjtu.toolbox.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 下载管理屏要的那几件事 —— **屏不关心「谁在做」**：本机的引擎（Android / 桌面）还是 **serve 进程**（web）。
 *
 * ## 为什么要有这层缝（2026-10-21）
 *
 * 屏（[DownloadManagerScreen]）原来直接收一个具体的 [DownloadManager]：那是「本机引擎 + 本机登记表」
 * 的装配，**浏览器里造不出来**（`JvmDownloadEngine` / `JvmDownloadFileSystem` 都是 JVM 侧的实现）。
 * 会议 **#9 = 丙「做法①」** 又明确写了**不做影子版**（浏览器里不许造一个假引擎假装在下载）——
 * 所以这一端必须收一个**窄接口**：本机那份的实现就是 [DownloadManager] 自己（一字未改的语义），
 * web 那份走 `docs/api-contract.md` §5.13 的 `/api/downloads`（队列、进度、落盘都真的发生在 serve 那台机器上）。
 *
 * ## 两个如实约定
 *
 * - [videoDirPath] 与 [changes] 都是**屏上要的、但 serve 那份拿不到实时值**的两样：
 *   - [videoDirPath]：本机那份是常量（`fileSystem.downloadRoot`）；serve 那份**要等第一次列表回来才知道**
 *     （`GET /api/downloads` 的 `dir.location`）⇒ web 的实现给的是**最近一次知道的值**（首次列表之前是空串，
 *     而屏进页就会拉一次列表，所以弹窗里实际不会看到空的）；
 *   - [changes]：本机那份是真进度流；serve 那份**没有推送**（契约 §5.13 只给了轮询用的 `/progress`）
 *     ⇒ web 的实现是**定时拉一次列表**，语义仍是「有变化就重拉」。
 * - [resumeDownload] / [resumeAll] 声明成 `suspend` **不是为了等下载跑完**（本机那份仍然是丢一个协程就返回），
 *   而是因为 serve 那份要等一次 HTTP 往返。调用方按「这一发之后屏上自己会刷新」理解即可。
 */
interface DownloadsHost {

    /** 回放视频的保存目录（屏上「下载到哪」那一格）。见类 KDoc 里那条「可能滞后」的说明。 */
    val videoDirPath: String

    /** 「有变化」的信号：屏收到就重拉一次列表与统计。 */
    /** 「有变化」的信号：屏收到就重拉一次列表与统计。 */
    val changes: Flow<Unit>

    /**
     * 一句要告诉用户的话（动作没做成 / 取数失败）：屏把它弹出来（一次性提示弹窗）。
     *
     * 默认空流 —— **本机那一份永远不发**（它没有「会失败」的远端动作）；serve 那一份用它报 `409`
     * （「这条任务是『已完成』，现在这个动作对它没有意义」）与网络故障。
     */
    val notices: Flow<String> get() = emptyFlow()

    /**
     * 一次快照：任务全表 + 计数。
     *
     * ⚠️ **合成一发**（而不是分开两个方法）：任务与计数来自同一发才不会一前一后对不上；
     * serve 那一份也因此只需要一次 HTTP（契约 §5.13 的 `GET /api/downloads` 本来就一次给齐）。
     */
    suspend fun snapshot(): DownloadsSnapshot

    suspend fun pauseDownload(taskId: Long)

    suspend fun resumeDownload(taskId: Long)

    /** 取消：停掉在跑的那一发 + 删文件 + 删记录。 */
    suspend fun cancelDownload(taskId: Long)

    /** 删一条记录；[deleteFile] 为真时连文件一起删。 */
    suspend fun deleteTask(taskId: Long, deleteFile: Boolean = true)

    suspend fun pauseAll()

    suspend fun resumeAll()

    suspend fun cancelAll()
}

/** [DownloadsHost.snapshot] 的返回：屏上那两样（任务全表 + 计数）。 */
data class DownloadsSnapshot(val tasks: List<DownloadTask>, val stats: DownloadStats)
