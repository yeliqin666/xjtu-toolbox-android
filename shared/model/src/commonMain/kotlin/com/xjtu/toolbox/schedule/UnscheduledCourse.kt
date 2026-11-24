package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.util.safeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 教务里选上了、但没排时间地点的课（`wdkb/modules/xskcb/xswpkc.do`），如科研训练、部分实验课（#360）。
 * 课表里画不出来，日程页置顶列出，由用户忽略或自己填时间地点。
 */
@Serializable
data class UnscheduledCourse(
    /** 教学班号 `JXBID`；没有时退回课程号 + 课序号。忽略记录按它认。 */
    val id: String,
    val name: String,
    val teacher: String = "",
    /** 「1-16周」这类周次文本（`SKZC`）。 */
    val weeksText: String = "",
    val credit: String = "",
) {
    companion object {
        fun of(row: JsonObject): UnscheduledCourse? {
            val name = row["KCM"].safeString().trim()
            if (name.isEmpty()) return null
            val id = row["JXBID"].safeString().ifEmpty { row["KCH"].safeString() + "-" + row["KXH"].safeString() }
            val credit = row["XF"].safeString().removeSuffix(".0")
            return UnscheduledCourse(id, name, row["SKJS"].safeString().trim(), row["SKZC"].safeString().trim(), credit)
        }
    }
}
