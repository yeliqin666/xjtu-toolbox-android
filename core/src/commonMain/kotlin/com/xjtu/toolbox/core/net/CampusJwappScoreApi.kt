package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.jwapp.CjcxScore
import com.xjtu.toolbox.jwapp.JwappScoreSource
import com.xjtu.toolbox.jwapp.JwxtScoreSupplement
import com.xjtu.toolbox.jwapp.NoScoreDetailException
import com.xjtu.toolbox.jwapp.ScoreDetail
import com.xjtu.toolbox.jwapp.ScoreDetailItem
import com.xjtu.toolbox.jwapp.TermScore
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDouble
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 移动教务成绩（`jwapp.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，一个类实现两个端口 ——
 * [JwappScoreSource] 与 [JwxtScoreSupplement]（与 `:data` 的 `AppJwappScoreSource` 同一条分工）。
 *
 * | 端点 | 站点 | 本类实现的方法 |
 * |---|---|---|
 * | `GET /api/jwapp/scores?term=` | `jwapp` | [getGrade] |
 * | `GET /api/jwapp/precise-scores` | **`jwxt`（教务）** | [preciseScores] |
 * | `GET /api/jwapp/score-detail?courseId=` | `jwapp` | [getScoreDetail]（2026-10-14 第四批，§5.8） |
 *
 * 契约 §5.6 / §5.8；`:server` 那一侧是 `JwappRoutes.kt`。⚠️ **前两条端点各自打一个站点**（不是笔误）：
 * `scores` 没会话时 502、`precise-scores` 没会话时也 502 —— 而 `:core` 的 `JwappScoreViewModel`
 * 对后者是**容忍**的（`catch` 住就用 jwapp 那一份），所以后者的 502 **不会让整屏失败**。
 * 本类把异常如实抛出去就行（[serveData] 那一层已经把 `message` 拼成一句中文），对齐不做合并：
 * 合并是屏上的事（`JwappScoreViewModel` 按「学期 + 课程名」替换，`normalizeCjcxCourseName` 在 `:core`）。
 *
 * ## 端了什么、刻意不端什么
 *
 * | 端口方法 | 端不端 |
 * |---|---|
 * | [getGrade] / [preciseScores] | **端**（上表前两条端点） |
 * | [getScoreDetail] | **端**（§5.8 的 `score-detail`，2026-10-14 第四批）：一门课的分项成绩 ⇒ serve 形态下 [canFetchScoreDetail] 报 `true`，卡片点了真能展开；「这门课没有分项」= `detail:null` ⇒ 本类抛 [NoScoreDetailException]（与 `:data` 的 `JwappApi.getDetail` 同一个异常）—— 屏拿它走「自己拼一份空明细」那条老分支 |
 * | [unevaluatedCourseNames] | 不端：那是**评教**那一侧的名单 |
 * | [reportedGrades] | **不端**：它**要学号**（§5.2 红线）。本端 [JwappScoreSource] 的 `studentId` 传空串 ⇒ VM 根本不会调它（`if (studentId.isNotEmpty())` 那一行）；真调到了也是这一条抛错（VM 容忍，成绩照旧） |
 * | [reAuthenticate] / 落盘缓存 | 不端：前者是宿主凭据的写动作（本类用端口上的默认实现 = 什么都不做），后者是宿主的缓存（屏那边 `cache = null`） |
 *
 * ## `-1.0` 那个哨兵值：**服务端那一侧就归一了**
 *
 * 上游对「没这一格」的写法是 `-1.0`（不是 `null`）：`pscj` / `qmcj` 那两格在 `:data` 里就是
 * `safeDouble(-999.0).takeIf { it > -100 }`（契约 §5.6 引的就是这一行）⇒ 端出来时已经是 `null`。
 * 本端**不再判一次**（同一个判据起第二个写法就是分叉的开始），也不拿 `0` 顶上。
 * `zcj` / `xfjd` / `xf` 三格在 `CjcxScore` 上是**非空** Double（没有「缺」那一档）⇒ 原样透传 ——
 * 与 `:core` 的 VM 同一条口径（它也是直接吃 `p.zcj` / `p.xfjd`）。
 *
 * ## 默认形态（campus-api）
 *
 * 移动教务在 campus-api 里没有对应端点（`:app` / 桌面走 `:data` 的 okhttp）⇒ 非 serve 分支一律
 * [serveOnly]（抛错，不返回一份空数据）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusJwappScoreApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.6）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : JwappScoreSource, JwxtScoreSupplement {

    /**
     * 本端能不能拿分项成绩：**serve 形态能**（§5.8 端了 `score-detail`），campus-api 形态不能
     * （移动教务在 campus-api 里没有对应端点，那个上游就没有这条）⇒ 屏在那形态下不展开那一格。
     */
    override val canFetchScoreDetail: Boolean get() = mode == ApiMode.SERVE

    override suspend fun getGrade(termCode: String?): List<TermScore> {
        requireServe()
        val data = client.serveData(
            "加载学期成绩",
            baseUrl,
            SCORES_PATH,
            // 省略 `?term=` = 全部学期（服务端请求体里是 `{"termCode":"*"}`）；给了个空白串是「形状不对」
            // （400）⇒ 空串不发出去（`serveData` 自己也会跳过空值参数）
            listOfNotNull(termCode?.takeIf { it.isNotBlank() }?.let { TERM_PARAM to it }),
        )
        val terms = data.arr("terms") ?: return emptyList()
        // `terms` 里那一层就是 `:core` 的 `TermScore`（服务端的 DTO 用的就是它自己，字段名一个不差）
        // ⇒ 直接按模型解码，不手抄一份会漂的字段表
        return AppJson.decodeFromJsonElement<List<TermScore>>(terms)
    }

    override suspend fun preciseScores(): List<CjcxScore> {
        requireServe()
        val data = client.serveData("加载教务精确成绩", baseUrl, PRECISE_SCORES_PATH)
        return data.arr("scores").orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.toCjcxScore()
        }
    }

    /**
     * 分项成绩（§5.8 的 `GET /api/jwapp/score-detail?courseId=`）。
     *
     * 三件事在这里定死：
     *  - `courseId` 取自 [getGrade] 那一行的 `id`，缺了就抛（服务端同样 400）—— 不猜一个课程 id；
     *  - ⚠️ **「这门课没有分项」写成 `detail:null`**（§4：上游明确说没有）⇒ 本类抛
     *    [NoScoreDetailException]（与 `:data` 的 `JwappApi.getDetail` 同一个异常），屏据此拼空明细；
     *  - `itemPercent` 已经是**比例**（`0.3` = 30%）：`:data` 早把上游那个 `"30%"` 除过 100 了，
     *    本端不除第二次（屏上乘 100 再显示）。
     */
    override suspend fun getScoreDetail(courseId: String): ScoreDetail {
        requireServe()
        val id = courseId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("courseId 不能为空（取自 scores 那一行的 id）")
        val data = client.serveData(
            "加载分项成绩",
            baseUrl,
            SCORE_DETAIL_PATH,
            listOf(COURSE_ID_PARAM to id),
        )
        // 字段缺失 = 「形状没对上」；显式 null = 上游说这门课没有分项（两件事，§4）
        val detail = data["detail"] ?: error("serve 分项成绩响应缺少 detail（$id）")
        if (detail.isNull) throw NoScoreDetailException()
        return (detail as? JsonObject)?.toScoreDetail()
            ?: error("serve 分项成绩响应的 detail 不是对象（$id）")
    }

    /** 见类 KDoc：那是评教那一侧的名单（评教本体的另一半在 `/api/jwxt/evaluations`）。 */
    override suspend fun unevaluatedCourseNames(): Set<String> = throw UnsupportedOperationException(
        "serve 契约不端「未评教的课程名单」：那是评教那一侧的边角" +
            "（docs/api-contract.md §5.6）—— 本端的 studentId 传空串，VM 不会调它",
    )

    /** 见类 KDoc：它**要学号**（§5.2 红线）。 */
    override suspend fun reportedGrades(studentId: String): List<ReportedGrade> = throw UnsupportedOperationException(
        "serve 契约不端「按学号取成绩报表」：它要学号（契约 §5.2 红线，例外清单里没有这一族）" +
            "—— 本端的 studentId 传空串，VM 不会调它（docs/api-contract.md §5.6）",
    )

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("移动教务成绩取数", "$SCORES_PATH + $PRECISE_SCORES_PATH + $SCORE_DETAIL_PATH")
    }
}

/**
 * 教务 `xscjcx.do` 的一行：字段名就是**教务的列名**（本端不翻译成「好看的名字」——同一个字段名
 * 不在这两处各起一个）。缩写对照见契约 §5.6 与 `JwappRoutes.kt`。
 *
 * 课名与课程号都空的那一行不列：VM 是按「学期 + 课程名」（退一步按课程号）替换的，两样都没有就
 * 对不上任何一门课 —— 列出来只会多一条永远匹配不上的记录。
 */
private fun JsonObject.toCjcxScore(): CjcxScore? {
    val courseName = this["courseName"].safeString().trim()
    val kch = this["kch"].safeString().trim()
    if (courseName.isEmpty() && kch.isEmpty()) return null
    return CjcxScore(
        courseName = courseName,
        termCode = this["termCode"].safeString(),
        zcj = this["zcj"].safeDouble(),
        xfjd = this["xfjd"].safeDouble(),
        xf = this["xf"].safeDouble(),
        djcjlxdm = this["djcjlxdm"].safeString(),
        djcjmc = this["djcjmc"].safeStringOrNull()?.takeIf { it.isNotBlank() },
        // 见类 KDoc：这两格服务端已经按 `> -100` 归一过（哨兵 `-1.0` 到不了这里）
        pscj = this["pscj"].safeDoubleOrNull(),
        pscjxs = this["pscjxs"].safeStringOrNull()?.takeIf { it.isNotBlank() },
        qmcj = this["qmcj"].safeDoubleOrNull(),
        qmcjxs = this["qmcjxs"].safeStringOrNull()?.takeIf { it.isNotBlank() },
        kch = kch,
        jxbid = this["jxbid"].safeString(),
        passFlag = this["passFlag"].safeBoolean(),
        examProp = this["examProp"].safeString(),
        kclbdm = this["kclbdm"].safeString(),
    )
}

/**
 * `detail` 对象（[ScoreDetail] 的线上形状，字段名一个不改）→ `:core` 的模型。
 *
 * `ScoreDetail` 不在 `:core` 的 `@Serializable` 那一族里（它是屏与 `:data` 共用的模型，
 * 形状不能在服务端那一层被注解钉住）⇒ 逐字段抄下来，与 `toCjcxScore` 同一个写法。
 */
private fun JsonObject.toScoreDetail(): ScoreDetail = ScoreDetail(
    courseName = this["courseName"].safeString(),
    coursePoint = this["coursePoint"].safeDouble(),
    examType = this["examType"].safeString(),
    majorFlag = this["majorFlag"].safeStringOrNull()?.takeIf { it.isNotBlank() },
    examProp = this["examProp"].safeString(),
    replaceFlag = this["replaceFlag"].safeBoolean(),
    score = this["score"].safeString(),
    scoreValue = this["scoreValue"].safeDoubleOrNull(),
    gpa = this["gpa"].safeDouble(),
    passFlag = this["passFlag"].safeBoolean(),
    specificReason = this["specificReason"].safeStringOrNull()?.takeIf { it.isNotBlank() },
    // 分项列表：不是对象的元素、连名字都没有的那几项丢掉（列出来只会多一行空表格）
    itemList = this.arr("itemList").orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item["itemName"].safeString().trim()
        if (name.isEmpty()) return@mapNotNull null
        ScoreDetailItem(
            itemName = name,
            // 见类 KDoc：比例（`0.3` = 30%），服务端已经除过 100，本端不除第二次
            itemPercent = item["itemPercent"].safeDouble(),
            itemScore = item["itemScore"].safeString(),
            itemScoreValue = item["itemScoreValue"].safeDoubleOrNull(),
        )
    },
)

/** 移动教务的学期成绩（粗分）。 */
private const val SCORES_PATH = "/api/jwapp/scores"

/** 教务的精确成绩（第二站点）。 */
private const val PRECISE_SCORES_PATH = "/api/jwapp/precise-scores"

/** 一门课的分项成绩（屏上「点开一门课」那一格；§5.8）。 */
private const val SCORE_DETAIL_PATH = "/api/jwapp/score-detail"

private const val COURSE_ID_PARAM = "courseId"

private const val TERM_PARAM = "term"
