package com.xjtu.toolbox.server

import com.xjtu.toolbox.jwapp.AppJwappScoreSource
import com.xjtu.toolbox.jwapp.CjcxScore
import com.xjtu.toolbox.jwapp.NoScoreDetailException
import com.xjtu.toolbox.jwapp.ScoreDetail
import com.xjtu.toolbox.jwapp.ScoreDetailItem
import com.xjtu.toolbox.jwapp.TermScore
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/jwapp*`。 */
internal fun Route.jwappRoutes(session: ServeSession) {
    route(JWAPP_SEGMENT) {

        // ── 移动教务的学期成绩（粗分）────────────────────────────────
        get(JWAPP_SCORES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val raw = call.request.queryParameters[JWAPP_PARAM_TERM]
            // 省略 = 全部学期；给了个空白串是「形状不对」（不是「省略」）
            if (raw != null && raw.trim().isEmpty()) {
                return@get call.respondBadRequestMessage("term 要么省略（= 全部学期），要么给学期码，形如 2026-2027-1")
            }
            val term = raw?.trim()
            try {
                val terms = session.jwappScoreSource().getGrade(term)
                call.respond(ApiEnvelope.ok(JwappScoresData(term = term, count = terms.size, terms = terms)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载学期成绩")
            }
        }

        // ── 教务的精确成绩（第二站点）──────────────────────────────────
        get(JWAPP_PRECISE_SCORES) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val scores = session.jwappScoreSource().preciseScores()
                call.respond(ApiEnvelope.ok(JwappPreciseScoresData(count = scores.size, scores = scores.map { it.toDto() })))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载教务精确成绩")
            }
        }

        // ── 一门课的分项成绩（同上一站，第二枪）────────────────────────
        get(JWAPP_SCORE_DETAIL) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val courseId = call.request.queryParameters[JWAPP_PARAM_COURSE_ID]?.trim().orEmpty()
            if (courseId.isEmpty()) {
                return@get call.respondBadRequestMessage(
                    "courseId 必填（取自 /api/jwapp/scores 那一行的 id）",
                )
            }
            try {
                val detail = session.jwappScoreSource().getScoreDetail(courseId)
                call.respond(ApiEnvelope.ok(ScoreDetailData(courseId = courseId, detail = detail.toDto())))
            } catch (e: NoScoreDetailException) {
                // 上游明确说没有分项（null ≠ 字段缺失）⇒ 回 detail:null，不是故障。
                call.respond(ApiEnvelope.ok(ScoreDetailData(courseId = courseId, detail = null)))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载分项成绩")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/jwapp/scores` 的 `data`。`terms` 直接用 `:core` 的 [TermScore]（`@Serializable`）。 */
@Serializable
internal data class JwappScoresData(
    /** 请求里那个学期码；`null` = 全部学期（省略了 `?term=`）。 */
    val term: String?,
    /** 学期条数（= `terms.size`）。 */
    val count: Int,
    val terms: List<TermScore>,
)

/** `GET /api/jwapp/precise-scores` 的 `data`。 */
@Serializable
internal data class JwappPreciseScoresData(val count: Int, val scores: List<JwappPreciseScoreDto>)

/** 教务 `xscjcx.do` 的一行（`:data` 的 [CjcxScore] 的线上形状，字段名 = 上游列名）。 */
@Serializable
internal data class JwappPreciseScoreDto(
    val courseName: String,
    val termCode: String,
    /** 总评成绩。 */
    val zcj: Double,
    /** 学分绩点。 */
    val xfjd: Double,
    /** 学分。 */
    val xf: Double,
    /** 等级成绩类型代码（空串 = 分数制）。 */
    val djcjlxdm: String,
    /** 等级成绩名称（`优` / `良` / `合格`…；分数制时是 `null`）。 */
    val djcjmc: String?,
    val pscj: Double?,
    val pscjxs: String?,
    val qmcj: Double?,
    val qmcjxs: String?,
    /** 课程号。 */
    val kch: String,
    /** 教学班 id。 */
    val jxbid: String,
    val passFlag: Boolean,
    /** 考核性质（必修 / 选修…）。 */
    val examProp: String,
    /** 课程类别代码。 */
    val kclbdm: String,
)

/** `GET /api/jwapp/score-detail` 的 `data`。 */
@Serializable
internal data class ScoreDetailData(
    /** 请求里那个课程 id，原样回（消费方靠它把结果对回某一门课）。 */
    val courseId: String,
    val detail: ScoreDetailDto?,
)

/** [ScoreDetail] 的线上形状：十二格 + 分项四格（字段名一个不改）。 */
@Serializable
internal data class ScoreDetailDto(
    val courseName: String,
    val coursePoint: Double,
    val examType: String,
    val majorFlag: String?,
    val examProp: String,
    val replaceFlag: Boolean,
    val score: String,
    val scoreValue: Double?,
    val gpa: Double,
    val passFlag: Boolean,
    val specificReason: String?,
    val itemList: List<ScoreDetailItemDto>,
)

/** 一个分项：⚠️ `itemPercent` 是比例（`0.3` = 30%） */
@Serializable
internal data class ScoreDetailItemDto(
    val itemName: String,
    val itemPercent: Double,
    val itemScore: String,
    val itemScoreValue: Double?,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun CjcxScore.toDto() = JwappPreciseScoreDto(
    courseName = courseName,
    termCode = termCode,
    zcj = zcj,
    xfjd = xfjd,
    xf = xf,
    djcjlxdm = djcjlxdm,
    djcjmc = djcjmc,
    pscj = pscj,
    pscjxs = pscjxs,
    qmcj = qmcj,
    qmcjxs = qmcjxs,
    kch = kch,
    jxbid = jxbid,
    passFlag = passFlag,
    examProp = examProp,
    kclbdm = kclbdm,
)

private fun ScoreDetail.toDto() = ScoreDetailDto(
    courseName = courseName,
    coursePoint = coursePoint,
    examType = examType,
    majorFlag = majorFlag,
    examProp = examProp,
    replaceFlag = replaceFlag,
    score = score,
    scoreValue = scoreValue,
    gpa = gpa,
    passFlag = passFlag,
    specificReason = specificReason,
    itemList = itemList.map { it.toDto() },
)

private fun ScoreDetailItem.toDto() = ScoreDetailItemDto(
    itemName = itemName,
    itemPercent = itemPercent,
    itemScore = itemScore,
    itemScoreValue = itemScoreValue,
)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 取数实例：[AppJwappScoreSource] 一个类实现两个端口，本族只需要这一个入口。 */
internal fun ServeSession.jwappScoreSource(): AppJwappScoreSource = AppJwappScoreSource(sessionManager)

internal const val JWAPP_SEGMENT = "jwapp"
internal const val JWAPP_SCORES = "scores"
internal const val JWAPP_PRECISE_SCORES = "precise-scores"
internal const val JWAPP_SCORE_DETAIL = "score-detail"
internal const val JWAPP_PARAM_TERM = "term"
internal const val JWAPP_PARAM_COURSE_ID = "courseId"
