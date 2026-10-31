package com.xjtu.toolbox.media

import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.ServeActionOutcome
import com.xjtu.toolbox.core.net.serveData
import com.xjtu.toolbox.core.net.serveOnly
import com.xjtu.toolbox.core.net.serveWrite
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 下载那一族的取数（`docs/api-contract.md` §5.13）—— **只在 serve 形态下存在**。
 *
 * 为什么它住在 `:feature:lms` 而不是像其余 `Campus*Api` 那样住 `:shared:network`：
 * 这一族的模型（[DownloadTask] / [DownloadStats] / [DownloadDirInfo]）就在本模块里，
 * 而 `:shared:network` 看不见 `:feature:*`（依赖方向是 feature → shared）。为此把模型搬去
 * `:shared:model` 是更大的动作，不值当 —— 这一份 API 跟着它的模型走。
 *
 * campus-api 形态没有这一族（那边点链接是**浏览器自己在下**，服务端不知情）⇒ 一律 [serveOnly]。
 */
class ServeDownloadsApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /** `GET /api/downloads`：任务全表 + 三个计数 + 目录那一格。 */
    suspend fun list(): ServeDownloadsList {
        requireServe()
        val data = client.serveData("列下载任务", baseUrl, LIST_PATH)
        val tasks = (data["tasks"] as? JsonArray)?.mapNotNull { element -> (element as? JsonObject)?.toTask() }.orEmpty()
        val dir = data["dir"] as? JsonObject
        return ServeDownloadsList(
            tasks = tasks,
            stats = DownloadStats(
                downloadingCount = data.int("downloading"),
                completedCount = data.int("completed"),
                activeCount = data.int("active"),
            ),
            dir = DownloadDirInfo(
                filesLocation = dir?.get("location").safeString().orEmpty(),
                notes = (dir?.get("notes") as? JsonArray)?.map { it.safeString() }.orEmpty(),
            ),
        )
    }

    /**
     * `POST /api/downloads`：新建一条（`{url, cameraType?, courseName?, activityTitle?, activityId?}`）。
     *
     * 回的是登记表的自增主键（屏上用不上，排障时有用）。
     */
    suspend fun enqueue(
        url: String,
        cameraType: String? = null,
        courseName: String? = null,
        activityTitle: String? = null,
        activityId: Int? = null,
    ): List<Long> {
        requireServe()
        val body = buildJsonObject {
            put("url", url)
            cameraType?.let { put("cameraType", it) }
            courseName?.let { put("courseName", it) }
            activityTitle?.let { put("activityTitle", it) }
            activityId?.let { put("activityId", it) }
        }.toString()
        val outcome = client.serveWrite(baseUrl, LIST_PATH, HttpMethod.Post, body)
        if (!outcome.ok) error("新建下载：${outcome.message}")
        return (outcome.data?.get("ids") as? JsonArray)
            ?.mapNotNull { it.safeString().toLongOrNull() }
            .orEmpty()
    }

    /** `POST /api/downloads/{id}/pause`。 */
    suspend fun pause(taskId: Long): ServeActionOutcome = verb(taskId, PAUSE)

    /** `POST /api/downloads/{id}/resume`。 */
    suspend fun resume(taskId: Long): ServeActionOutcome = verb(taskId, RESUME)

    /** `POST /api/downloads/{id}/cancel`（停掉在跑的那一发 + 删文件 + 删记录）。 */
    suspend fun cancel(taskId: Long): ServeActionOutcome = verb(taskId, CANCEL)

    /**
     * `DELETE /api/downloads/{id}`（`?deleteFile=false` 只删记录）。
     *
     * ⚠️ 与三个动词不同，这一条是**幂等**的（目标不在表里也是 `200`）；而「还在下载中」那一条回 `409`
     * —— 两种都是结果（[ServeActionOutcome.ok] 与 `message`），不是异常。
     */
    suspend fun delete(taskId: Long, deleteFile: Boolean = true): ServeActionOutcome {
        requireServe()
        return client.serveWrite(
            baseUrl,
            "$LIST_PATH/$taskId",
            HttpMethod.Delete,
            body = null,
            query = listOf("deleteFile" to if (deleteFile) "true" else "false"),
        )
    }

    private suspend fun verb(taskId: Long, verb: String): ServeActionOutcome {
        requireServe()
        // 三个动词没有请求体（serve 那一侧也不读）；空 JSON 体只是为了让 Content-Type 一致
        return client.serveWrite(baseUrl, "$LIST_PATH/$taskId/$verb", HttpMethod.Post, "{}")
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) {
            serveOnly("下载管理", "GET/POST /api/downloads{,/progress,/{id}} 与 /{id}/{pause,resume,cancel}")
        }
    }

    private companion object {
        const val LIST_PATH = "/api/downloads"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val CANCEL = "cancel"
    }
}

/** `GET /api/downloads` 一次拿到的三样（列表 / 计数 / 目录那一格）。 */
data class ServeDownloadsList(
    val tasks: List<DownloadTask>,
    val stats: DownloadStats,
    val dir: DownloadDirInfo,
)

// ── 线上形状 → `:core` 模型（字段名与契约 §5.13 逐条对应）────────────────

/** 一行 → [DownloadTask]；`id` 读不出来就丢掉这一行（它是屏上的句柄，空值等于点了没反应的卡）。 */
private fun JsonObject.toTask(): DownloadTask? {
    val id = this["id"].safeString().toLongOrNull() ?: return null
    val camera = this["cameraType"].safeString()
    return DownloadTask(
        id = id,
        activityId = int("activityId"),
        courseName = this["courseName"].safeString(),
        activityTitle = this["title"].safeString(),
        cameraType = camera,
        // ⚠️ 契约**不投影** `videoUrl`（那是直链，serve 那台机器自己在用）⇒ 本端这一格是空串
        videoUrl = "",
        audioSource = camera,
        filePath = this["filePath"].safeString(),
        fileSize = this["fileSize"].safeString().toLongOrNull() ?: -1L,
        downloadedSize = this["downloadedBytes"].safeString().toLongOrNull() ?: 0L,
        status = this["status"].safeString(),
        // `createTime` 是 ISO-8601 字符串（契约 §4）：屏上只拿它排「最近」的序，这里换成毫秒；
        // 转不动就是 0（= 本端不知道），不编一个时刻
        createTime = this["createTime"].safeString().let { iso ->
            if (iso.isBlank()) 0L
            else runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)
        },
        completeTime = null,
        errorMessage = this["errorMessage"].safeString().ifBlank { null },
        downloadSpeed = 0L,
    )
}

private fun JsonObject.int(key: String): Int = this[key].safeString().toIntOrNull() ?: 0
