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

/**
 * `/api/jwapp*` —— 移动教务成绩屏的**三个只读端点**（`docs/api-contract.md` §5.6 与 §5.8），走 `:data` 的
 * [AppJwappScoreSource]（它一个类实现两个端口，取数口径一行不重做）。
 *
 * ⚠️ **这一族是「两个站点」**（不是笔误）：屏上的成绩是两份上游拼起来的 ——
 *
 * | 端点 | 端口方法 | 站点 |
 * |---|---|---|
 * | `GET /api/jwapp/scores` | `JwappScoreSource.getGrade(termCode)` | `jwapp`（移动教务） |
 * | `GET /api/jwapp/precise-scores` | `JwxtScoreSupplement.preciseScores()` | **`jwxt`（教务）** —— `CjcxApi` 的 `xscjcx.do` |
 * | `GET /api/jwapp/score-detail` | `JwappScoreSource.getScoreDetail(courseId)` | `jwapp`（同上一站，第二枪） |
 *
 * 分成两条路径而不是一条合并的：`:core` 的 `JwappScoreViewModel` 也是**分别取、在屏上按
 * 「学期 + 课程名」替换**的（那份归一化 `normalizeCjcxCourseName` 在 `:core`、有测试钉着），
 * 服务端合并等于把同一件事做第二遍、还多一个「哪一份说了算」的口径。消费方按 VM 的老规矩合。
 *
 * ## 端了什么、刻意不端什么
 *
 * | 端口方法 | 本轮端不端 |
 * |---|---|
 * | `getGrade` / `preciseScores` | **端**（两个端点） |
 * | `getScoreDetail`（一门课的分项成绩） | **端**（`?courseId=`，2026-10-14 第四批补上，见 §5.8）：屏上「点开一门课看分项」那一格就是它。⚠️ 契约 §5.6 里那一行当年写的是「不端」（如实缺口），已随本批改写成「§5.8 已端」 |
 * | `unevaluatedCourseNames` | 不端：那是**评教**那一侧的名单（`:server` 里 `/api/jwxt/evaluations` 已经端了评教本体的另一半） |
 * | `reportedGrades(studentId)` | **不端**：它**要学号**（`ScoreReportApi` 按学号取报表）⇒ §5.2 红线；而且那三行是「未评教课程拿不到成绩」的补丁，属评教那一族的边角 |
 * | `reAuthenticate` / `JwappScoreCache.read` / `write` | 不端：前者是宿主凭据的写动作，后两者是宿主的落盘缓存（serve 这一层不替浏览器缓存） |
 *
 * ⚠️ 与 `/api/jwxt/grades`（§5.2，`ScoreReportSource` 的**成绩报表**）不是同一份数据：
 * 那条是教务报表的投影，这一族是「移动教务粗分 + 教务精确成绩」两发 —— 三者在 `:app`/桌面端
 * 本来就都在用，谁都不是谁的替代。**serve 模式下成绩屏要的是这一族**（`JwappScoreViewModel`
 * 只认这两个端口），`/api/jwxt/grades` 是报表那一屏的。
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `ScoreModels`）
 *
 * - **scores**：`data.terms` 直接复用 `:core` 的 [TermScore]（`@Serializable`，字段名一个不改）：
 *   `{termCode, termName, scoreList:[{id, termCode, courseName, score, scoreValue, passFlag,
 *   specificReason, coursePoint, examType, majorFlag, examProp, replaceFlag, gpa, source,
 *   courseCategory, courseCode, courseGroup}]}`。`source` = `JWAPP` / `REPORT`，
 *   `courseGroup` = `GEN_CORE` / `GEN_ELECTIVE`（`null` = 不属于通识课）——
 *   枚举的线上形状是**枚举名**，`:core` 的屏直接读它们。
 *   ⚠️ `score` 是**字符串**（上游原文：可能是「优秀」「通过」这种等级制文本），
 *   `scoreValue` 才是解析出来的数字（解析不出来是 `null`）—— 与 `:core` 的 `ScoreItem` 一致；
 * - **precise-scores**：`:data` 的 [CjcxScore] 原样投影（字段名就是教务 `xscjcx.do` 的列名，
 *   本层不翻译成「好看的名字」—— §7.3：同一个字段名不在这两处各起一个）。
 *   缩写对照写在契约 §5.6 里（`zcj` = 总评成绩、`xfjd` = 学分绩点、`xf` = 学分、
 *   `pscj` / `pscjxs` = 平时成绩（值 / 显示的文本）、`qmcj` / `qmcjxs` = 期末成绩、
 *   `djcjlxdm` / `djcjmc` = 等级成绩类型代码 / 名称、`kch` = 课程号、`jxbid` = 教学班 id、
 *   `kclbdm` = 课程类别代码、`examProp` = 考核性质、`passFlag` = 是否通过）。
 *   它**没有学期名**（只有 `termCode`），要名字就用 `scores` 那一发的 `termName` 对上；
 *   它**没有学号/姓名**（教务那一枪是按登录会话取「我的成绩」）。
 *
 * ## 请求参数与错误码
 *
 * - `?term=`（只有 `scores` 收）：省略 = **全部学期**（`:data` 的 `getGrade(null)`，
 *   请求体里是 `{"termCode":"*"}`）；给了但是空白 ⇒ 400；**不做形状校验**
 *   （学期码的合法取值就是响应里的 `termCode`，本层不另立一套判据）；
 * - `precise-scores` 不收参数；
 * - `?courseId=`（只有 `score-detail` 收）：**必填**（取自 `scores` 里那一行的 `id`），缺 / 空白 ⇒ 400；
 *   这门课**没有分项**时 `data.detail` 是 `null`（§4：上游明确说没有）而**不是** 502/404 —— 消费方
 *   按 `NoScoreDetailException` 那条老规矩拿**已经拿到的那一行**自己拼一份空明细
 *   （`:core` 的 `ScoreItem.asEmptyDetail()`，`JwappScoreViewModel.loadDetail` 就是这么接的）；
 * - 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句，含会话失效那档 ——
 *   `:data` 抛的 `SessionExpiredException` 在本层归到 502：同样的请求稍后重试可能就成）。
 *   ⚠️ 两个端点的会话是**两个站点**：`jwapp` 没会话时 `scores` 502；`jwxt` 没会话时
 *   `precise-scores` 502 —— 而 `:core` 的 VM 对后者是**容忍**的（拿不到就只出 jwapp 那一份），
 *   消费方照那条规矩处理即可。
 *
 * @param session 会话装配；两个站点（`jwapp` / `jwxt`）都由 [AppJwappScoreSource] 自己从
 *   `sessionManager` 取（与 `:data` 的用法逐字相同），本层不另解析站点。
 */
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
                // 「这门课没有分项」= 上游**明确说没有**（§4：null ≠ 字段缺失），不是故障 ——
                // 如实回 detail:null（不编一份空明细、也不把它报成 502/404）。
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

/**
 * 教务 `xscjcx.do` 的一行（`:data` 的 [CjcxScore] 的线上形状，字段名 = 上游列名）。
 * 缩写对照见文件头 KDoc 与契约 §5.6。
 */
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

/**
 * `GET /api/jwapp/score-detail` 的 `data`。
 *
 * `detail` 可空：`null` = 上游**明确说**这门课没有分项成绩（§4），**不是**字段缺失、也不是失败 ——
 * 消费方拿已经拿到的那一行自己拼一份空明细（`:core` 的 `ScoreItem.asEmptyDetail()`）。
 */
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

/**
 * 一个分项：⚠️ `itemPercent` 是**比例**（`0.3` = 30%）—— `:data` 的 `JwappApi.getDetail`
 * 已经把上游那个 `"30%"` 字符串除成比例了（本层不除第二次），`itemScoreValue` 是
 * `itemScore` 解析出来的数字（解析不出来是 `null`）。
 */
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

/**
 * 取数实例：`:data` 的 [AppJwappScoreSource] 一个类实现两个端口（`jwapp` 与 `jwxt` 两个站点
 * 都由它自己从 `sessionManager` 取），所以本族只需要这一个入口。
 */
internal fun ServeSession.jwappScoreSource(): AppJwappScoreSource = AppJwappScoreSource(sessionManager)

internal const val JWAPP_SEGMENT = "jwapp"
internal const val JWAPP_SCORES = "scores"
internal const val JWAPP_PRECISE_SCORES = "precise-scores"
internal const val JWAPP_SCORE_DETAIL = "score-detail"
internal const val JWAPP_PARAM_TERM = "term"
internal const val JWAPP_PARAM_COURSE_ID = "courseId"
