package com.xjtu.toolbox.server

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.dzpz.AppTranscriptSource
import com.xjtu.toolbox.dzpz.DzpzDocuments
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/api/dzpz*` —— 电子凭证（成绩单）**只读的第一步**（`docs/api-contract.md` §5.5）。
 *
 * | 端点 | 对应端口 / `:data` 实现 |
 * |---|---|
 * | `GET /api/dzpz/transcript` | `TranscriptSource.loadCreateForm(workflowId)`（`AppTranscriptSource` 包住 `TranscriptApi`） |
 *
 * ## 为什么只有「第一步」
 *
 * `:core` 的 `TranscriptSource` 有七步，只有第 1 步是**取数**：
 *
 * | 步骤 | 端口方法 | 本轮端不端 |
 * |---|---|---|
 * | 1 建单表单（类型选项 / 默认日期 / 默认请求名） | `loadCreateForm` | **端**（本端点） |
 * | 2 联动（学号 / 入学年 / 模板 / 分类名） | `getLinkageData` | 不端：返回值里有**学号**（红线），且它只服务于后面那几步 |
 * | 3 生成预览 PDF | `generatePreviewPdf` | 不端：写路径（在上游建文档） |
 * | 4 第一次提交（建流程） | `submitCreate` | 不端：写路径 |
 * | 5 重载 + 校验 + 第二次提交（签章 / 转发下载节点） | `reloadAndForward` | 不端：写路径 |
 * | 6 取最终下载链接 | `getDownloadInfo` | 不端：它吃的 `SubmitResult` 只能由第 4/5 步产生 |
 * | 7 下载 PDF 字节 | `downloadPdf` | 不端：写路径的输出，且「落盘」是宿主能力（`:app` = MediaStore/下载管理，桌面 = 写下载目录） |
 *
 * ⇒ **serve 模式下这张屏现在只能画「选类型」那一步**：出 PDF 那半条链没有端点（如实缺口，
 * 契约 §5.5 记着；`:web` 消费方待接）。
 *
 * ## 逐字段映射（判据是真源：`:data` 的解析 + `:core` 的 `TranscriptModels`）
 *
 * - `workflowId`：**本端点只端成绩单**，取 `DzpzDocuments.TRANSCRIPT.workflowId`（= 29）——
 *   不是请求参数。引擎里别的 `workflowId` 属于别的证明文件，等真有屏要再接；
 * - `typeOptions` ← `FormContext.typeOptions`（`TranscriptTypeOption.name`/`value`）。
 *    **不投影 `cancelled`**：`:data` 的 `parseTypeOptions` 已经把 `cancel=1` 那些滤掉了
 *   （`filter { !it.cancelled }`）⇒ 投影出来恒为 `false`，多一个永远不动的字段只会多一处口径；
 * - `defaultDate` / `defaultRequestName` ← `FormContext` 同名字段（上游 `field7249` / `field-1`）；
 * - **不投影** `params` / `submitParams` / `maindata`（原样 JSON：里面是提交表单体与
 *   `signatureSecretKey` 那类**签章密钥**，属于写路径的原料 —— 端出去等于把密钥交给浏览器）、
 *   `linkageUUID` / `signatureAttributesStr` / `signatureSecretKey`（同上）。
 *
 * ## 错误码
 *
 * 401 令牌对但没会话 · 502 上游 / 网络故障（`FriendlyError` 中文短句）。**没有 400**：
 * 本端点不收任何参数（形状不对只能是 URL 写错了 ⇒ 404）。
 *
 * @param session 会话装配；`dzpz` 站点从它那里取（登录时随 [ServeSession.SESSION_SITE_KEYS] 预热）。
 */
internal fun Route.dzpzRoutes(session: ServeSession) {
    route(DZPZ_SEGMENT) {

        // ── 建单表单（成绩单的第一步，也是唯一一步取数）────────────────
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

/** `GET /api/dzpz/transcript` 的 `data`（契约 §5.5 逐字段）。 */
@Serializable
internal data class DzpzTranscriptData(
    val workflowId: Int,
    val typeOptions: List<DzpzTypeOptionDto>,
    val defaultDate: String,
    val defaultRequestName: String,
)

/**
 * 一个成绩单类型选项（`TranscriptTypeOption` 的线上形状）。
 *
 * ⚠️ 没有 `cancelled`：见文件头 KDoc（`:data` 已经滤掉取消项，这个字段端出去恒 `false`）。
 */
@Serializable
internal data class DzpzTypeOptionDto(val name: String, val value: Int)

// ── 常量 ─────────────────────────────────────────────────────────────

/** 电子凭证站点会话。 */
internal fun ServeSession.dzpzSite(): SiteSession = sessionManager.getSite(ServeSession.DZPZ_SITE_KEY)

internal const val DZPZ_SEGMENT = "dzpz"
internal const val DZPZ_TRANSCRIPT = "transcript"
