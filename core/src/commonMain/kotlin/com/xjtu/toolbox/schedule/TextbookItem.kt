package com.xjtu.toolbox.schedule

import kotlinx.serialization.Serializable

/**
 * 一条教材信息（教务的帆软报表 `frReport2/show.do` 解析结果）。
 *
 * 从 `:app` 的 `schedule/ScheduleApi.kt` 原样切出来；解析本体（jsoup + 报表分页）搬到了
 * `:data`（`JwxtScheduleApi.textbooks`）。与 [ExamItem] 同理：它也是落盘缓存的形状
 * （`ScheduleCache` 的 `textbooks_<学期>` 键），字段名不能动。
 */
@Serializable
data class TextbookItem(
    val courseName: String = "",
    val textbookName: String = "",
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
    val price: String = "",
    val edition: String = "",
    /** 课程号：教材报表里有这一列，有它就不必按课程名去猜是哪门课。老缓存没有，按名字匹配照旧。 */
    val courseCode: String = "",
) {
    /** 是否有实质性教材信息；服务端对无教材课程返回书名「无教材」。 */
    val hasSubstantiveTextbook: Boolean
        get() = textbookName.trim() != "无教材"
                && (textbookName.trim().length >= 2
                    || isbn.any { it.isDigit() }
                    || author.trim().length >= 2)
}
