package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.media.DownloadTaskStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * serve 侧的下载登记表（`docs/api-contract.md` §5.13）。
 *
 * ## 为什么用**自己的店名**
 *
 * 桌面的那份是 `download_tasks<账号后缀>.properties`，而 `DownloadTask` **不是** `@Serializable`
 * （桌面那份是手工拼 JSON 的）。serve 若去读写同一个文件，两边的手工格式一旦有一处不一致，
 * 就会「对方写的行读不出来 ⇒ 下一次整份回写把它抹掉」——**静默丢用户的下载记录**。
 * 所以这一端用自己的店名（`serve_download_tasks<账号后缀>`），格式只由本文件定义，
 * 与桌面互不干扰；代价是两边的列表不共享（如实写在契约里）。
 *
 * ## 形状
 *
 * 一行一条任务，键 `rows` 存一个 JSON 数组；字段名与 `DownloadTask` 的属性逐字同名
 * （便于将来真做跨端合并时对齐）。
 */
internal class ServeDownloadTaskStore(
    private val store: KeyValueStore = secureKeyValueStore(storeName(AccountContext.activeAccountId)),
) : DownloadTaskStore {

    private val lock = Mutex()

    /** 进程内的当前快照（构造时读一次；每次写整份落盘）——与桌面那份同一条做法。 */
    private val rows: MutableList<DownloadTask> = read().toMutableList()
    private var lastId: Long = rows.maxOfOrNull { it.id } ?: 0L

    override suspend fun insert(task: DownloadTask): Long = lock.withLock {
        val id = ++lastId
        rows.add(task.copy(id = id))
        persist()
        id
    }

    override suspend fun getAll(): List<DownloadTask> = lock.withLock { rows.byCreateTimeDesc() }

    override suspend fun getActiveTasks(): List<DownloadTask> =
        lock.withLock { rows.filter { it.isActive }.byCreateTimeDesc() }

    override suspend fun getCompletedTasks(): List<DownloadTask> =
        lock.withLock { rows.filter { it.status == DownloadStatus.COMPLETED }.byCreateTimeDesc() }

    override suspend fun getDownloadingCount(): Int =
        lock.withLock { rows.count { it.status == DownloadStatus.DOWNLOADING } }

    override suspend fun getCompletedCount(): Int =
        lock.withLock { rows.count { it.status == DownloadStatus.COMPLETED } }

    override suspend fun getActiveCount(): Int = lock.withLock { rows.count { it.isActive } }

    override suspend fun getByStatus(status: String): List<DownloadTask> =
        lock.withLock { rows.filter { it.status == status }.byCreateTimeDesc() }

    override suspend fun deleteById(id: Long) = lock.withLock {
        rows.removeAll { it.id == id }
        persist()
    }

    override suspend fun deleteCompleted() = lock.withLock {
        rows.removeAll { it.status == DownloadStatus.COMPLETED }
        persist()
    }

    override suspend fun updateStatus(id: Long, status: String, errorMessage: String?) = lock.withLock {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(status = status, errorMessage = errorMessage)
            persist()
        }
    }

    override suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long) = lock.withLock {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(downloadedSize = downloadedSize, fileSize = fileSize)
            persist()
        }
    }

    override suspend fun updateSpeed(id: Long, speed: Long) = lock.withLock {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(downloadSpeed = speed)
            persist()
        }
    }

    private fun read(): List<DownloadTask> {
        val raw = store.getString(KEY_ROWS) ?: return emptyList()
        return runCatching { AppJson.parseToJsonElement(raw).jsonArray.map { it.jsonObject.toTask() } }
            .getOrDefault(emptyList())
    }

    private fun persist() {
        runCatching { store.putString(KEY_ROWS, encode(rows).toString()) }
    }

    private fun encode(list: List<DownloadTask>): JsonArray = buildJsonArray {
        list.forEach { task ->
            add(
                buildJsonObject {
                    put("id", task.id)
                    put("activityId", task.activityId)
                    put("courseName", task.courseName)
                    put("activityTitle", task.activityTitle)
                    put("cameraType", task.cameraType)
                    put("videoUrl", task.videoUrl)
                    put("audioSource", task.audioSource)
                    put("filePath", task.filePath)
                    put("fileSize", task.fileSize)
                    put("downloadedSize", task.downloadedSize)
                    put("status", task.status)
                    put("createTime", task.createTime)
                    put("completeTime", task.completeTime?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("errorMessage", task.errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("downloadSpeed", task.downloadSpeed)
                },
            )
        }
    }

    private fun JsonObject.toTask(): DownloadTask = DownloadTask(
        id = long("id"),
        activityId = int("activityId"),
        courseName = string("courseName"),
        activityTitle = string("activityTitle"),
        cameraType = string("cameraType"),
        videoUrl = string("videoUrl"),
        audioSource = string("audioSource"),
        filePath = string("filePath"),
        fileSize = long("fileSize"),
        downloadedSize = long("downloadedSize"),
        status = string("status"),
        createTime = long("createTime"),
        completeTime = this["completeTime"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull(),
        errorMessage = this["errorMessage"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        downloadSpeed = long("downloadSpeed"),
    )

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.int(name: String): Int =
        this[name]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.content.orEmpty()

    companion object {
        /** 端内店名的基础部分（账号后缀见 [storeName]）。 */
        const val STORE_NAME = "serve_download_tasks"

        /** 那一行的键（一行一条任务，值是一个 JSON 数组）。 */
        const val KEY_ROWS = "rows"

        /** 端内名 = `serve_download_tasks<账号后缀>`。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            STORE_NAME + AccountContext.suffixFor(accountId)
    }
}

private fun List<DownloadTask>.byCreateTimeDesc(): List<DownloadTask> = sortedByDescending { it.createTime }
