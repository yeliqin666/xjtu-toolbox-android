package com.xjtu.toolbox.server

import com.xjtu.toolbox.media.DownloadItem
import com.xjtu.toolbox.media.DownloadManager
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.media.JvmDownloadEngine
import com.xjtu.toolbox.media.JvmDownloadFileSystem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable

/**
 * `/api/downloads` —— 下载管理在浏览器上的那一族（`docs/api-contract.md` §5.13）。
 *
 * ## 口径（会议 #9 = 丙，**做法①**）
 *
 * 浏览器里没有「App 管的下载队列」这一回事（用户点链接是浏览器自己在下，serve 不知情）。
 * 所以这一族**不是**「把登记表端出来给人看」的影子版，而是：
 *
 * > **serve 进程里装真的下载引擎**（`:feature:lms` 的 [DownloadManager] + `:data` 的
 * > [JvmDownloadEngine] / [JvmDownloadFileSystem]，与桌面窗口模式同一套），**浏览器只当界面**。
 *
 * 于是队列、进度、删除、落盘（落到**那台机器**的 `~/Downloads`）都是真的。
 *
 * | 端点 | 方法 | 干什么 |
 * |---|---|---|
 * | [DOWNLOAD_PATH] | `GET` | 列任务（含进度与状态） |
 * | [DOWNLOAD_PATH] | `POST` | 新建一条（body `{url, cameraType?, courseName?, activityTitle?, activityId?}`） |
 * | `[DOWNLOAD_PATH]/{id}` | `DELETE` | 删一条（`?deleteFile=false` 只删记录；**还在下载中 ⇒ `409`**） |
 * | `[DOWNLOAD_PATH]/[DOWNLOAD_PROGRESS]` | `GET` | 只回进度（屏上轮询它，别每次拉全表） |
 * | `[DOWNLOAD_PATH]/{id}/[DOWNLOAD_PAUSE]` | `POST` | 暂停（只对 `downloading` / `pending` 有意义） |
 * | `[DOWNLOAD_PATH]/{id}/[DOWNLOAD_RESUME]` | `POST` | 恢复（只对 `paused` / `pending` 有意义） |
 * | `[DOWNLOAD_PATH]/{id}/[DOWNLOAD_CANCEL]` | `POST` | 取消：停掉在跑的那一发 + 删文件 + 删记录 |
 *
 * 三个动作动词（2026-10-21 晚补的，桌面那一屏本来就直接调 [DownloadManager] 的这三个方法）：
 * 它们与 `DELETE` 的分工是「**对这一条做一件事**」vs「**删掉就没了**」——
 * 所以目标不在表里、或状态不允许时一律 `409` + 中文短句（**不谎报成功**），
 * 而 `DELETE` 仍是幂等的 `200`。两处状态闸门（[PAUSABLE_STATUSES] / [RESUMABLE_STATUSES]）
 * 是必要的：`:data` 的 `pauseDownload` **不看状态**（拿一个已完成的任务调它会把那一行改回「已暂停」，
 * 之后 `resumeAll` 还会把它重新下一遍）。
 *
 * ## 进门条件（2026-10-21 本批补的）
 *
 * 四条都**要求已登录**（`session.authenticated`，未登录回 401 指路）。上一轮落地时只挂了令牌闸门、
 * 没挂登录判据，后果是：拿到令牌就能让**那台机器**去取任意 URL 并往操作者的下载目录里写文件；
 * 而且 `AccountContext.activeAccountId` 还是 null 时，登记表会落到 `serve_download_tasks_default`
 * 那个命名空间。本批与其余二十多族拉齐（均要求登录），如实记在契约 §5.13。
 *
 * ## 本批顺手修的三处（都是上一轮没测试所以没暴露的）
 *
 * 1. **路由挂错了**：原本写的是绝对路径 `/api/downloads`，而这棵子树已经挂在 `route("/api")` 之下
 *    ⇒ 实际注册成 `/api/api/downloads`，**整族在线上也是 404**。现在改挂相对段 [DOWNLOAD_SEGMENT]。
 * 2. **回的是裸对象**：四条都直接 `respond(DTO)` ⇒ 没有 §4 的信封（`{code,data,message}`）。
 *    本批全部包上 `ApiEnvelope.ok(…)`（这一族不在 §4 的例外清单里）。
 * 3. **删「正在下载」那一条是静默空操作**（`:data` 的 `deleteTask` 只在非 downloading 时真删），
 *    而旧代码照旧回 200 + 成功形状 ⇒ 屏上点删除看起来成功、那一行还在。
 *    现在**回查一次**再答：行还在就回 `409` + [DOWNLOAD_BUSY_MESSAGE]。
 *
 * ## 如实边界
 *
 * - 登记表用**自己的店名**（`serve_download_tasks<账号后缀>`，见 [ServeDownloadTaskStore]）⇒
 *   **与桌面窗口的列表不共享**（理由在那份 KDoc 里：共用一个文件会互相写坏）。
 * - `:data` 的引擎只支持**可直接 GET 的直链**（HLS 的 `m3u8` 不支持）⇒ 遇到 m3u8 在这里就拒，
 *   不交给引擎去失败。
 * - 目录不可写 / 网络断 ⇒ 由引擎把任务置成 `failed` 并写 `errorMessage`，这里**照实回**。
 */

/** `/api/downloads` 的段名（拼 [DOWNLOAD_PATH] 用）。 */
internal const val DOWNLOAD_SEGMENT = "downloads"

/** 这一族的根路径。 */
internal const val DOWNLOAD_PATH = "$API_PREFIX/$DOWNLOAD_SEGMENT"

/** 只回进度的那一条（屏上轮询）。 */
internal const val DOWNLOAD_PROGRESS = "progress"

/** serve 侧的下载装配（与桌面的 `DesktopDownloads` 对位；进程级一份）。 */
internal object ServeDownloads {

    val manager: DownloadManager by lazy {
        DownloadManager(
            store = ServeDownloadTaskStore(),
            engine = JvmDownloadEngine(),
            fileSystem = JvmDownloadFileSystem(),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /** 下载目录信息（屏上那一格）。与桌面同一套措辞，但说清是**哪台机器**的目录。 */
    fun dirInfo(manager: DownloadManager = this.manager): DownloadDirData =
        DownloadDirData(location = manager.videoDirPath, notes = DIR_NOTES)

    /** 目录那一格的说明（三条）。与桌面同一套措辞。 */
    private val DIR_NOTES = listOf(
        "下载落在**运行 serve 的那台机器**上（不是浏览器所在的设备）",
        "浏览器只是界面：队列、进度、删除都是 serve 在真的做",
        "只支持可直接 GET 的直链；HLS（m3u8）不支持",
    )
}

internal fun Route.downloadRoutes(
    session: ServeSession,
    manager: DownloadManager = ServeDownloads.manager,
) {
    // 相对段而不是绝对路径：这棵子树已经挂在 `route("/api")` 之下（先例见 SessionRoutes / AccountRoutes）。
    // ⚠️ 2026-10-21：这里原本写的是绝对路径 [DOWNLOAD_PATH] ⇒ 实际注册成 `/api/api/downloads`，
    // 整族在线上也是 404（上一轮没写契约测试，所以没发现）。本批的 `ServeDownloadRoutesTest` 钉住它。
    route(DOWNLOAD_SEGMENT) {

        get {
            if (!session.authenticated) return@get call.respondLoginRequired()
            runCatching {
                val tasks = manager.getAllTasks()
                val stats = manager.getDownloadStats()
                DownloadListData(
                    count = tasks.size,
                    downloading = stats.downloadingCount,
                    completed = stats.completedCount,
                    active = stats.activeCount,
                    dir = ServeDownloads.dirInfo(manager),
                    tasks = tasks.map { it.toDto() },
                )
            }.onSuccess { call.respond(ApiEnvelope.ok(it)) }
                .onFailure { call.respondUpstreamFailure(it, "列下载任务") }
        }

        post {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<DownloadEnqueueBody>()
                ?: return@post call.respondBadRequestMessage(DOWNLOAD_BODY_MESSAGE)
            val url = body.url.trim()
            if (url.isEmpty()) return@post call.respondBadRequestMessage(DOWNLOAD_BODY_MESSAGE)
            if (url.contains(".m3u8")) return@post call.respondBadRequestMessage(DOWNLOAD_M3U8_MESSAGE)
            val camera = body.cameraType?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CAMERA
            runCatching {
                val ids = manager.enqueueDownloads(
                    courseName = body.courseName?.trim().orEmpty().ifEmpty { DEFAULT_COURSE },
                    activityTitle = body.activityTitle?.trim().orEmpty().ifEmpty { DEFAULT_TITLE },
                    activityId = body.activityId ?: 0,
                    videos = listOf(DownloadItem(cameraType = camera, url = url)),
                )
                DownloadEnqueueData(ids = ids)
            }.onSuccess { call.respond(ApiEnvelope.ok(it)) }
                .onFailure { call.respondUpstreamFailure(it, "新建下载") }
        }

        get(DOWNLOAD_PROGRESS) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            runCatching {
                val tasks = manager.getAllTasks()
                DownloadProgressData(
                    downloading = tasks.count { it.status == DownloadStatus.DOWNLOADING },
                    active = tasks.count { it.isActive },
                    completed = tasks.count { it.status == DownloadStatus.COMPLETED },
                    tasks = tasks.filter { it.isActive }.map {
                        DownloadProgressRow(
                            id = it.id,
                            title = it.activityTitle,
                            downloadedBytes = it.downloadedSize,
                            totalBytes = it.fileSize,
                            progress = it.progress,
                            status = it.status,
                            statusLabel = it.statusLabel,
                            errorMessage = it.errorMessage,
                        )
                    },
                )
            }.onSuccess { call.respond(ApiEnvelope.ok(it)) }
                .onFailure { call.respondUpstreamFailure(it, "读下载进度") }
        }

        delete("{id}") {
            if (!session.authenticated) return@delete call.respondLoginRequired()
            val id = call.parameters["id"]?.trim()?.toLongOrNull()
                ?: return@delete call.respondBadRequestMessage(DOWNLOAD_ID_MESSAGE)
            val deleteFile = call.request.queryParameters["deleteFile"]?.trim() != "false"
            runCatching {
                manager.deleteTask(id, deleteFile = deleteFile)
                // ⚠️ `:data` 的 `deleteTask` 对「正在下载」那一条是**空操作**（它只在非 downloading 时真删）。
                // 回查一次再答：行还在 ⇒ 如实回 409，不谎报成功（屏上点删除、那一行却还在，是最坏的一种）。
                manager.getAllTasks().any { it.id == id && it.status == DownloadStatus.DOWNLOADING }
            }
                .onSuccess { stillDownloading ->
                    if (stillDownloading) {
                        call.respondActionRejected(DOWNLOAD_BUSY_MESSAGE)
                    } else {
                        call.respond(ApiEnvelope.ok(DownloadDeleteData(id = id, deletedFile = deleteFile)))
                    }
                }
                .onFailure { call.respondUpstreamFailure(it, "删除下载") }
        }

        // ── 三个动作动词（暂停 / 恢复 / 取消）──────────────────────────
        // 与 DELETE 的分工：DELETE 是「删掉就没了」（幂等，200）；这三个是「对这一条做一件事」，
        // 所以**目标不在表里、或状态不允许时一律 409 + 中文短句**（不谎报成功）。
        post("{id}/$DOWNLOAD_PAUSE") {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val id = call.parameters["id"]?.trim()?.toLongOrNull()
                ?: return@post call.respondBadRequestMessage(DOWNLOAD_ID_MESSAGE)
            runCatching {
                val task = manager.getAllTasks().firstOrNull { it.id == id }
                // ⚠️ `:data` 的 `pauseDownload` **不看当前状态**（直接 updateStatus(PAUSED)）⇒ 拿一个已完成的任务
                // 调它会把那一行改回「已暂停」（之后 resumeAll 还会把它重新下一遍）。所以本层先判状态。
                when {
                    task == null -> DownloadActionRefusal.GONE
                    task.status !in PAUSABLE_STATUSES -> DownloadActionRefusal.WRONG_STATE(task.statusLabel)
                    else -> {
                        manager.pauseDownload(id)
                        null
                    }
                }
            }
                .onSuccess { refusal ->
                    if (refusal == null) call.respond(ApiEnvelope.ok(DownloadActionData(id = id)))
                    else call.respondActionRejected(refusal.message)
                }
                .onFailure { call.respondUpstreamFailure(it, "暂停下载") }
        }

        post("{id}/$DOWNLOAD_RESUME") {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val id = call.parameters["id"]?.trim()?.toLongOrNull()
                ?: return@post call.respondBadRequestMessage(DOWNLOAD_ID_MESSAGE)
            runCatching {
                val task = manager.getAllTasks().firstOrNull { it.id == id }
                when {
                    task == null -> DownloadActionRefusal.GONE
                    task.status !in RESUMABLE_STATUSES -> DownloadActionRefusal.WRONG_STATE(task.statusLabel)
                    else -> {
                        manager.resumeDownload(id)
                        null
                    }
                }
            }
                .onSuccess { refusal ->
                    if (refusal == null) call.respond(ApiEnvelope.ok(DownloadActionData(id = id)))
                    else call.respondActionRejected(refusal.message)
                }
                .onFailure { call.respondUpstreamFailure(it, "恢复下载") }
        }

        post("{id}/$DOWNLOAD_CANCEL") {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val id = call.parameters["id"]?.trim()?.toLongOrNull()
                ?: return@post call.respondBadRequestMessage(DOWNLOAD_ID_MESSAGE)
            runCatching {
                val task = manager.getAllTasks().firstOrNull { it.id == id }
                if (task == null) {
                    DownloadActionRefusal.GONE
                } else {
                    // 取消 = 停掉在跑的那一发 + 删文件 + 删记录（`:data` 的 `cancelDownload` 三件都做）
                    manager.cancelDownload(id)
                    null
                }
            }
                .onSuccess { refusal ->
                    if (refusal == null) call.respond(ApiEnvelope.ok(DownloadActionData(id = id)))
                    else call.respondActionRejected(refusal.message)
                }
                .onFailure { call.respondUpstreamFailure(it, "取消下载") }
        }
    }
}

/** 一条任务在屏上的样子（字段与 `:core` 的屏对齐）。 */
@Serializable
internal data class DownloadTaskDto(
    val id: Long,
    val title: String,
    val courseName: String,
    val cameraType: String,
    val filePath: String,
    val fileSize: Long,
    val downloadedBytes: Long,
    val progress: Float,
    val status: String,
    val statusLabel: String,
    val createTime: String? = null,
    val errorMessage: String? = null,
)

/** 下载目录那一格（屏上「下载到哪」的说明）。 */
@Serializable
internal data class DownloadDirData(val location: String, val notes: List<String>)

/** `GET /api/downloads` 的 `data`。 */
@Serializable
internal data class DownloadListData(
    val count: Int,
    val downloading: Int,
    val completed: Int,
    val active: Int,
    val dir: DownloadDirData,
    val tasks: List<DownloadTaskDto>,
)

/** `POST /api/downloads` 的请求体。 */
@Serializable
internal data class DownloadEnqueueBody(
    val url: String,
    val cameraType: String? = null,
    val courseName: String? = null,
    val activityTitle: String? = null,
    val activityId: Int? = null,
)

/** `POST /api/downloads` 的 `data`。 */
@Serializable
internal data class DownloadEnqueueData(val ids: List<Long>)

/** `GET /api/downloads/progress` 的一行。 */
@Serializable
internal data class DownloadProgressRow(
    val id: Long,
    val title: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Float,
    val status: String,
    val statusLabel: String,
    val errorMessage: String? = null,
)

/** `GET /api/downloads/progress` 的 `data`。 */
@Serializable
internal data class DownloadProgressData(
    val downloading: Int,
    val active: Int,
    val completed: Int,
    val tasks: List<DownloadProgressRow>,
)

/** `DELETE /api/downloads/{id}` 的 `data`。 */
@Serializable
internal data class DownloadDeleteData(val id: Long, val deletedFile: Boolean)

/**
 * 三个动作动词（暂停 / 恢复 / 取消）的 `data`。
 *
 * ⚠️ **只回 `ok` 与 `id`，不回状态**：`:data` 的 `resumeDownload` 只是丢一个协程（那一刻状态还没变）
 * ⇒ 报一个状态就是编事实。屏上照旧轮询 `/progress` 或重拉列表（与桌面那一屏同一步）。
 */
@Serializable
internal data class DownloadActionData(val ok: Boolean = true, val id: Long)

/**
 * 三个动词的拒绝理由（都落 `409` + 中文短句）。
 *
 * 为什么不是静默空操作：这三个是「对这一条做一件事」，目标不在表里 / 状态不允许时
 * **屏上那一下点了就是没发生** —— 与 DELETE 那条静默空操作是同一类毛病（那个已在本批修）。
 */
private sealed interface DownloadActionRefusal {
    val message: String

    /** 这一条不在表里（已被删 / id 抄错）。 */
    data object GONE : DownloadActionRefusal {
        override val message: String = "没有这条下载任务（可能已经被删掉了）—— 下拉刷新看看列表"
    }

    /** 状态不允许这个动作（例如对「已完成」调暂停）。 */
    data class WRONG_STATE(val label: String) : DownloadActionRefusal {
        override val message: String = "这条任务是「$label」，现在这个动作对它没有意义"
    }
}

private fun DownloadTask.toDto(): DownloadTaskDto = DownloadTaskDto(
    id = id,
    title = activityTitle,
    courseName = courseName,
    cameraType = cameraType,
    filePath = filePath,
    fileSize = fileSize,
    downloadedBytes = downloadedSize,
    progress = progress,
    status = status,
    statusLabel = statusLabel,
    createTime = isoTime(createTime),
    errorMessage = errorMessage,
)

private const val DEFAULT_CAMERA = "encoder"
private const val DEFAULT_COURSE = "回放"
private const val DEFAULT_TITLE = "回放"

private const val DOWNLOAD_BODY_MESSAGE = "请求体需要 url（视频直链）；cameraType / courseName / activityTitle / activityId 可选"
private const val DOWNLOAD_M3U8_MESSAGE = "这一条是 HLS（m3u8）：本端引擎只支持可直接 GET 的直链，请换一条"
private const val DOWNLOAD_ID_MESSAGE = "路径里的 id 需要是数字（那一条下载任务的主键）"

/** 三个动作动词的子路径。 */
internal const val DOWNLOAD_PAUSE = "pause"
internal const val DOWNLOAD_RESUME = "resume"
internal const val DOWNLOAD_CANCEL = "cancel"

/** 可以暂停的状态（`:data` 的 `pauseDownload` 不看状态，所以由本层把住）。 */
private val PAUSABLE_STATUSES = setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING)

/** 可以恢复的状态（对「已完成」调恢复会把那一集重下一遍）。 */
private val RESUMABLE_STATUSES = setOf(DownloadStatus.PAUSED, DownloadStatus.PENDING)
/** 「这条还在下载中，删不掉」那一档（409，见 DELETE 那一段的注释）。 */
private const val DOWNLOAD_BUSY_MESSAGE =
    "这条任务还在下载中，删不掉：先暂停（`POST /api/downloads/{id}/pause`）或取消（`…/cancel`）它"
