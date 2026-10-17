package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.jiaocai.AppJiaocaiSource
import com.xjtu.toolbox.jiaocai.JiaocaiBook
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/jiaocai*`。 */
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

/** 一本教材（`:core` 的 [JiaocaiBook] 的线上形状）。 */
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
