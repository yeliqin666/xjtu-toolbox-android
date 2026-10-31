package com.xjtu.toolbox.jiaocai1

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    /**
     * 重新去取一份（书架栏取数失败时那个「重试」按钮）。
     *
     * 默认空实现：本机那一份的 `observeAll()` 是 Room / 文件的**热**流，不存在「要重新拉一次」这回事
     * —— 有这一档的是 serve 侧那一份（浏览器的书架在运行 serve 的机器上，得再打一枪）。
     */
    suspend fun refresh() {}

    /**
     * 书架取数失败时的一句话（成功时发一条空串把上一次的清掉）。**默认空流** = 不会失败。
     *
     * 本机那一份（Room / JVM 文件）几乎不会失败，所以默认不发；serve 那一份用它报
     * 「会话过期 / 网络故障 / 上游 5xx」—— 没有它的话，屏上只会显示「书架还是空的」，
     * 那是**编事实**（库里有几本书，只是这一次没读到）。
     */
    val notices: Flow<String> get() = emptyFlow()
}
