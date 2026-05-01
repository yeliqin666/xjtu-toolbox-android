package com.xjtu.toolbox.schedule

import androidx.room.*

const val AGENDA_NOTE_PREFIX = "[AGENDA]"

fun encodeAgendaNote(note: String): String {
    val trimmed = note.trim()
    return if (trimmed.startsWith(AGENDA_NOTE_PREFIX)) trimmed else "$AGENDA_NOTE_PREFIX$trimmed"
}

fun decodeAgendaNote(note: String): String = note.removePrefix(AGENDA_NOTE_PREFIX).trim()

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
        courseCode = "custom_$id",
        courseType = if (note.startsWith(AGENDA_NOTE_PREFIX)) "日程" else "自定义",
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay
    )
}

/**
 * 将诸如 "第1-4，6-16周"、"1,2,4-10" 等不规则字符串解析为周次列表
 * 参考桌面版 schedule_service.py 设计
 */
fun parseWeeksString(weeksStr: String): List<Int> {
    if (weeksStr.isBlank()) return emptyList()
    var s = weeksStr.trim().replace("第", "").replace("周", "")
    val seps = arrayOf("，", "、", ";", "；", " ")
    for (sep in seps) {
        s = s.replace(sep, ",")
    }
    val parts = s.split(",").filter { it.isNotBlank() }
    val weeks = mutableSetOf<Int>()
    for (p in parts) {
        val part = p.trim()
        if (part.isBlank()) continue
        if ("-" in part) {
            val range = part.split("-", limit = 2)
            val start = range.getOrNull(0)?.toIntOrNull()
            val end = range.getOrNull(1)?.toIntOrNull()
            if (start != null && end != null) {
                val s1 = minOf(start, end)
                val e1 = maxOf(start, end)
                weeks.addAll(s1..e1)
            }
        } else {
            part.toIntOrNull()?.let { weeks.add(it) }
        }
    }
    return weeks.sorted()
}

/**
 * 将周次列表转换为长度为 maxWeeks 的 0/1 位图字符串（e.g. "11110000..."）
 */
fun List<Int>.toWeekBits(maxWeeks: Int = 20): String {
    val chars = CharArray(maxWeeks) { '0' }
    for (w in this) {
        if (w in 1..maxWeeks) {
            chars[w - 1] = '1'
        }
    }
    return String(chars)
}

@Dao
interface CustomCourseDao {
    @Query("SELECT * FROM custom_courses WHERE termCode = :termCode ORDER BY dayOfWeek, startSection, startMinuteOfDay")
    suspend fun getByTerm(termCode: String): List<CustomCourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CustomCourseEntity): Long

    @Update
    suspend fun update(course: CustomCourseEntity)

    @Delete
    suspend fun delete(course: CustomCourseEntity)

    @Query("DELETE FROM custom_courses WHERE termCode = :termCode")
    suspend fun deleteByTerm(termCode: String)

    @Query("SELECT * FROM custom_courses ORDER BY termCode DESC, dayOfWeek, startSection, startMinuteOfDay")
    suspend fun getAll(): List<CustomCourseEntity>

    /**
     * 查询同一学期内、同星期、且时间段有交集的课程（粗略排课冲突检测）
     */
    @Query("""
        SELECT * FROM custom_courses 
        WHERE termCode = :termCode 
          AND dayOfWeek = :dayOfWeek
          AND NOT (endSection < :startSection OR startSection > :endSection)
    """)
    suspend fun getConflicts(termCode: String, dayOfWeek: Int, startSection: Int, endSection: Int): List<CustomCourseEntity>
}
