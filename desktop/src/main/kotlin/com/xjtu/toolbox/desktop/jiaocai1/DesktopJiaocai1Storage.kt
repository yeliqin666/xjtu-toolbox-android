package com.xjtu.toolbox.desktop.jiaocai1

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.desktop.accountScopedStoreName
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

/** 书架的桌面实现（`:core` 的 [Jiaocai1ShelfStore] 在桌面侧的填法）。 */
class DesktopJiaocai1ShelfStore(
    /** 存储；生产装配给的是那份 0600 的落盘文件（测试可传内存版做隔离）。 */
    private val store: KeyValueStore,
    /** 旧名（[STORE_NAME]，无账号后缀）的只读兜底：新名里没有时读它一次。 */
    private val legacyStore: KeyValueStore? = null,
) : Jiaocai1ShelfStore {

    /** 生产装配：端内名 = `jiaocai1_shelf<账号后缀>` + 旧名的只读兜底。 */
    constructor(accountId: String? = AccountContext.activeAccountId) : this(
        store = com.xjtu.toolbox.platform.secureKeyValueStore(storeName(accountId)),
        legacyStore = com.xjtu.toolbox.platform.secureKeyValueStore(STORE_NAME),
    )

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
        val sorted = next.sortedByDescending { it.lastReadAt }
        store.putString(KEY_ROWS, encode(sorted))
        rows.value = sorted
    }

    private fun read(): List<Jiaocai1ShelfRecord> {
        val raw = store.getString(KEY_ROWS) ?: legacyStore?.getString(KEY_ROWS) ?: return emptyList()
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

    /** 缺字段 / JSON null 都当空串。 */
    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }

    companion object {
        /** 端内 store 名的基础部分；完整端内名见 [storeName]。 */
        const val STORE_NAME = "jiaocai1_shelf"

        /** 端内名（`jiaocai1_shelf<账号后缀>`）。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            accountScopedStoreName(STORE_NAME, accountId)

        private const val KEY_ROWS = "rows"
    }
}

/** 教材页图的缓存根目录（`:data` 的 `Jiaocai1PageLoader` 收到的那个 `File`）。 */
internal fun jiaocai1CacheRoot(): File {
    val override = dataRootOverride
    if (override != null) return File(override, "cache")
    val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home") ?: "/tmp", ".cache")
    return File(base, "xjtu-toolbox")
}

/** 测试用的落盘存储（桌面端的 `:core` 键值缝在本端现在也是落盘实现）。 */
internal fun inMemoryJiaocai1ShelfStore(): KeyValueStore = keyValueStore("jiaocai1_shelf_test")
