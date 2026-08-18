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
)

@Dao
interface Jiaocai1ShelfDao {
    @Query("SELECT * FROM jiaocai1_shelf ORDER BY lastReadAt DESC")
    fun observeAll(): Flow<List<Jiaocai1ShelfEntity>>

    @Query("SELECT * FROM jiaocai1_shelf WHERE ssno = :ssno")
    suspend fun get(ssno: String): Jiaocai1ShelfEntity?

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

    @Query("DELETE FROM jiaocai1_shelf WHERE ssno = :ssno")
    suspend fun delete(ssno: String)

}
