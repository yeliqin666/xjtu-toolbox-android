package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.dzpz.AppTranscriptSource
import com.xjtu.toolbox.dzpz.DzpzDocuments
import com.xjtu.toolbox.dzpz.FormContext
import com.xjtu.toolbox.dzpz.SubmitResult
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/dzpz*` —— 电子凭证（成绩单）。
 *
 * 读一半（`GET transcript`：类型选项/默认日期）来自契约 §5.5；写一半（`submit` → `preview` →
 * `download`）来自契约 §5.2 的第一条例外（①）：成绩单只能"先建申请单、再出 PDF"两段走，
 * 且 PDF 不能回 JSON —— 所以第二段交回一张**一次性本机票据**，第三段用票据取
 * `application/pdf`（契约已登记成第三个 envelope 例外）。上游的签章下载链全程留在服务端。
 */
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

        // ── 第一段：建申请单（幂等条：同一类型还新鲜 ⇒ 复用，不开第二张）──
        post(DZPZ_TRANSCRIPT_SUBMIT) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val body = call.receiveOrNull<DzpzTranscriptSubmitRequest>()
                ?: return@post call.respondBadRequestMessage(DZPZ_SUBMIT_BODY_MESSAGE)
            try {
                val source = AppTranscriptSource(session.dzpzSite())
                val form = source.loadCreateForm(DzpzDocuments.TRANSCRIPT.workflowId)
                val type = body.type ?: form.typeOptions.firstOrNull()?.value
                    ?: return@post call.respondBadRequestMessage(DZPZ_SUBMIT_NO_TYPE_MESSAGE)
                if (form.typeOptions.none { it.value == type }) {
                    return@post call.respondBadRequestMessage(DZPZ_SUBMIT_TYPE_MESSAGE)
                }
                // 幂等：看目标状态 —— 同一类型的申请单还新鲜就不重建（重复点击不会开第二张）
                val fresh = TranscriptPending.reuse(type)
                if (fresh != null) {
                    return@post call.respond(
                        ApiEnvelope.ok(
                            DzpzTranscriptSubmitData(
                                requestId = fresh.result.requestId,
                                type = type,
                                created = false,
                                message = DZPZ_SUBMIT_REUSED_MESSAGE,
                            ),
                        ),
                    )
                }
                val linkage = source.getLinkageData(form, type)
                val created = source.submitCreate(form, linkage, type)
                TranscriptPending.remember(form, created, type)
                call.respond(
                    ApiEnvelope.ok(
                        DzpzTranscriptSubmitData(
                            requestId = created.requestId,
                            type = type,
                            created = true,
                            message = DZPZ_SUBMIT_CREATED_MESSAGE,
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "提交成绩单申请")
            }
        }

        // ── 第二段：生成 + 签章 + 取下载信息 ⇒ 交回一张一次性票据 ──
        post(DZPZ_TRANSCRIPT_PREVIEW) {
            if (!session.authenticated) return@post call.respondLoginRequired()
            val pending = TranscriptPending.last()
                ?: return@post call.respondActionRejected(DZPZ_PREVIEW_NO_SUBMIT_MESSAGE)
            try {
                val source = AppTranscriptSource(session.dzpzSite())
                source.generatePreviewPdf(pending.ctx.workflowId, pending.type, pending.result.requestId)
                val forwarded = source.reloadAndForward(pending.ctx, pending.result, pending.type)
                val info = source.getDownloadInfo(forwarded)
                val bytes = source.downloadPdf(info.downloadUrl)
                if (bytes.isEmpty()) {
                    return@post call.respondActionRejected(DZPZ_PREVIEW_EMPTY_MESSAGE)
                }
                val ticket = TranscriptDownloads.put(bytes, info.filename)
                TranscriptPending.forget()
                call.respond(
                    ApiEnvelope.ok(
                        DzpzTranscriptPreviewData(
                            ok = true,
                            filename = info.filename,
                            filesize = info.filesize,
                            downloadPath = "$API_PREFIX/$DZPZ_SEGMENT/$DZPZ_TRANSCRIPT_DOWNLOAD?$DZPZ_TICKET_PARAM=$ticket",
                        ),
                    ),
                )
            } catch (e: Exception) {
                call.respondUpstreamFailure(e, "生成成绩单")
            }
        }

        // ── 第三段：票据换 PDF（一次性；契约里那一条 `application/pdf` 例外）──
        get(DZPZ_TRANSCRIPT_DOWNLOAD) {
            if (!session.authenticated) return@get call.respondLoginRequired()
            val ticket = call.request.queryParameters[DZPZ_TICKET_PARAM]?.trim().orEmpty()
            if (ticket.isEmpty()) return@get call.respondBadRequestMessage(DZPZ_DOWNLOAD_TICKET_MESSAGE)
            val file = TranscriptDownloads.take(ticket)
                ?: return@get call.respondText(
                    DZPZ_DOWNLOAD_GONE_MESSAGE,
                    status = HttpStatusCode.NotFound,
                )
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"$DZPZ_DOWNLOAD_FALLBACK_NAME\";" +
                    " filename*=UTF-8''${rfc5987(file.filename)}",
            )
            call.respondBytes(file.bytes, ContentType.Application.Pdf, HttpStatusCode.OK)
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

/** `POST /api/dzpz/transcript/submit` 的请求体：`type` 省略 = 表单里的第一个选项。 */
@Serializable
internal data class DzpzTranscriptSubmitRequest(val type: Int? = null)

/** `POST /api/dzpz/transcript/submit` 的 `data`。`created=false` = 复用了上一张（幂等命中）。 */
@Serializable
internal data class DzpzTranscriptSubmitData(
    val requestId: Int,
    val type: Int,
    val created: Boolean,
    val message: String,
)

/** `POST /api/dzpz/transcript/preview` 的 `data`：不用 `downloadUrl` 直指学校，只给本机一次性票据。 */
@Serializable
internal data class DzpzTranscriptPreviewData(
    val ok: Boolean,
    val filename: String,
    val filesize: String,
    val downloadPath: String,
)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 电子凭证站点会话。 */
internal fun ServeSession.dzpzSite(): SiteSession = sessionManager.getSite(ServeSession.DZPZ_SITE_KEY)

internal const val DZPZ_SEGMENT = "dzpz"
internal const val DZPZ_TRANSCRIPT = "transcript"
internal const val DZPZ_TRANSCRIPT_SUBMIT = "transcript/submit"
internal const val DZPZ_TRANSCRIPT_PREVIEW = "transcript/preview"
internal const val DZPZ_TRANSCRIPT_DOWNLOAD = "transcript/download"

/** 票据参数名（一次性下载）。 */
internal const val DZPZ_TICKET_PARAM = "ticket"

/** 票据有效期：10 分钟。过点作废（票据只在内存里，不落盘）。 */
internal const val DZPZ_DOWNLOAD_TTL_MS = 10 * 60 * 1000L

/** 申请单新鲜期：10 分钟。同类型在这窗口里重复点「提交」不会开第二张。 */
internal const val DZPZ_PENDING_TTL_MS = 10 * 60 * 1000L

/** 下载响应的 ASCII 回退名（真实文件名走 `filename*=UTF-8''`）。 */
internal const val DZPZ_DOWNLOAD_FALLBACK_NAME = "transcript.pdf"

internal const val DZPZ_SUBMIT_BODY_MESSAGE = "请求体要是 {\"type\": 0}（或 {}\uff0c\u7528\u8868\u5355\u7b2c\u4e00\u9879）"
internal const val DZPZ_SUBMIT_NO_TYPE_MESSAGE = "表单里没有可选成绩单类型"
internal const val DZPZ_SUBMIT_TYPE_MESSAGE = "这个成绩单类型不在表单选项里"
internal const val DZPZ_SUBMIT_CREATED_MESSAGE = "申请单已保存（下一步生成成绩单）"
internal const val DZPZ_SUBMIT_REUSED_MESSAGE = "上一张申请单还在，直接用它（没有重复提交）"
internal const val DZPZ_PREVIEW_NO_SUBMIT_MESSAGE = "还没有申请单：先 POST /api/dzpz/transcript/submit"
internal const val DZPZ_PREVIEW_EMPTY_MESSAGE = "学校回了空文件"
internal const val DZPZ_DOWNLOAD_TICKET_MESSAGE = "缺 ticket"
internal const val DZPZ_DOWNLOAD_GONE_MESSAGE = "这张下载票据已经用过了（一次性）"

// ── 进程内记忆 ───────────────────────────────────────────────────────

/**
 * 两段式提交的幂等锚点：上一次建成的申请单（含后续 `reloadAndForward` 需要的表单上下文）。
 *
 * serve 是单人本机进程，所以放个内存单格就够；重启即失效（重启后重提交本来也该重建）。
 */
internal object TranscriptPending {
    private data class Pending(val ctx: FormContext, val result: SubmitResult, val type: Int, val atMs: Long)

    @Volatile
    private var pending: Pending? = null

    /** 同类型且还新鲜的申请单（幂等命中）。 */
    fun reuse(type: Int, nowMs: Long = System.currentTimeMillis()): PendingForServe? =
        pending
            ?.takeIf { p -> p.type == type && nowMs - p.atMs < DZPZ_PENDING_TTL_MS }
            ?.let { PendingForServe(it.ctx, it.result, it.type) }
    /** 最近一张申请单（第二段用它，不看新鲜期：刚刚才建的那张）。 */
    fun last(): PendingForServe? =
        pending?.let { PendingForServe(it.ctx, it.result, it.type) }

    fun remember(ctx: FormContext, result: SubmitResult, type: Int) {
        pending = Pending(ctx, result, type, System.currentTimeMillis())
    }

    fun forget() {
        pending = null
    }

    /** 清空（测试用）。 */
    fun reset() {
        pending = null
    }
}

/** [TranscriptPending] 的对外形状（不用把私有的 `Pending` 漏出去）。 */
internal data class PendingForServe(val ctx: FormContext, val result: SubmitResult, val type: Int)

/** 一次性下载票据：内存里的 PDF 字节，`take` 之后就没了（不落盘、不可重放）。 */
internal object TranscriptDownloads {
    private data class Ticket(val bytes: ByteArray, val filename: String, val atMs: Long)

    private val tickets = ConcurrentHashMap<String, Ticket>()

    fun put(bytes: ByteArray, filename: String): String {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { now - it.value.atMs > DZPZ_DOWNLOAD_TTL_MS }
        val id = UUID.randomUUID().toString()
        tickets[id] = Ticket(bytes, filename.ifBlank { DZPZ_DOWNLOAD_FALLBACK_NAME }, now)
        return id
    }

    /** 取一次就作废（过期也当没有）。 */
    fun take(id: String): TicketForServe? {
        val t = tickets.remove(id) ?: return null
        if (System.currentTimeMillis() - t.atMs > DZPZ_DOWNLOAD_TTL_MS) return null
        return TicketForServe(t.bytes, t.filename)
    }

    /** 清空（测试用）。 */
    fun reset() {
        tickets.clear()
    }
}

/** [TranscriptDownloads] 的对外形状。 */
internal data class TicketForServe(val bytes: ByteArray, val filename: String)

/** `Content-Disposition` 的 `filename*=UTF-8''…` 那一格（RFC 5987）。 */
internal fun rfc5987(value: String): String {
    val encoded = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return encoded
}
