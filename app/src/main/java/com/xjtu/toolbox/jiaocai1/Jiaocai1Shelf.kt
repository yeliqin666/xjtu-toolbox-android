package com.xjtu.toolbox.jiaocai1

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jiaocai1_shelf")
data class Jiaocai1ShelfEntity(
    @PrimaryKey val ssno: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val totalPages: Int,
    val lastReadIndex: Int,
    val lastReadAt: Long,
    val addedAt: Long,
    val pinned: Boolean,
    val cachedPages: Int,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val cropReady: Boolean = false,
)

@Entity(tableName = "jiaocai1_cache_pages", primaryKeys = ["ssno", "fileName"])
data class Jiaocai1CachePageEntity(
    val ssno: String,
    val fileName: String,
    val ready: Boolean,
    val failCount: Int,
    val updatedAt: Long,
)

@Dao
interface Jiaocai1ShelfDao {
    @Query("SELECT * FROM jiaocai1_shelf ORDER BY lastReadAt DESC")
    fun observeAll(): Flow<List<Jiaocai1ShelfEntity>>

    @Query("SELECT * FROM jiaocai1_shelf WHERE ssno = :ssno")
    suspend fun get(ssno: String): Jiaocai1ShelfEntity?

    @Query("SELECT ssno FROM jiaocai1_shelf WHERE pinned = 1")
    suspend fun pinnedSsnos(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: Jiaocai1ShelfEntity)

    @Query(
        """
        UPDATE jiaocai1_shelf
        SET lastReadIndex = :index, lastReadAt = :at, totalPages = :totalPages
        WHERE ssno = :ssno
        """
    )
    suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int)

    @Query(
        """
        UPDATE jiaocai1_shelf
        SET title = :title, author = :author, coverUrl = :coverUrl, totalPages = :totalPages
        WHERE ssno = :ssno
        """
    )
    suspend fun updateMeta(ssno: String, title: String, author: String, coverUrl: String, totalPages: Int)

    @Query("UPDATE jiaocai1_shelf SET cachedPages = :cached, pinned = :pinned WHERE ssno = :ssno")
    suspend fun updateCache(ssno: String, cached: Int, pinned: Boolean)

    @Query(
        """
        UPDATE jiaocai1_shelf
        SET cropLeft = :left, cropTop = :top, cropRight = :right, cropBottom = :bottom, cropReady = 1
        WHERE ssno = :ssno
        """
    )
    suspend fun updateCrop(ssno: String, left: Float, top: Float, right: Float, bottom: Float)

    @Query("DELETE FROM jiaocai1_shelf WHERE ssno = :ssno")
    suspend fun delete(ssno: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(row: Jiaocai1CachePageEntity)

    @Query("SELECT * FROM jiaocai1_cache_pages WHERE ssno = :ssno")
    suspend fun pagesOf(ssno: String): List<Jiaocai1CachePageEntity>

    @Query("SELECT COUNT(*) FROM jiaocai1_cache_pages WHERE ssno = :ssno AND ready = 1")
    suspend fun readyPageCount(ssno: String): Int

    @Query("DELETE FROM jiaocai1_cache_pages WHERE ssno = :ssno")
    suspend fun deletePages(ssno: String)
}
