package com.xjtu.toolbox.jiaocai1

import kotlinx.coroutines.flow.Flow

/**
 * 书架里的一本书（**含阅读进度**）。
 *
 * 搬迁前它是 `:app` 的 Room 实体 `Jiaocai1ShelfEntity`（`@Entity(tableName = "jiaocai1_shelf")`）——
 * 字段逐个相同、名字逐个相同，只有两件事变了：
 *  - Room 的注解留在了 `:app`（实体与 DAO 还在那儿，**表名与那几个 SQL 一字未改** ⇒ Android 上
 *    已有的书架与进度一个不丢）；
 *  - 多了一个「界面用的模型」的身份：各端把**自己那份存储**映射成它（见 [Jiaocai1ShelfStore]）。
 */
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

/**
 * 书架的**存储端口**（`jiaocai1` 域里唯一需要宿主存储的那一块）。
 *
 * ## 为什么是端口而不是直接搬实现
 *
 * 搬迁前的实现是 Room：`Jiaocai1ShelfDao`（`app/jiaocai1/Jiaocai1Shelf.kt`）+ `AppDatabase`，
 * 两者都扎在 Android（`androidx.room` + `Context`）。而书架这一屏（书架列表、进度条、
 * 「移出」）要能在桌面端复用 ⇒ 按 `docs/desktop-port-plan.md` 的老规矩切端口：
 *  - **Android**（`AppJiaocai1ShelfStore`，`:app`）= 原样包住那个 DAO（SQL 与表名不动）；
 *  - **桌面**（`DesktopJiaocai1ShelfStore`，`:desktop`）= 一份 JSON 落盘文件（同一份数据 root 下）。
 *
 * 方法集与 DAO **逐个对应**（`observeAll` / `get` / `upsert` / `updateProgress` / `delete`），
 * 外加一个 [saveProgressOnLeave] —— 它是原来 `Jiaocai1ReaderViewModel.onCleared` 里那一发
 * 「页面作用域已取消之后仍要打完的进度」。
 */
interface Jiaocai1ShelfStore {

    /** 全部书架条目，按最近阅读时间倒序（原 Room 查询：`ORDER BY lastReadAt DESC`）。 */
    fun observeAll(): Flow<List<Jiaocai1ShelfRecord>>

    suspend fun get(ssno: String): Jiaocai1ShelfRecord?

    /** 插入或整行覆盖（原 `@Insert(onConflict = REPLACE)`）。 */
    suspend fun upsert(row: Jiaocai1ShelfRecord)

    /** 只改进度三列（原 `UPDATE … SET lastReadIndex, lastReadAt, totalPages WHERE ssno`）。 */
    suspend fun updateProgress(ssno: String, index: Int, at: Long, totalPages: Int)

    suspend fun delete(ssno: String)

    /**
     * 离开阅读器时的最后一次进度：**不阻塞**，实现方自带页面之外的作用域打完这一枪
     * （`onCleared` 那一刻 `viewModelScope` 已经取消）。
     *
     * 与 `LibrarySource.restoreCampus` 是同一条规矩：搬迁前它是在 `:app` 里
     * `(context as XjtuApp).applicationScope.launch { dao.updateProgress(...) }` —— 现在那一半
     * 由实现方负责（Android 那份用同一个应用级作用域，桌面那份直接同步落盘）。
     */
    fun saveProgressOnLeave(ssno: String, index: Int, at: Long, totalPages: Int)
}
