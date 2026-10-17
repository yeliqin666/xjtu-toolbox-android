package com.xjtu.toolbox.jiaocai1

import android.content.Context
import com.xjtu.toolbox.XjtuApp
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** `:core` 的 [Jiaocai1ShelfStore] 在 Android 侧的填法。 */
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

    override fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int) {
        val app = appContext as? XjtuApp ?: return
        app.applicationScope.launch { dao().updateProgress(ssno, index, at, totalPages) }
    }
}

/** `:core` 的 [Jiaocai1Cache] 在 Android 侧的填法。 */
class AppJiaocai1Cache(context: Context) : Jiaocai1Cache {

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
