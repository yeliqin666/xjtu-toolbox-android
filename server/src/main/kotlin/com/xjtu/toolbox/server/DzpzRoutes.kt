package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.dzpz.AppTranscriptSource
import com.xjtu.toolbox.dzpz.DzpzDocuments
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** `/api/dzpz*` —— 电子凭证（成绩单）只读的第一步（`docs/api-contract.md` §5.5）。 */
internal fun Route.dzpzRoutes(session: ServeSession) {
    route(DZPZ_SEGMENT) {

        get(DZPZ_TRANSCRIPT) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            try {
                val form = AppTranscriptSource(session.dzpzSite())
                    .loadCreateForm(DzpzDocuments.TRANSCRIPT.workflowId)
                call.respond(
                    ApiEnvelope.ok(
                        DzpzTranscriptData(
                            workflowId = form.workflowId,
                            typeOptions = form.typeOptions.map { DzpzTypeOptionDto(it.name, it.value) },
                            defaultDate = form.defaultDate,
                            defaultRequestName = form.defaultRequestName,
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "加载成绩单表单")
            }
        }
    }
}

// ── 数据模型（客户端形状）──────────────────────────────────────────

/** `GET /api/dzpz/transcript` 的 `data`（契约 §5.5）。 */
@Serializable
internal data class DzpzTranscriptData(
    val workflowId: Int,
    val typeOptions: List<DzpzTypeOptionDto>,
    val defaultDate: String,
    val defaultRequestName: String,
)

/** 一个成绩单类型选项（`TranscriptTypeOption` 的线上形状）。 */
@Serializable
internal data class DzpzTypeOptionDto(val name: String, val value: Int)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 电子凭证站点会话。 */
internal fun ServeSession.dzpzSite(): SiteSession = sessionManager.getSite(ServeSession.DZPZ_SITE_KEY)

internal const val DZPZ_SEGMENT = "dzpz"
internal const val DZPZ_TRANSCRIPT = "transcript"
