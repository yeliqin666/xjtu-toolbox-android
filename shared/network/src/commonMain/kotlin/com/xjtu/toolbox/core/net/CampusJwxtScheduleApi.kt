package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.schedule.CourseItem
import com.xjtu.toolbox.schedule.ExamItem
import com.xjtu.toolbox.schedule.ScheduleChangeEvent
import com.xjtu.toolbox.schedule.SourceSchedule
import com.xjtu.toolbox.schedule.TextbookItem
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.requireArr
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/** 「我的课表」那一族的只读取数：调停补课 / 考试 / 教材 / 已合并的课表。 */
class CampusJwxtScheduleApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /** 调停补课记录（`ScheduleChangeEvent`）：说明面。 */
    suspend fun changes(term: String? = null): List<ScheduleChangeEvent> =
        AppJson.decodeFromJsonElement(fetch(CHANGES_PATH, term, "changes", "加载调停补课"))

    /** 考试安排：[term] 空/空白 = 当前学期；行序 = 上游序（最近的考试在前）。 */
    suspend fun examSchedule(term: String? = null): List<ExamItem> =
        AppJson.decodeFromJsonElement(fetch(EXAMS_PATH, term, "exams", "加载考试安排"))

    /** 本学期教材（教务的帆软报表那一份）：[term] 空/空白 = 当前学期。 */
    suspend fun textbooks(term: String? = null): List<TextbookItem> =
        AppJson.decodeFromJsonElement(fetch(TEXTBOOKS_PATH, term, "books", "加载教材"))

    /** 已合并调停补课的课表（`GET /api/jwxt/schedule?merged=1`，契约 §5.9）：同一个端点的另一种问法。 */
    suspend fun mergedSchedule(term: String? = null): SourceSchedule {
        requireServe()
        val code = term?.trim().orEmpty()
        val data = client.serveData(
            "加载课表（已合并调停补课）",
            baseUrl,
            SCHEDULE_PATH,
            listOfNotNull(
                code.takeIf { it.isNotEmpty() }?.let { TERM_PARAM to it },
                MERGED_PARAM to MERGED_VALUE,
            ),
        )
        val courses: List<CourseItem> = AppJson.decodeFromJsonElement(data.requireArr("courses"))
        val changes: List<ScheduleChangeEvent> = AppJson.decodeFromJsonElement(data.requireArr("changes"))
        return SourceSchedule(courses = courses, changes = changes)
    }

    /** 三发取数共用的那一发：`?term=` 空白就整个不发（= 省略），行数组的键名各自不同。 */
    private suspend fun fetch(path: String, term: String?, key: String, what: String): JsonArray {
        requireServe()
        val code = term?.trim().orEmpty()
        val data = client.serveData(
            what,
            baseUrl,
            path,
            listOfNotNull(code.takeIf { it.isNotEmpty() }?.let { TERM_PARAM to it }),
        )
        return data.requireArr(key)
    }

    private fun requireServe() {
        if (mode != ApiMode.SERVE) {
            serveOnly("我的课表取数", "$SCHEDULE_PATH?merged=1 + $CHANGES_PATH + $EXAMS_PATH + $TEXTBOOKS_PATH")
        }
    }

    private companion object {
        const val SCHEDULE_PATH = "/api/jwxt/schedule"
        const val CHANGES_PATH = "/api/jwxt/changes"
        const val EXAMS_PATH = "/api/jwxt/exams"
        const val TEXTBOOKS_PATH = "/api/jwxt/textbooks"
        const val TERM_PARAM = "term"

        /** `?merged=` 的两格（`/api/jwxt/schedule` 那一条）；值只有 `1` 算数（见 §5.9）。 */
        const val MERGED_PARAM = "merged"
        const val MERGED_VALUE = "1"
    }
}
