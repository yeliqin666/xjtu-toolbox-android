package com.xjtu.toolbox.jiaocai1

import kotlinx.coroutines.flow.Flow

/** 书架里的一本书（含阅读进度）。 */
data class Jiaocai1ShelfRecord(
    val ssno: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val totalPages: Int,
    val lastReadIndex: Int,
    val lastReadAt: Long,
    val addedAt: Long,
)

/** 书架的存储端口（`jiaocai1` 域里唯一需要宿主存储的那一块）。 */
interface Jiaocai1ShelfStore {

    /** 全部书架条目，按最近阅读时间倒序（原 Room 查询：`ORDER BY lastReadAt DESC`）。 */
    fun observeAll(): Flow<List<Jiaocai1ShelfRecord>>

    suspend fun get(ssno: String): Jiaocai1ShelfRecord?

    /** 插入或整行覆盖（原 `@Insert(onConflict = REPLACE)`）。 */
    suspend fun upsert(row: Jiaocai1ShelfRecord)

    /** 只改进度三列（原 `UPDATE … SET lastReadIndex, lastReadAt, totalPages WHERE ssno`）。 */
    suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int)

    suspend fun delete(ssno: String)

    /** 离开阅读器时的最后一次进度：不阻塞，实现方自带页面之外的作用域打完这一枪（`onCleared` 那一刻 `viewModelScope` 已经取消）。 */
    fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int)
}
