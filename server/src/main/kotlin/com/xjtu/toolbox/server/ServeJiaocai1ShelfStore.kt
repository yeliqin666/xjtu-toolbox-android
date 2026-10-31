package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfRecord
import com.xjtu.toolbox.jiaocai1.Jiaocai1ShelfStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.util.AppJson
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
 * serve 侧的教材书架（`docs/api-contract.md` §5.14）。
 *
 * ## 为什么书架在 serve 侧
 *
 * 会议 #9（下载队列）已经拍过同一条口径：**serve 进程装真的那一层，浏览器只当界面**。
 * 书架按同一条走 —— 与桌面的 `DesktopJiaocai1ShelfStore`（数据根下的一个 JVM 文件）同性质，
 * 与 Android 的 Room 表（按账号分）同性质：**跟着账号，换浏览器还在**。
 * 代价（如实写在契约里）：web 的档案导出是同步的、读不到 serve 侧的东西 ⇒
 * `files/jiaocai/jiaocai1_shelf.json` 这一族在 web 的档案里仍不落地（那一格继续记缺口）。
 *
 * ## 为什么用自己的店名
 *
 * 桌面的那份是 `jiaocai1_shelf<账号后缀>.properties`，一行 `rows` 存整份 JSON 数组。
 * serve 若读写同一个文件，两边的手工编码一旦有一处不一致，就会「对方写的行读不出来 ⇒
 * 下一次整份回写把它抹掉」——**静默丢用户的书架**（`ServeDownloadTaskStore` 的 KDoc 里
 * 对同一件事写过一遍）。所以这一端用自己的店名（`serve_jiaocai1_shelf<账号后缀>`），
 * 格式只由本文件定义；代价同样是「两端的书架不共享」。
 *
 * ## 形状
 *
 * 键 `rows` 存一个 JSON 数组，字段名与 [Jiaocai1ShelfRecord] 的属性**逐字同名**
 * （与桌面那一份的编码也是同一批键名，便于将来真做跨端合并时对齐）。
 */
internal class ServeJiaocai1ShelfStore(
    private val store: KeyValueStore = secureKeyValueStore(storeName(AccountContext.activeAccountId)),
) : Jiaocai1ShelfStore {

    private val lock = Any()

    /** 进程内的当前快照（构造时读一次；每次写整份落盘）——与桌面那一份同一条做法。 */
    private val rows = MutableStateFlow(read())

    override fun observeAll(): Flow<List<Jiaocai1ShelfRecord>> = rows

    override suspend fun get(ssno: String): Jiaocai1ShelfRecord? =
        synchronized(lock) { rows.value.firstOrNull { it.ssno == ssno } }

    override suspend fun upsert(row: Jiaocai1ShelfRecord) {
        synchronized(lock) {
            write(rows.value.filterNot { it.ssno == row.ssno } + row)
        }
    }

    override suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int) {
        synchronized(lock) {
            write(progressed(rows.value, ssno, index, at, totalPages))
        }
    }

    override suspend fun delete(ssno: String) {
        synchronized(lock) {
            write(rows.value.filterNot { it.ssno == ssno })
        }
    }

    /**
     * 离开阅读器时的最后一枪（`:core` 那一侧不阻塞）。
     *
     * ⚠️ 接口把它写成**非 suspend**（原实现自带页面之外的作用域）；这里就是同步落盘 ——
     * 与本类的其余写方法同一条路径，不会因为调用方那个作用域已经取消而丢这一格。
     */
    override fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int) {
        synchronized(lock) {
            write(progressed(rows.value, ssno, index, at, totalPages))
        }
    }

    private fun progressed(
        current: List<Jiaocai1ShelfRecord>,
        ssno: String,
        index: Int,
        at: Long,
        totalPages: Int,
    ): List<Jiaocai1ShelfRecord> = current.map { row ->
        if (row.ssno != ssno) row
        else row.copy(lastReadIndex = index, lastReadAt = at, totalPages = totalPages)
    }

    private fun write(next: List<Jiaocai1ShelfRecord>) {
        val sorted = next.sortedByDescending { it.lastReadAt }
        runCatching { store.putString(KEY_ROWS, encode(sorted).toString()) }
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

    private fun encode(items: List<Jiaocai1ShelfRecord>): JsonArray = buildJsonArray {
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
    }

    /** 缺字段 / JSON null 都当空串。 */
    private fun JsonObject.text(key: String): String {
        val v = this[key] ?: return ""
        if (v is JsonNull) return ""
        return (v as? JsonPrimitive)?.content.orEmpty()
    }

    companion object {
        /** 端内店名的基础部分（账号后缀见 [storeName]）。 */
        const val STORE_NAME = "serve_jiaocai1_shelf"

        /** 那一行的键（整份 JSON 数组）。 */
        const val KEY_ROWS = "rows"

        /** 端内名 = `serve_jiaocai1_shelf<账号后缀>`。 */
        fun storeName(accountId: String? = AccountContext.activeAccountId): String =
            STORE_NAME + AccountContext.suffixFor(accountId)
    }
}
