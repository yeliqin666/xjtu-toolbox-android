package com.xjtu.toolbox.desktop.jiaocai1

import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * 书架的桌面实现（`:core` 的 [Jiaocai1ShelfStore] 在桌面侧的填法）。
 *
 * ## 为什么不是 Room
 *
 * 搬迁前书架是 Room（`androidx.room` + `Context`），桌面端没有那一套。这里用**一份 JSON 落盘**：
 * 把行数组序列化成一个字符串，存进 `:data` 的密文键值缝 [com.xjtu.toolbox.platform.secureKeyValueStore]
 * —— 它在本端就是 `<数据根>/jiaocai1_shelf.properties`（权限 0600，与 cookie / 凭据同一处），
 * 写入是同步的，所以「离开阅读器时那一发」不需要额外的协程。
 *
 * 行数很少（书架 = 我读过的那几本），所以每次写整份重写；JSON 手写而不是给模型加
 * `@Serializable`：`:core` 的 [Jiaocai1ShelfRecord] 是界面模型，不为了某一端的存储去改它的形状。
 *
 * ## 与 Android 的差异（如实）
 *
 * | | Android | 桌面 |
 * |---|---|---|
 * | 存储 | Room 表 `jiaocai1_shelf`（升级不丢） | 一份 0600 的 Properties 文件 |
 * | 观察 | DAO 的 `Flow`（数据库变化推送） | 进程内 `MutableStateFlow`（本进程写完就推） |
 *
 * 也就是说：桌面上**别的进程**改这份文件不会被感知（单实例应用的正常取舍），
 * 而 Android 那条 Room 路径一个字都没动。
 */
class DesktopJiaocai1ShelfStore(
    /** 存储；默认是那份 0600 的落盘文件（测试可传内存版做隔离）。 */
    private val store: KeyValueStore = com.xjtu.toolbox.platform.secureKeyValueStore(FILE_NAME),
) : Jiaocai1ShelfStore {

    private val rows = MutableStateFlow(read())

    override fun observeAll(): Flow<List<Jiaocai1ShelfRecord>> = rows

    override suspend fun get(ssno: String): Jiaocai1ShelfRecord? = rows.value.firstOrNull { it.ssno == ssno }

    override suspend fun upsert(row: Jiaocai1ShelfRecord) {
        val next = rows.value.filterNot { it.ssno == row.ssno } + row
        write(next)
    }

    override suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int) {
        val next = rows.value.map { row ->
            if (row.ssno != ssno) row
            else row.copy(lastReadIndex = index, lastReadAt = at, totalPages = totalPages)
        }
        write(next)
    }

    override suspend fun delete(ssno: String) {
        write(rows.value.filterNot { it.ssno == ssno })
    }

    /** 同步落盘（没有页面作用域要躲，见类 KDoc 的差异表）。 */
    override fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int) {
        val next = rows.value.map { row ->
            if (row.ssno != ssno) row
            else row.copy(lastReadIndex = index, lastReadAt = at, totalPages = totalPages)
        }
        write(next)
    }

    private fun write(next: List<Jiaocai1ShelfRecord>) {
        // 排序口径与 Android 那条 SQL 一致：最近读的在前
        val sorted = next.sortedByDescending { it.lastReadAt }
        store.putString(KEY_ROWS, encode(sorted))
        rows.value = sorted
    }

    private fun read(): List<Jiaocai1ShelfRecord> {
        val raw = store.getString(KEY_ROWS) ?: return emptyList()
        val array = runCatching { AppJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val ssno = o.text("ssno")
            if (ssno.isEmpty()) return@mapNotNull null
            Jiaocai1ShelfRecord(
                ssno = ssno,
                title = o.text("title"),
                author = o.text("author"),
                coverUrl = o.text("coverUrl"),
                totalPages = o.text("totalPages").toIntOrNull() ?: 0,
                lastReadIndex = o.text("lastReadIndex").toIntOrNull() ?: 0,
                lastReadAt = o.text("lastReadAt").toLongOrNull() ?: 0L,
                addedAt = o.text("addedAt").toLongOrNull() ?: 0L,
            )
        }
    }

    private fun encode(items: List<Jiaocai1ShelfRecord>): String = buildJsonArray {
        items.forEach { row ->
            add(
                buildJsonObject {
                    put("ssno", row.ssno)
                    put("title", row.title)
                    put("author", row.author)
                    put("coverUrl", row.coverUrl)
                    put("totalPages", row.totalPages)
                    put("lastReadIndex", row.lastReadIndex)
                    put("lastReadAt", row.lastReadAt)
                    put("addedAt", row.addedAt)
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

    private companion object {
        const val FILE_NAME = "jiaocai1_shelf"
        const val KEY_ROWS = "rows"
    }
}

/**
 * 教材页图的**缓存根目录**（`:data` 的 `Jiaocai1PageLoader` 收到的那个 `File`；
 * 它下面固定再建 `jiaocai1/` 子目录）。
 *
 * 桌面 = XDG 缓存目录（`$XDG_CACHE_HOME` 或 `~/.cache`）下的 `xjtu-toolbox` —— 与 Android 的
 * `context.cacheDir` 同义（「可以随时删」的那一类）。证据运行与测试把
 * [dataRootOverride] 指到临时目录时**跟着它走**，免得假登录时顺手往用户真实缓存里写页图。
 */
internal fun jiaocai1CacheRoot(): File {
    val override = dataRootOverride
    if (override != null) return File(override, "cache")
    val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home") ?: "/tmp", ".cache")
    return File(base, "xjtu-toolbox")
}

/** 测试用的内存存储（桌面端的 `:core` 键值缝在本端就是内存实现）。 */
internal fun inMemoryJiaocai1ShelfStore(): KeyValueStore = keyValueStore("jiaocai1_shelf_test")
