package com.xjtu.toolbox.jiaocai1

import android.content.Context
import com.xjtu.toolbox.XjtuApp
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * `:core` 的 [Jiaocai1ShelfStore] 在 Android 侧的填法：**原样包住搬迁前那个 Room DAO**
 * （`AppDatabase.jiaocai1ShelfDao()` / `Jiaocai1ShelfDao` / `Jiaocai1ShelfEntity`）。
 *
 * ## 为什么实体与 DAO 留在 `:app`，而不是跟着搬
 *
 * 它们是 Room（`androidx.room` + `Context`）：`:core` 编不到 wasm、`:data` 里也没有 Room
 * 的公共面。而**表名与那几条 SQL 一字未改**（`jiaocai1_shelf`、`ORDER BY lastReadAt DESC`、
 * 「只改进度三列」那条 UPDATE），所以 Android 上已有的书架与阅读进度一个不丢 —— 这正是
 * 「切端口而不是搬实现」那条规矩的报偿（见 `Jiaocai1ShelfStore` 的 KDoc）。
 *
 * 逐方法的对应关系：
 * | 端口 | 原来 ViewModel 里那一句 |
 * |---|---|
 * | [observeAll] | `dao.observeAll()`（原样，只多一层 `Jiaocai1ShelfRecord` 映射） |
 * | [get] | `dao.get(ssno)` |
 * | [upsert] | `dao.upsert(entity)` |
 * | [updateProgress] | `dao.updateProgress(...)` |
 * | [delete] | `dao.delete(ssno)` |
 * | [saveProgressOnLeave] | `(context as XjtuApp).applicationScope.launch { dao.updateProgress(...) }` |
 */
class AppJiaocai1ShelfStore(context: Context) : Jiaocai1ShelfStore {

    private val appContext = context.applicationContext

    private fun dao() = AppDatabase.getInstance(appContext).jiaocai1ShelfDao()

    override fun observeAll(): Flow<List<Jiaocai1ShelfRecord>> =
        dao().observeAll().map { rows -> rows.map { it.toRecord() } }

    override suspend fun get(ssno: String): Jiaocai1ShelfRecord? = dao().get(ssno)?.toRecord()

    override suspend fun upsert(row: Jiaocai1ShelfRecord) {
        dao().upsert(row.toEntity())
    }

    override suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int) {
        dao().updateProgress(ssno, index, at, totalPages)
    }

    override suspend fun delete(ssno: String) {
        dao().delete(ssno)
    }

    /**
     * 与搬迁前 `Jiaocai1ReaderViewModel.onCleared` 里那一发**逐字同构**：`viewModelScope` 那时
     * 已经取消，所以交给应用级作用域（`XjtuApp.applicationScope`）打完，不阻塞主线程。
     */
    override fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int) {
        val app = appContext as? XjtuApp ?: return
        app.applicationScope.launch { dao().updateProgress(ssno, index, at, totalPages) }
    }
}

/**
 * `:core` 的 [Jiaocai1Cache] 在 Android 侧的填法：原样包住 `DataCache`（`cacheDir` 下那个
 * 账号隔离的 JSON 文件缓存）—— **同一个键 `jiaocai1_classify_v2`、同一个 30 天 TTL、
 * 同一个目录**（`data_cache<suffix>/`，会跟着「清除缓存」一起被清）。
 */
class AppJiaocai1Cache(context: Context) : Jiaocai1Cache {

    // 账号在构造时定下（与 DataCache 的口径一致：写回时落在发起时的账号下）。
    private val cache = DataCache(context.applicationContext)

    override fun readCategories(maxAgeMs: Long): List<Jiaocai1Category>? =
        cache.read<List<Jiaocai1Category>>(CATEGORY_CACHE_KEY, maxAgeMs)

    override fun writeCategories(items: List<Jiaocai1Category>) {
        cache.write(CATEGORY_CACHE_KEY, items)
    }
}

private const val CATEGORY_CACHE_KEY = "jiaocai1_classify_v2"

private fun Jiaocai1ShelfEntity.toRecord() = Jiaocai1ShelfRecord(
    ssno = ssno,
    title = title,
    author = author,
    coverUrl = coverUrl,
    totalPages = totalPages,
    lastReadIndex = lastReadIndex,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
)

private fun Jiaocai1ShelfRecord.toEntity() = Jiaocai1ShelfEntity(
    ssno = ssno,
    title = title,
    author = author,
    coverUrl = coverUrl,
    totalPages = totalPages,
    lastReadIndex = lastReadIndex,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
)
