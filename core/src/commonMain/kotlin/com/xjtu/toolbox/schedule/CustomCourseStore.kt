package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 「自建日程那张表」这条缝 —— 读写收成一组动作，怎么存由各端自己定。
 *
 * ## 为什么是缝
 *
 * 那张表是 `:app` 的 Room 表（`custom_courses`，10 个消费方：小组件、首页、通知、
 * 删除账号、屁岱工具……）。屏与 ViewModel 进 `:core` 之后不能再认 `AppDatabase` 与
 * `CustomCourseEntity`（Room 注解是 Android 专属的），但**数据必须还是同一张表里的同一批行**：
 * 用户在旧版本里加的日程，升级后一条都不能丢。
 *
 * 所以按「能力」切这一刀：
 *  - `:app` —— `RoomCustomCourseStore` 一行行转发给原来的 `CustomCourseDao`（同一个库、同一张表、
 *    同一批列名，老数据逐字节照旧读得出来）；
 *  - `:desktop` —— [KeyValueCustomCourseStore]（`:core` 自带，落在一份键值文件里）。
 *
 * ## 语义债
 *
 * 每一个方法的名字、过滤条件、排序与搬迁前 `CustomCourseDao` 上那一条**逐字相同** —— 包括
 * [getConflicts] 那句「只按星期与节次粗筛，周次要再由 `CustomCourseConflicts` 精判」
 * （SQL 里就是 `NOT (endSection < :startSection OR startSection > :endSection)`）。
 */
interface CustomCourseStore {

    /** 某账号某学期的全部日程，按「星期、开始节次、分钟级开始」排序。表一变就重发。 */
    fun observeByTerm(accountId: String, termCode: String): Flow<List<CustomCourse>>

    /** 同上的一次性读。 */
    suspend fun getByTerm(accountId: String, termCode: String): List<CustomCourse>

    /**
     * 同一学期内、同星期、且节次有交集的课程。**只是粗筛**：不看周次，
     * 结果必须再经 [CustomCourseConflicts.conflicts] 精判，否则不同周的同一时段会被误判为冲突。
     */
    suspend fun getConflicts(
        accountId: String,
        termCode: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
    ): List<CustomCourse>

    /** 新增，返回落库后的自增主键。 */
    suspend fun insert(course: CustomCourse): Long

    suspend fun update(course: CustomCourse)

    suspend fun delete(course: CustomCourse)

    /** 某账号的全部日程（含历史学期），新的学期在前。 */
    suspend fun getAll(accountId: String): List<CustomCourse>

    /** 删除某账号的全部自定义课程（删除账号时调用）。 */
    suspend fun deleteByAccount(accountId: String)
}

/**
 * 键值缝上的那份实现 —— 桌面端用（这一端没有 Room）。
 *
 * 数据形状与 Room 那张表对齐：**一份清单装下所有账号的行**（Room 里也是一张表，
 * 按 `accountId` 过滤），自增主键就是清单里 `max(id) + 1`。所以
 * 「切账号后看见上一个人的日程」这类偏差不会出现，`id` 也不会在两端重号。
 *
 * 键名 `custom_courses`：桌面端把它落在 0600 的键值文件里（`:data` 的 `secureKeyValueStore`），
 * Android 一份都不用存（那边是 Room）。
 */
class KeyValueCustomCourseStore(private val store: KeyValueStore) : CustomCourseStore {

    /** 写一次就 +1：`observeByTerm` 靠它把新表推出去（键值缝本身没有监听器）。 */
    private val version = MutableStateFlow(0L)

    override fun observeByTerm(accountId: String, termCode: String): Flow<List<CustomCourse>> =
        version.map { byTerm(accountId, termCode) }.distinctUntilChanged()

    override suspend fun getByTerm(accountId: String, termCode: String): List<CustomCourse> =
        byTerm(accountId, termCode)

    override suspend fun getConflicts(
        accountId: String,
        termCode: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
    ): List<CustomCourse> = all()
        .filter {
            it.accountId == accountId && it.termCode == termCode && it.dayOfWeek == dayOfWeek &&
                !(it.endSection < startSection || it.startSection > endSection)
        }
        .sortedBy { it.startMinuteOfDay }

    override suspend fun insert(course: CustomCourse): Long {
        val rows = all()
        val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
        write(rows + course.copy(id = id))
        return id
    }

    override suspend fun update(course: CustomCourse) {
        write(all().map { if (it.id == course.id) course else it })
    }

    override suspend fun delete(course: CustomCourse) {
        write(all().filterNot { it.id == course.id })
    }

    override suspend fun getAll(accountId: String): List<CustomCourse> =
        all().filter { it.accountId == accountId }
            .sortedWith(compareByDescending<CustomCourse> { it.termCode }.thenBy { it.dayOfWeek }
                .thenBy { it.startSection }.thenBy { it.startMinuteOfDay })

    override suspend fun deleteByAccount(accountId: String) {
        write(all().filterNot { it.accountId == accountId })
    }

    private fun byTerm(accountId: String, termCode: String): List<CustomCourse> =
        all().filter { it.accountId == accountId && it.termCode == termCode }
            .sortedWith(compareBy<CustomCourse> { it.dayOfWeek }.thenBy { it.startSection }
                .thenBy { it.startMinuteOfDay })

    private fun all(): List<CustomCourse> =
        store.getString(KEY)?.let { runCatching { AppJson.decodeFromString<List<CustomCourse>>(it) }.getOrNull() }
            .orEmpty()

    private fun write(rows: List<CustomCourse>) {
        store.putString(KEY, AppJson.encodeToString(rows))
        version.value++
    }

    companion object {
        /** 落盘键。桌面端这份文件里只有这一个键。 */
        const val KEY = "custom_courses"
    }
}
