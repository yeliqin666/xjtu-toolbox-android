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

/**
 * 「我的课表」那一族的只读取数（`docs/api-contract.md` §5.8 那三条只读端点 + §5.9 的
 * `?merged=1` 那一档，2026-10-14 第四批与批①收尾）：调停补课 / 考试 / 教材 / 已合并的课表。
 *
 * 给 `:web` 的教务取数口（`WebScheduleSource`）用 —— `:app` / 桌面走 `:data` 的 `JwxtScheduleApi`
 * （同一个上游、同一份解析），本类只是把服务端已经归一好的形状读回来：
 *
 * | 方法 | 端点 | 行是哪个模型 |
 * |---|---|---|
 * | [changes] | `GET /api/jwxt/changes?term=` | [ScheduleChangeEvent] 十二格（`kind` 是枚举名） |
 * | [examSchedule] | `GET /api/jwxt/exams?term=` | [ExamItem] 六格原样 |
 * | [textbooks] | `GET /api/jwxt/textbooks?term=` | [TextbookItem] 八格 + 那第九格 `hasTextbook`（它**就是** [TextbookItem.hasSubstantiveTextbook]，不是第二个判据） |
 * | [mergedSchedule] | `GET /api/jwxt/schedule?merged=1` | [SourceSchedule]：[CourseItem] 九格 + [ScheduleChangeEvent] 十二格（同一个端点的另一种问法：不带 `merged` 那一档端的是**排课原样**，见下） |
 *
 * ## 四条纪律（都写在契约里，这里只是照着做）
 *
 * - `?term=` 与 `/api/jwxt/schedule` 同一个读法：**空白 = 省略**（服务端拿当前学期），形状不对由服务端
 *   答 400（那条判据 `com.xjtu.toolbox.schedule.isTermCode` 在服务端）⇒ 本端不自己造一个学期码，
 *   也不把一个空白串发出去（`serveData` 本来就会跳过空值参数）；
 * - **不投影的格子不进屏**：调停补课的教师名（上游 `YSKJS` 那个「姓名/工号」）与申请时间从来不在
 *   [ScheduleChangeEvent] 上（§5.2 红线）；教材里那串占位 ISBN（`9780000000000`）由屏那一侧滤
 *   （`CourseLinkSections.realIsbn`），本层原样投影、不写第二份判据；
 * - **教材不收学号**：学号由服务端从会话里取（请求参数里没有那一格）⇒ [textbooks] 干脆不收学号参数
 *   （`:core` 的 `JwxtScheduleSource.textbooks(studentId, term)` 那个参数是 `:data` 的帆软报表要的）。
 * - **`mergedSchedule` 是同一个端点的另一种问法**（契约 §5.9）：`?merged=1` 时服务端端的是 `:data`
 *   的 `JwxtScheduleApi.schedule()` 已经合过调停补课的课表（Android / 桌面端吃的那一张）；
 *   不带它时是**排课原样**（那个形状由 [CampusApi.schedule] 读）。二者都是 [CourseItem]，但周次
 *   那一格的来源不同（`merged` = `:data` 的 `weekBits` 位串；非 `merged` = 屏把 `ZCMC` 文本翻成的位串）。
 *
 * ## 默认形态（campus-api）
 *
 * 这三条在 campus-api 形态里取不到（本端只认 serve 契约里那一种字段面，契约 §7 第 2 条）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，**不返回一份空表** —— 空表会把「形态不对」说成「真没有」）。调用方
 * （`WebScheduleSource`）在那一形态下自己回空表 / 空记录，并在日程页顶部写明那两栏为什么是空的。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusJwxtScheduleApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) {

    /**
     * 调停补课记录（`ScheduleChangeEvent`）：**说明面** —— `/api/jwxt/schedule` 端出去的课表是排课
     * 原样，调课/停课/补课不在那些行里（合并那一层在 `:core` 的 `JwxtChanges`，见 §5.8）。
     */
    suspend fun changes(term: String? = null): List<ScheduleChangeEvent> =
        AppJson.decodeFromJsonElement(fetch(CHANGES_PATH, term, "changes", "加载调停补课"))

    /** 考试安排：[term] 空/空白 = 当前学期；行序 = 上游序（最近的考试在前）。 */
    suspend fun examSchedule(term: String? = null): List<ExamItem> =
        AppJson.decodeFromJsonElement(fetch(EXAMS_PATH, term, "exams", "加载考试安排"))

    /** 本学期教材（教务的帆软报表那一份）：[term] 空/空白 = 当前学期。 */
    suspend fun textbooks(term: String? = null): List<TextbookItem> =
        AppJson.decodeFromJsonElement(fetch(TEXTBOOKS_PATH, term, "books", "加载教材"))

    /**
     * 已合并调停补课的课表（`GET /api/jwxt/schedule?merged=1`，契约 §5.9）：**同一个端点**的另一种问法。
     *
     * 不带 `merged` 的那一档（[CampusApi.schedule] 读的）端的是排课原样；这一档端的是 `:data` 的
     * `JwxtScheduleApi.schedule()` —— 调停补课已经经 `JwxtChanges.apply` 合进去了，Android 与桌面端
     * 课表屏吃的就是这一张表。两个列表一起回来（`courses` + `changes`），不另发一次请求。
     *
     * 只认 serve 形态：campus-api 那一头不认 `merged`（同名路径的字段面不同，契约 §7 第 2 条）。
     */
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
