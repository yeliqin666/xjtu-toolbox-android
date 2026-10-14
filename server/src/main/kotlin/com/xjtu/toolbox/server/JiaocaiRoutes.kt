package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.jiaocai.AppJiaocaiSource
import com.xjtu.toolbox.jiaocai.JiaocaiBook
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/jiaocai*` —— 教材中心的**两个只读端点**（`docs/api-contract.md` §5.6），走 `:data` 的
 * [AppJiaocaiSource]（它包住搬进 `:data` 的 `JiaocaiApi`；解析口径一行不重做）。
 *
 * | 端点 | 对应端口方法 |
 * |---|---|
 * | `GET /api/jiaocai/search` | `JiaocaiSource.search(keyword)` |
 * | `GET /api/jiaocai/ssno` | `JiaocaiSource.fetchSsno(book)` |
 *
 * ## 端了什么、刻意不端什么
 *
 * - 端：检索（屏上那一栏的全部输入就是**一个关键词** —— `JiaocaiViewModel.keyword`，
 *   没有院系 / 教师那些筛选控件）与「这本书有没有全文」那一枪；
 * - **不端**「本学期我的教材」那一栏：它的数据不在教材中心，而在**教务**的课表缓存里
 *   （`:core` 的 `JiaocaiTermTextbooks` 是个**由宿主实现**的端口 —— `:app` 读 `ScheduleCache`、
 *   桌面端回空表）。serve 这一层没有那份缓存，而且 `:data` 里真正能取到那份数据的是
 *   `JwxtScheduleApi.textbooks(studentId, term)`（帆软报表），**要学号**（红线）
 *   ⇒ 本批不端，如实缺口。`:web` 上「查教材」栏在 serve 模式下只有「检索」那半；
 * - **不端**全文阅读：`jiaocai1` 的页图与阅读器（`:data` 的 `Jiaocai1Api`）是另一条链
 *   （另一个域名、另一套会话），本轮不碰它。本端点只回答「ssno 是多少」。
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `JiaocaiModels`）
 *
 * - **search**：命中 `JiaocaiBook` 的全部字段（`:core` 的模型原样投影，字段名一个不改）：
 *   `id`（`general_55258665` 那种拼好的 id）、`appId` / `engineInstanceId`（`ssno` 那一枪要）
 *   `title`（`:data` 已剥掉 `<b>` 那些标签）、`author`、`summary`（`<br>` 分行的富文本转成的纯文本）、
 *   `ssno`（**列表接口没有链接 ⇒ 恒 null**；进详情才拿得到，见下）、`hasFullText`
 *   （`:data` 的判据：`ssno != null || fields.values.any { "本地全文" in it }`）、
 *   `fields`（从描述拆出的键值对：课程名称 / 开课学院 / 教材名称 / 出版社 / ISBN号…，
 *   键名是**上游原文**，本层不翻译）。书目信息是**公开数据**（与黄页/教师检索同档），
 *   没有姓名/学号那一类字段；
 * - **ssno**：`{id, ssno}` —— `ssno` 是全文库书目号，取不到就是 **`null`**
 *   （§4：`null` = 上游明确说没有：这本书没开放本地全文，或者会话过期那一枪没补上）。
 *   ⚠️ 只回 `{id, ssno}` 两格，不回 `hasFullText`：这里没有 `fields`（请求里只有三个 id），
 *   用 `ssno != null` 重算一遍等于给同一个判据起第二个写法。
 *
 * ## 请求参数与错误码
 *
 * - `?keyword=`（`search` 必填，空白 ⇒ 400）。**不收 `page` / `pageSize`**：这一屏没有翻页
 *   （`:data` 固定 20 条一页、只取第一页，见 `JiaocaiSource.search` 的 KDoc）；
 * - `?id=&appId=&engineInstanceId=`（`ssno` 三个都必填，取自 `/api/jiaocai/search` 的同名三格）：
 *   缺 / 空白 ⇒ 400；`appId` / `engineInstanceId` 不是整数 ⇒ 400（详情页 URL 就靠这三格拼）；
 * - 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句）。
 *   ⚠️ 没有会话时上游不返数据、返一张「JS 跳转登录链」页，`:data` 的 `PortalRedirect` 会自己跟完那几跳
 *   （那是取数那一半的事，本层看不见）；跟不通就按 `:data` 的老口径**回空列表**（检索）
 *   —— 所以「查不到书」与「会话断了」在检索这一发上**长得一样**，那是 `:data` 既有的口径，本层不改。
 *
 * @param session 会话装配；教材中心站点（key = `jiaocai`，`:data` 的 `JiaocaiSession`）从它那里取。
 */
internal fun Route.jiaocaiRoutes(session: ServeSession) {
    route(JIAOCAI_SEGMENT) {

        // ── 按关键词检索教材 ──────────────────────────────────────────
        get(JIAOCAI_SEARCH) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val keyword = call.request.queryParameters[JIAOCAI_PARAM_KEYWORD]?.trim().orEmpty()
            if (keyword.isEmpty()) {
                return@get call.respondBadRequestMessage("keyword 必填（教材检索词）")
            }
            try {
                val books = session.jiaocaiSource().search(keyword)
                call.respond(
                    ApiEnvelope.ok(JiaocaiSearchData(keyword = keyword, count = books.size, books = books.map { it.toDto() })),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "检索教材")
            }
        }

        // ── 一本书的全文库书目号 ──────────────────────────────────────
        get(JIAOCAI_SSNO) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val id = call.request.queryParameters[JIAOCAI_PARAM_ID]?.trim().orEmpty()
            if (id.isEmpty()) {
                return@get call.respondBadRequestMessage("id 必填（取自 /api/jiaocai/search 的 id）")
            }
            val appId = call.requireIntParam(JIAOCAI_PARAM_APP_ID, "取自 /api/jiaocai/search 的 appId", min = 0)
                ?: return@get
            val engineInstanceId = call.requireIntParam(
                JIAOCAI_PARAM_ENGINE_INSTANCE_ID,
                "取自 /api/jiaocai/search 的 engineInstanceId",
                min = 0,
            ) ?: return@get
            try {
                // `:data` 的 `fetchSsno` 收的是整本书 —— 这里只有三格 url 原料，其余字段用默认值，
                // 它读的也只有这三格（`id` 里 `_` 后面那串数字 + `engineInstanceId` + `appId`）。
                val book = JiaocaiBook(id = id, appId = appId, engineInstanceId = engineInstanceId)
                call.respond(ApiEnvelope.ok(JiaocaiSsnoData(id = id, ssno = session.jiaocaiSource().fetchSsno(book))))
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "取教材全文号")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/jiaocai/search` 的 `data`。 */
@Serializable
internal data class JiaocaiSearchData(
    /** 请求里那个检索词，原样回。 */
    val keyword: String,
    val count: Int,
    val books: List<JiaocaiBookDto>,
)

/**
 * 一本教材（`:core` 的 [JiaocaiBook] 的线上形状）。
 *
 * `fields` 是**上游原文**的键值对（课程名称 / 开课学院 / 教材名称 / 出版社 / ISBN号…）：
 * 键名不翻译、不重排（`LinkedHashMap` 的顺序 = 上游描述里的顺序）。
 */
@Serializable
internal data class JiaocaiBookDto(
    val id: String,
    val appId: Int,
    val engineInstanceId: Int,
    val title: String,
    val author: String,
    val summary: String,
    /** 全文库书目号。**列表接口恒 `null`**（链接只在详情页里），要它得打 `/api/jiaocai/ssno`。 */
    val ssno: String?,
    /** `:data` 的判据：有 `ssno`，或者描述里那句「本地全文」。 */
    val hasFullText: Boolean,
    val fields: Map<String, String>,
)

/** `GET /api/jiaocai/ssno` 的 `data`。 */
@Serializable
internal data class JiaocaiSsnoData(
    /** 请求里那个书目 id，原样回。 */
    val id: String,
    /** 全文库书目号；`null` = 这本书没有开放本地全文（或会话过期那一枪没补上）。 */
    val ssno: String?,
)

// ── 投影 helpers ─────────────────────────────────────────────────────

private fun JiaocaiBook.toDto() = JiaocaiBookDto(
    id = id,
    appId = appId,
    engineInstanceId = engineInstanceId,
    title = title,
    author = author,
    summary = summary,
    ssno = ssno,
    hasFullText = hasFullText,
    fields = fields,
)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 教材中心站点会话（key = `jiaocai`，`:data` 的 `JiaocaiSession`；全文库借的也是它）。 */
internal fun ServeSession.jiaocaiSite(): SiteSession = sessionManager.getSite(ServeSession.JIAOCAI_SITE_KEY)

internal fun ServeSession.jiaocaiSource(): AppJiaocaiSource = AppJiaocaiSource(jiaocaiSite())

internal const val JIAOCAI_SEGMENT = "jiaocai"
internal const val JIAOCAI_SEARCH = "search"
internal const val JIAOCAI_SSNO = "ssno"
internal const val JIAOCAI_PARAM_KEYWORD = "keyword"
internal const val JIAOCAI_PARAM_ID = "id"
internal const val JIAOCAI_PARAM_APP_ID = "appId"
internal const val JIAOCAI_PARAM_ENGINE_INSTANCE_ID = "engineInstanceId"
