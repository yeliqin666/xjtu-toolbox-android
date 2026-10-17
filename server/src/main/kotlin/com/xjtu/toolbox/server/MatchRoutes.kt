package com.xjtu.toolbox.server

import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.social.DiningHabit
import com.xjtu.toolbox.social.MatchData
import com.xjtu.toolbox.social.MatchProfile
import com.xjtu.toolbox.util.todayInSystemZone
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.Serializable

/** `/api/match*`。 */
internal fun Route.matchRoutes(session: ServeSession) {
    route(MATCH_SEGMENT) {

        // ── 消费统计（只读；窗口内现取流水，判据全在 :core 的 DiningHabit）────
        get(MATCH_DINING) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val rawTo = call.request.queryParameters[MATCH_PARAM_TO]?.trim().orEmpty()
            val to = if (rawTo.isEmpty()) {
                todayInSystemZone()
            } else {
                runCatching { LocalDate.parse(rawTo) }.getOrNull()
                    ?: return@get call.respondBadRequestMessage(matchDateMessage(MATCH_PARAM_TO))
            }
            val rawFrom = call.request.queryParameters[MATCH_PARAM_FROM]?.trim().orEmpty()
            val from = if (rawFrom.isEmpty()) {
                to.minus(DINING_DEFAULT_DAYS, DateTimeUnit.DAY)
            } else {
                runCatching { LocalDate.parse(rawFrom) }.getOrNull()
                    ?: return@get call.respondBadRequestMessage(matchDateMessage(MATCH_PARAM_FROM))
            }
            if (from > to) return@get call.respondBadRequestMessage("$MATCH_PARAM_FROM 不能晚于 $MATCH_PARAM_TO")

            try {
                val (rows, truncated) = session.cardSource().transactionsWindow(from, to)
                val counts = DiningHabit.hourCounts(rows)
                // 饭点 / 食堂那两维**不在这里重算**：交给 `:core` 的 `MatchProfile.build`
                // 那一行判据（该小时 ≥2 笔才置 1、全 0 时给空串、食堂名去过 `clean()`）
                val profile = MatchProfile.build(
                    MatchData.Local(diningHourCounts = counts, canteens = DiningHabit.canteens(rows)),
                    dims = DINING_DIMS,
                )
                call.respond(
                    ApiEnvelope.ok(
                        MatchDiningData(
                            from = from.toString(),
                            to = to.toString(),
                            scanned = rows.size,
                            truncated = truncated,
                            hourCounts = counts.entries.sortedBy { it.key }
                                .associate { it.key.toString() to it.value },
                            diningHours = profile.diningHours,
                            canteens = profile.canteens.toList(),
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "统计校园卡消费")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/match/dining` 的 `data`。 */
@Serializable
internal data class MatchDiningData(
    /** 统计窗口的起点（`YYYY-MM-DD`；省略 `?from=` 时 = `to` 往前 [DINING_DEFAULT_DAYS] 天）。 */
    val from: String,
    /** 统计窗口的终点（`YYYY-MM-DD`；省略 `?to=` 时 = 服务端系统时区的今天）。 */
    val to: String,
    /** 真正进统计的**笔数**（窗口内的流水全表；不是「食堂消费笔数」）。 */
    val scanned: Int,
    /** true = 到 [MAX_PAGES] 页上限还没拉完 ⇒ [hourCounts] / [canteens] 只覆盖前几页。 */
    val truncated: Boolean,
    /** 小时（`"0".."23"`，**字符串键、升序**）→ 那个小时的食堂消费笔数（只看支出）。 */
    val hourCounts: Map<String, Int>,
    /** 24 位 `0`/`1`：这一小时经常去食堂（该小时 ≥2 笔）才置 1；一个 1 都没有时是空串。 */
    val diningHours: String,
    /** 常去的食堂（≥2 次、按次数降序、最多 5 个）。 */
    val canteens: List<String>,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

/** 窗口内的流水全表（按页拉）→ `(行, 有没有到上限)`。 */
private suspend fun AppCampusCardSource.transactionsWindow(
    from: LocalDate,
    to: LocalDate,
): Pair<List<Transaction>, Boolean> {
    val rows = ArrayList<Transaction>()
    var total = 0
    var page = 1
    while (page <= MAX_PAGES) {
        val (count, pageRows) = transactions(from = from, to = to, page = page, pageSize = DINING_PAGE_SIZE)
        total = count
        rows += pageRows
        if (pageRows.size < DINING_PAGE_SIZE || rows.size >= total) break
        page++
    }
    return rows to (rows.size < total)
}

private fun matchDateMessage(name: String) =
    "$name 需要形如 2026-09-14（省略 = $MATCH_PARAM_TO 用今天、$MATCH_PARAM_FROM 用今天往前 $DINING_DEFAULT_DAYS 天）"

/** 这一端点只开「饭点」与「食堂」两维。 */
private val DINING_DIMS = MatchProfile.Dimensions(
    schedule = false,
    sameCourses = false,
    buildings = false,
    teachers = false,
    pastCourses = false,
    exams = false,
    textbooks = false,
    identity = false,
    diningHours = true,
    canteens = true,
    dietTags = false,
)

// ── 常量 ─────────────────────────────────────────────────────────────

internal const val MATCH_SEGMENT = "match"
internal const val MATCH_DINING = "dining"
internal const val MATCH_PARAM_FROM = "from"
internal const val MATCH_PARAM_TO = "to"

/** 省略 `?from=` 时往前几天（约一个学期的一半：够看出「常不常去」，又不至于拉太多页）。 */
private const val DINING_DEFAULT_DAYS = 90

/** 一页几笔。上游 `personal/turnover` 收 `size`，这里要的是「尽量少打几枪」。 */
private const val DINING_PAGE_SIZE = 200

/** 最多拉几页（超了标 `truncated`）。 */
private const val MAX_PAGES = 5
