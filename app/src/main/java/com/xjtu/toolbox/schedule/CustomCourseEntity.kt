package com.xjtu.toolbox.schedule

import androidx.room.*

/**
 * 自定义课程实体（Room 数据库）—— **表还是这一张**：`custom_courses`，列名一个未改。
 *
 * ## 与 `:core` 的 `CustomCourse` 是什么关系
 *
 * 屏与 ViewModel 进了 `:core`，而 Room 注解是 Android 专属的 ⇒ `:core` 那边放**领域模型**
 * （`CustomCourse`，字段与这里一个不差），这里留实体与两张之间的逐字段映射。
 * 读写那条路由 `RoomCustomCourseStore` 收成 `:core` 的 `CustomCourseStore` 缝
 * （同一个库、同一张表、同一批列名 —— 老用户的自建日程一条都不会丢）。
 *
 * 谁仍直接用这一份：`ScheduleWidgetProvider` / `HomeTab` / `AgentTool` / `AccountManager`
 * 四处（它们本来就在 `:app`），一行未改。
 */
@Entity(tableName = "custom_courses")
data class CustomCourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 所属账号 ID（学号/手机号）。多账号隔离用。 */
    val accountId: String = "",
    val courseName: String,
    val teacher: String = "",
    val location: String = "",
    /** 上课周次位图, e.g. "01111111111111111100"（第1位=第1周） */
    val weekBits: String,
    /** 星期几 1-7 (1=周一) */
    val dayOfWeek: Int,
    /** 开始节次 */
    val startSection: Int,
    /** 结束节次 */
    val endSection: Int,
    /** 分钟级开始时间，单位：距 00:00 的分钟；-1 表示使用节次推导 */
    val startMinuteOfDay: Int = -1,
    /** 分钟级结束时间，单位：距 00:00 的分钟；-1 表示使用节次推导 */
    val endMinuteOfDay: Int = -1,
    /** 所属学期代码, e.g. "2024-2025-2" */
    val termCode: String,
    /** 备注信息 */
    val note: String = "",
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 转为 CourseItem（与 API 课程统一展示） */
    fun toCourseItem(): CourseItem = CourseItem(
        courseName = courseName,
        teacher = teacher,
        location = location,
        weekBits = weekBits,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        courseCode = "$CUSTOM_COURSE_CODE_PREFIX$id",
        courseType = if (note.startsWith(AGENDA_NOTE_PREFIX)) "日程" else "自定义",
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay
    )

    /** 表行 → 领域模型（逐字段，一个不漏）。 */
    fun toCustomCourse(): CustomCourse = CustomCourse(
        id = id,
        accountId = accountId,
        courseName = courseName,
        teacher = teacher,
        location = location,
        weekBits = weekBits,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        termCode = termCode,
        note = note,
        createdAt = createdAt,
    )
}

/** 领域模型 → 表行。新增（`id == 0`）落回 Room 的自增主键。 */
fun CustomCourse.toEntity(): CustomCourseEntity = CustomCourseEntity(
    id = id,
    accountId = accountId,
    courseName = courseName,
    teacher = teacher,
    location = location,
    weekBits = weekBits,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    termCode = termCode,
    note = note,
    createdAt = createdAt,
)

@Dao
interface CustomCourseDao {
    @Query("SELECT * FROM custom_courses WHERE accountId = :accountId AND termCode = :termCode ORDER BY dayOfWeek, startSection, startMinuteOfDay")
    suspend fun getByTerm(accountId: String, termCode: String): List<CustomCourseEntity>

    /** 同 [getByTerm]，但表一变就重发。日程页用它：屁岱在别处写进来的日程也能立刻出现。 */
    @Query("SELECT * FROM custom_courses WHERE accountId = :accountId AND termCode = :termCode ORDER BY dayOfWeek, startSection, startMinuteOfDay")
    fun observeByTerm(accountId: String, termCode: String): kotlinx.coroutines.flow.Flow<List<CustomCourseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CustomCourseEntity): Long

    @Update
    suspend fun update(course: CustomCourseEntity)

    @Delete
    suspend fun delete(course: CustomCourseEntity)

    @Query("SELECT * FROM custom_courses WHERE accountId = :accountId ORDER BY termCode DESC, dayOfWeek, startSection, startMinuteOfDay")
    suspend fun getAll(accountId: String): List<CustomCourseEntity>

    /**
     * 查询同一学期内、同星期、且节次有交集的课程。**只是粗筛**：不看周次，
     * 结果必须再经 [CustomCourseConflicts.conflicts] 精判，否则不同周的同一时段会被误判为冲突。
     */
    @Query("""
        SELECT * FROM custom_courses
        WHERE accountId = :accountId
          AND termCode = :termCode
          AND dayOfWeek = :dayOfWeek
          AND NOT (endSection < :startSection OR startSection > :endSection)
    """)
    suspend fun getConflicts(accountId: String, termCode: String, dayOfWeek: Int, startSection: Int, endSection: Int): List<CustomCourseEntity>

    /** 删除某账号的全部自定义课程（删除账号时调用）。 */
    @Query("DELETE FROM custom_courses WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: String)
}
