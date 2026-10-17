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

/** 移动教务成绩（`jwapp.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，一个类实现两个端口。 */
class CampusJwappScoreApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.6）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : JwappScoreSource, JwxtScoreSupplement {

    /** 本端能不能拿分项成绩：serve 形态能（§5.8 端了 `score-detail`），campus-api 形态不能（移动教务在 campus-api 里没有对应端点，那个上游就没有这条）⇒屏在那形态下不展开那一格。 */
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

    /** 分项成绩（§5.8 的 `GET /api/jwapp/score-detail?courseId=`）。 */
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

    /** 见类 KDoc：它要学号（§5.2）。 */
    override suspend fun reportedGrades(studentId: String): List<ReportedGrade> = throw UnsupportedOperationException(
        "serve 契约不端「按学号取成绩报表」：它要学号（契约 §5.2 红线，例外清单里没有这一族）" +
            "—— 本端的 studentId 传空串，VM 不会调它（docs/api-contract.md §5.6）",
    )

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("移动教务成绩取数", "$SCORES_PATH + $PRECISE_SCORES_PATH + $SCORE_DETAIL_PATH")
    }
}

/** 教务 `xscjcx.do` 的一行：字段名就是教务的列名（本端不翻译成「好看的名字」——同一个字段名不在这两处各起一个）。 */
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

/** `detail` 对象（[ScoreDetail] 的线上形状，字段名一个不改）→ `:core` 的模型。 */
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
