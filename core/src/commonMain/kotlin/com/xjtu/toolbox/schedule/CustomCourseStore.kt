package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 「自建日程那张表」这条缝 —— 读写收成一组动作，怎么存由各端自己定。 */
interface CustomCourseStore {

    /** 某账号某学期的全部日程，按「星期、开始节次、分钟级开始」排序。表一变就重发。 */
    fun observeByTerm(accountId: String, termCode: String): Flow<List<CustomCourse>>

    /** 同上的一次性读。 */
    suspend fun getByTerm(accountId: String, termCode: String): List<CustomCourse>

    /** 同一学期内、同星期、且节次有交集的课程。 */
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

/** 键值缝上的那份实现 —— 桌面端用（这一端没有 Room）。 */
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
