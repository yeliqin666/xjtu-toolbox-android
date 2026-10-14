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

/**
 * 下载任务存储在**桌面侧的填法** —— `:core` 的 [DownloadTaskStore] 实现。
 *
 * ## 为什么不是 Room
 *
 * 搬迁前这份数据是 Room 表（`androidx.room` + `Context`），桌面端没有那一套。这里用**一份 JSON 落盘**：
 * 把行数组序列化成一个字符串，存进 `:data` 的密文键值缝 [secureKeyValueStore]
 * —— 它在本端就是 `<数据根>/download_tasks<账号后缀>.properties`（S7b；权限 0600，与 cookie /
 * 凭据同一处。加后缀之前那份旧名 `download_tasks` 只会被**只读**读一次，见构造器的 [legacyStore]），
 * 写入是同步的，所以「暂停时那一发状态」不需要额外的协程。
 *
 * 行数很少（我排过的回放就那几条），所以每次写整份重写；JSON 手写而不是给模型加 `@Serializable`：
 * `:core` 的 [DownloadTask] 是业务模型，不为了某一端的存储去改它的形状。
 *
 * ## 与 Android 的差异（如实）
 *
 * | | Android（Room 表 `download_tasks`） | 桌面（这里） |
 * |---|---|---|
 * | 存储 | SQLite（有索引、有事务、升级不丢） | 一份 0600 的 Properties 文件，值是一段 JSON |
 * | 排序 / 计数 | SQL（`ORDER BY createTime DESC`、`COUNT(*)`） | 内存里做同一件事（口径写在 [DownloadTaskStore] 的 KDoc 里） |
 * | 自增 id | `@PrimaryKey(autoGenerate = true)` | 内存里的 `maxOf(id) + 1`（**重启后不会与原表冲突**：这张表是本端独有的） |
 * | 并发 | SQLite 事务 | 一把 `Mutex`（写整份重写，读改都在锁里） |
 * | 多进程 | 任何进程读同一份库 | **只有本进程看得见**（单实例应用的正常取舍） |
 * | 老数据 | 原表原样、迁移路径未动 | 队列本身没老数据（桌面第一次有这个队列）；端内名从 `download_tasks` 变成带账号后缀时只**只读**读一次旧名（S7b） |
 *
 * 也就是说：**共享层只承诺语义**（见 [DownloadTaskStore]），Android 那条 Room 路径一个字都没动。
 */
class DesktopDownloadTaskStore(
    /** 存储；生产装配给的是那份 0600 的落盘文件（测试可传内存版做隔离）。 */
    private val store: KeyValueStore,
    /**
     * S7b 之前那份旧名（[STORE_NAME]，无账号后缀）的**只读**兜底：新名里没有行时读它一次
     * （§6.1 第 2 条「旧形状只读」—— 只读，不往回写、也不删旧文件）。
     *
     * 默认 null：测试注入 [store] 时不该顺带读到隔离根之外的旧文件（生产装配见下面那个构造器）。
     */
    private val legacyStore: KeyValueStore? = null,
) : DownloadTaskStore {

    /**
     * 生产装配（S7b）：端内名 = `download_tasks<账号后缀>`（[storeName]）+ 旧名的只读兜底。
     *
     * ⚠️ 名字在**建实例时**定下来：`DesktopDownloads.manager` 是进程级单例 ⇒ 这条队列属于
     * 「本进程第一次碰它时那个账号」（未定项见 `accountScopedStoreName` 的 KDoc）。
     */
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
        // S7b：新名里没有行时，读一次旧名那份（旧形状只读）
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

    /** 缺字段 / JSON null 都当空串（与 `:data` / `:core` 里那几处 `optString` 同一条宽缺口径）。 */
    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }

    /** 与 [text] 同口径，但「本来就没有」返回 null（`completeTime` / `errorMessage` 是可空列）。 */
    private fun JsonObject.nullableText(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    companion object {
        /** 端内 store 名的基础部分；完整端内名见 [storeName]（S7b：带账号后缀）。 */
        const val STORE_NAME = "download_tasks"

        /** 端内名（`download_tasks<账号后缀>`）—— 测试清场与「落盘文件在哪儿」共用这一处口径。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            accountScopedStoreName(STORE_NAME, accountId)

        private const val KEY_ROWS = "rows"
    }
}

/** 与 Android 那条 SQL 的 `ORDER BY createTime DESC` 同口径。 */
private fun List<DownloadTask>.byCreateTimeDesc(): List<DownloadTask> = sortedByDescending { it.createTime }
