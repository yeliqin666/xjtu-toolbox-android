package com.xjtu.toolbox.schedule

import androidx.room.*

/**
 * 自定义课程实体（Room 数据库）
 * 用于存储用户手动添加的实验课、临时排课等
 */
@Entity(tableName = "custom_courses")
data class CustomCourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
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
        courseCode = "custom_$id",
        courseType = "自定义"
    )
}

@Dao
interface CustomCourseDao {
    @Query("SELECT * FROM custom_courses WHERE termCode = :termCode ORDER BY dayOfWeek, startSection")
    suspend fun getByTerm(termCode: String): List<CustomCourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CustomCourseEntity): Long

    @Update
    suspend fun update(course: CustomCourseEntity)

    @Delete
    suspend fun delete(course: CustomCourseEntity)

    @Query("DELETE FROM custom_courses WHERE termCode = :termCode")
    suspend fun deleteByTerm(termCode: String)

    @Query("SELECT * FROM custom_courses ORDER BY termCode DESC, dayOfWeek, startSection")
    suspend fun getAll(): List<CustomCourseEntity>
}
