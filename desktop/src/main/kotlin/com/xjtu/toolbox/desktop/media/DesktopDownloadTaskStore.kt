package com.xjtu.toolbox.desktop.media

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.accountScopedStoreName
import com.xjtu.toolbox.media.DownloadStatus
import com.xjtu.toolbox.media.DownloadTask
import com.xjtu.toolbox.media.DownloadTaskStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** 下载任务存储在桌面侧的填法 —— `:core` 的 [DownloadTaskStore] 实现。 */
class DesktopDownloadTaskStore(
    /** 存储；生产装配给的是那份 0600 的落盘文件（测试可传内存版做隔离）。 */
    private val store: KeyValueStore,
    /** 旧名（[STORE_NAME]，无账号后缀）的只读兜底：新名里没有时读它一次。 */
    private val legacyStore: KeyValueStore? = null,
) : DownloadTaskStore {

    /** 生产装配：端内名 = `download_tasks<账号后缀>`（[storeName]）+ 旧名的只读兜底。 */
    constructor(accountId: String? = AccountContext.activeAccountId) : this(
        store = secureKeyValueStore(storeName(accountId)),
        legacyStore = secureKeyValueStore(STORE_NAME),
    )

    private val lock = Mutex()

    /** 进程内的当前快照（构造时读一次；每次写都整份落盘）。 */
    private val rows: MutableList<DownloadTask> = read().toMutableList()

    private var lastId: Long = rows.maxOfOrNull { it.id } ?: 0L

    override suspend fun insert(task: DownloadTask): Long = lock.withLock {
        val id = ++lastId
        rows.add(task.copy(id = id))
        persist()
        id
    }

    override suspend fun getAll(): List<DownloadTask> = lock.withLock { rows.byCreateTimeDesc() }

    override suspend fun getActiveTasks(): List<DownloadTask> = lock.withLock {
        rows.filter { it.isActive }.byCreateTimeDesc()
    }

    override suspend fun getCompletedTasks(): List<DownloadTask> = lock.withLock {
        rows.filter { it.status == DownloadStatus.COMPLETED }.byCreateTimeDesc()
    }

    override suspend fun getDownloadingCount(): Int = lock.withLock {
        rows.count { it.status == DownloadStatus.DOWNLOADING }
    }

    override suspend fun getCompletedCount(): Int = lock.withLock {
        rows.count { it.status == DownloadStatus.COMPLETED }
    }

    override suspend fun getActiveCount(): Int = lock.withLock { rows.count { it.isActive } }

    override suspend fun getByStatus(status: String): List<DownloadTask> = lock.withLock {
        rows.filter { it.status == status }.byCreateTimeDesc()
    }

    override suspend fun deleteById(id: Long) {
        mutate { it.removeAll { row -> row.id == id } }
    }

    override suspend fun deleteCompleted() {
        mutate { it.removeAll { row -> row.status == DownloadStatus.COMPLETED } }
    }

    override suspend fun updateStatus(id: Long, status: String, errorMessage: String?) {
        // 与 Room 那条窄 UPDATE 同一条语义：status 与 errorMessage 一起写（默认 null = 清掉上次的失败原因）
        mutate { rows ->
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(status = status, errorMessage = errorMessage)
        }
    }

    override suspend fun updateProgress(id: Long, downloadedSize: Long, fileSize: Long) {
        mutate { rows ->
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(downloadedSize = downloadedSize, fileSize = fileSize)
        }
    }

    override suspend fun updateSpeed(id: Long, speed: Long) {
        mutate { rows ->
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(downloadSpeed = speed)
        }
    }

    private suspend fun mutate(block: (MutableList<DownloadTask>) -> Unit) = lock.withLock {
        block(rows)
        persist()
    }

    private fun persist() {
        store.putString(KEY_ROWS, encode(rows))
    }

    private fun read(): List<DownloadTask> {
        val raw = store.getString(KEY_ROWS) ?: legacyStore?.getString(KEY_ROWS) ?: return emptyList()
        val array = runCatching { AppJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val filePath = o.text("filePath")
            if (filePath.isEmpty()) return@mapNotNull null
            DownloadTask(
                id = o.text("id").toLongOrNull() ?: 0L,
                activityId = o.text("activityId").toIntOrNull() ?: 0,
                courseName = o.text("courseName"),
                activityTitle = o.text("activityTitle"),
                cameraType = o.text("cameraType"),
                videoUrl = o.text("videoUrl"),
                audioSource = o.text("audioSource"),
                filePath = filePath,
                fileSize = o.text("fileSize").toLongOrNull() ?: -1L,
                downloadedSize = o.text("downloadedSize").toLongOrNull() ?: 0L,
                status = o.text("status").ifBlank { DownloadStatus.PENDING },
                createTime = o.text("createTime").toLongOrNull() ?: 0L,
                completeTime = o.nullableText("completeTime")?.toLongOrNull(),
                errorMessage = o.nullableText("errorMessage"),
                downloadSpeed = o.text("downloadSpeed").toLongOrNull() ?: 0L,
            )
        }
    }

    private fun encode(items: List<DownloadTask>): String = buildJsonArray {
        items.forEach { row ->
            add(
                buildJsonObject {
                    put("id", row.id)
                    put("activityId", row.activityId)
                    put("courseName", row.courseName)
                    put("activityTitle", row.activityTitle)
                    put("cameraType", row.cameraType)
                    put("videoUrl", row.videoUrl)
                    put("audioSource", row.audioSource)
                    put("filePath", row.filePath)
                    put("fileSize", row.fileSize)
                    put("downloadedSize", row.downloadedSize)
                    put("status", row.status)
                    put("createTime", row.createTime)
                    put("completeTime", row.completeTime?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("errorMessage", row.errorMessage?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("downloadSpeed", row.downloadSpeed)
                },
            )
        }
    }.toString()

    /** 缺字段 / JSON null 都当空串。 */
    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }

    /** 同 [text]，但字段本来就没有时返回 null（`completeTime` / `errorMessage` 是可空列）。 */
    private fun JsonObject.nullableText(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    companion object {
        /** 端内 store 名的基础部分；完整端内名见 [storeName]。 */
        const val STORE_NAME = "download_tasks"

        /** 端内名（`download_tasks<账号后缀>`）。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            accountScopedStoreName(STORE_NAME, accountId)

        private const val KEY_ROWS = "rows"
    }
}

/** 与 Android 那条 SQL 的 `ORDER BY createTime DESC` 一致。 */
private fun List<DownloadTask>.byCreateTimeDesc(): List<DownloadTask> = sortedByDescending { it.createTime }
