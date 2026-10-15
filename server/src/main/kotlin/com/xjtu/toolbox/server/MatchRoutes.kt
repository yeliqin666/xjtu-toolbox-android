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

/**
 * `/api/match*` —— 匹配交友（`docs/api-contract.md` §5.7）的**一个只读端点**：
 * 校园卡消费统计（「常在几点吃饭 / 常去哪个食堂」那两维）。
 *
 * | 端点 | 对应 `:data` / `:core` 的方法 |
 * |---|---|
 * | `GET /api/match/dining` | `CampusCardSource.transactions(from,to,page,pageSize)` + `:core` 的 `DiningHabit.hourCounts` / `canteens` |
 *
 * ## 这一族只有这一个端点（另一半拿不到 —— 如实缺口）
 *
 * 匹配交友屏要两样东西：**学工档案**（年级 / 专业 / 学院 / 书院 / 校区 / 班级 / 生源地，
 * 见 `:core` 的 `MatchIdentity`）与**消费习惯**。学工档案那条链是
 * `hello.xjtu.edu.cn` 的 `/yingxin/user/afterLogin`，实现（`HelloApi` / `HelloLogin` /
 * `HelloProfileStore`）**只在 `:app`**：要 `android.content.Context`、要 `hello` 站点的会话、
 * 头像还是 `Bitmap`；`:data` 里**一个 `hello` 的字面量都没有**（`grep` 可证）。
 * 桌面端同一条缺口（`desktop/.../DesktopMatch.kt` 的 `identity()` 如实返回 `null`，
 * 并且把那件事写成了一条 TODO）。
 *
 * ⇒ `/api/match/profile` 这一轮**不端**：服务端拿不到那份档案，编一份出来就是编数据。
 * 要端它得先把 `hello` 的取数与档案模型接进 `:data`（那是另一件事，见上引的桌面 TODO）。
 * 契约 §5.7 把这个缺口与它的判据一起写死了。
 *
 * ## 消费统计为什么能端（只有这一半有源）
 *
 * 「常在几点吃饭 / 常去哪个食堂」是 `:core` 的 [DiningHabit] 从**校园卡流水**里推出来的
 * （两个纯函数：`hourCounts` 只输出「小时 → 笔数」，`canteens` 只输出食堂名，
 * **不含金额、不含商户全称、不含明细** —— 那条 KDoc 把口径写死了）。
 * `:app` 喂给它的是**落盘快照**（`CampusCardCache`，`MatchHost.transactions()`）；
 * serve 这一层没有那份宿主快照，但 `/api/card/transactions` 那条路由证明**同一份**流水
 * 可以现取 ⇒ 这里现取窗口内的流水（`MAX_PAGES` 页封顶）再交给同一对纯函数。
 *
 * ## ⚠️ 红线：学号 / 姓名 / 教师名 / 手机号一个都不投影
 *
 * 流水的 `merchant` / `description`（商户全称与明细）**不进响应**：`DiningHabit` 早就把它们
 * 归并成食堂名了（「一食堂」而不是「一食堂三层清真档口 07 号窗」）。金额也不进 —— 它本来
 * 就是统计的输入而不是输出。响应里只有：小时 → 笔数、食堂名、以及**这笔数**几笔。这一族的
 * 上游里没有教师名，也没有「本人姓名」可投影（校园卡 `CardInfo` 上那几个红线字段在
 * `/api/card/balance` 那边就已经不投影了，这里连卡面都不读）。
 *
 * ## 分享码那一半的如实说明（`:web` 接的时候最要紧的一条）
 *
 * 这一族的终点是**分享码**（`:core` 的 `MatchProfile.encode` / `decode`），而它要的两条平台缝
 * （deflate、Base64）在 **wasmJs 上曾经不存在**（2026-10-15 的 D12 ② 之后已补上，浏览器能自己出码了）
 * ⇒ 当时 `MatchProfile.codecAvailable == false`：
 * **浏览器端生成 / 解析分享码都不可用**（`:web` 那一侧的路由 `schedule_match` 因此整屏接不上，
 * 见契约 §5.7 的「消费方待接」）。本端点端的是**原料**（饭点 / 食堂那两维），
 * 不是码本身 —— 服务端这一侧没有「生成分享码」端点，也不该有：码里含昵称与课表网格，
 * 是**用户自己决定给谁看**的东西（`MatchProfile` 的类 KDoc 就是为这一条写的）。
 *
 * ## 请求参数与错误码
 *
 * - `?from=` / `?to=`：`YYYY-MM-DD`（`to` 省略 = 今天，服务端系统时区；`from` 省略 =
 *   `to` 往前 [DINING_DEFAULT_DAYS] 天）。给了但不成形 / `from` 晚于 `to` ⇒ `400`；
 * - `401` 令牌对但没会话 · `502` 上游 / 网络故障（含会话失效那档）。
 *
 * @param session 会话装配；流水来自**校园卡**站点（[ServeSession.cardSource]，
 *   与 `/api/card/transactions` 同一个源）。
 */
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

/**
 * `GET /api/match/dining` 的 `data`。
 *
 * 全部字段都是**统计结果**：没有商户全称、没有金额、没有明细（见文件头红线那一段）。
 */
@Serializable
internal data class MatchDiningData(
    /** 统计窗口的起点（`YYYY-MM-DD`；省略 `?from=` 时 = `to` 往前 [DINING_DEFAULT_DAYS] 天）。 */
    val from: String,
    /** 统计窗口的终点（`YYYY-MM-DD`；省略 `?to=` 时 = 服务端系统时区的今天）。 */
    val to: String,
    /** 真正进统计的**笔数**（窗口内的流水全表；不是「食堂消费笔数」）。 */
    val scanned: Int,
    /** true = 到 [MAX_PAGES] 页上限还没拉完 ⇒ [hourCounts] / [canteens] 只覆盖前几页（如实）。 */
    val truncated: Boolean,
    /** 小时（`"0".."23"`，**字符串键、升序**）→ 那个小时的食堂消费笔数（只看支出）。 */
    val hourCounts: Map<String, Int>,
    /**
     * 24 位 `0`/`1`：这一小时**经常**去食堂（该小时 ≥2 笔）才置 1；一个 1 都没有时是**空串**。
     * 判据与 `MatchProfile.build` 的 `diningBits` **同一行**（本层不重写一份），
     * 语义也与分享码里那一格相同（空串 = 这一维没数据、不进码）。
     */
    val diningHours: String,
    /** 常去的食堂（去过 ≥2 次才留下、按次数降序、最多 5 个；`DiningHabit.canteens` 的口径）。 */
    val canteens: List<String>,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

/**
 * 窗口内的流水全表（按页拉）→ `(行, 有没有到上限)`。
 *
 * `:data` 的 `transactions` 一次给一页 + 总数（与 `/api/card/transactions` 用的是同一个方法），
 * 统计要的是**窗口全表** ⇒ 这里按 [MAX_PAGES] 页封顶地拉：够了就停、到顶就如实标 `truncated`。
 * 一行解析都不在这里做（分页是取数形状，不是口径）。
 */
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

/**
 * 这一端点只开「饭点」与「食堂」两维：其余十维要么不在这一端点的话题里（课表 / 考试 / 教材），
 * 要么这一端拿不到（[MatchProfile.Dimensions.identity] —— 学工档案，见文件头）。
 * 全部**显式关掉**而不是留在默认值上：默认值会随 `MatchProfile.Dimensions` 漂。
 */
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

/** 最多拉几页（[DINING_PAGE_SIZE] × 它 = 统计窗口的笔数上限；超了如实标 `truncated`）。 */
private const val MAX_PAGES = 5
