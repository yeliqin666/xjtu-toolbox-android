package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.dzpz.DownloadInfo
import com.xjtu.toolbox.dzpz.FormContext
import com.xjtu.toolbox.dzpz.LinkageResult
import com.xjtu.toolbox.dzpz.SubmitResult
import com.xjtu.toolbox.dzpz.TranscriptSource
import com.xjtu.toolbox.dzpz.TranscriptTypeOption
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.safeIntOrNull
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 电子成绩单（`dzpz.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [TranscriptSource]。
 *
 * serve 侧是**两段式**（契约 §5.12，与契约 §5.2 的第一条例外：成绩单只能「先建申请单、再出 PDF」）：
 *
 * | 屏上的步骤 | serve 里对应什么 |
 * |---|---|
 * | Step 1 加载表单 | `GET /api/dzpz/transcript`（§5.5） |
 * | Step 2 学籍联动 | **没有端点**（服务端在建单那一发里自己做；返回值里有学号 = 红线）⇒ 空壳，本端不读 |
 * | Step 3 保存申请 | `POST /api/dzpz/transcript/submit`（第一段，幂等） |
 * | Step 4 生成文件 | `POST /api/dzpz/transcript/preview`（第二段：生成 + 签章 + 取下载信息一口气做完） |
 * | Step 5 签章 / 第二次提交 | **没有单独的端点**（就在上面那一发里）⇒ 原样交回上一步的结果 |
 * | Step 6 取下载链接 | 交回 `preview` 存下的三格（文件名 / 大小 / 一次性票据路径） |
 * | Step 7 下载 PDF 字节 | `GET /api/dzpz/transcript/download?ticket=`（§5.12 的 `application/pdf` 例外） |
 *
 * 屏与 ViewModel 都不用改：它们按 `TranscriptSource` 那七步走，只是在这一端有几步是「同一发 HTTP」。
 */
class CampusTranscriptApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : TranscriptSource {

    /**
     * serve 形态下**整条链都走得通**（2026-10-22 批6：两段式 `submit` → `preview` → `download`，契约 §5.12）；
     * campus-api 那一形态仍然只画得了「选类型」那一步（它没有写端点）。
     */
    override val canRunWorkflow: Boolean get() = mode == ApiMode.SERVE

    override suspend fun loadCreateForm(workflowId: Int): FormContext {
        requireServe()
        val data = client.serveData("加载成绩单表单", baseUrl, TRANSCRIPT_PATH)
        // `workflowId` 不是请求参数（§5.5：本端点只端成绩单，取 DzpzDocuments.TRANSCRIPT.workflowId）
        val served = data["workflowId"].safeIntOrNull()
            ?: error("加载成绩单表单：服务端返回缺少 workflowId（契约 §5.5）")
        if (served != workflowId) {
            throw UnsupportedOperationException(
                "serve 契约只端成绩单（workflowId=$served）：$TRANSCRIPT_PATH 不收参数，" +
                    "别的电子凭证（要的那一个是 $workflowId）没有取数端点",
            )
        }
        return FormContext(
            workflowId = served,
            params = JsonObject(emptyMap()),
            submitParams = JsonObject(emptyMap()),
            maindata = JsonObject(emptyMap()),
            linkageUUID = "",
            signatureAttributesStr = "",
            signatureSecretKey = "",
            typeOptions = data.arr("typeOptions").orEmpty().mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                val name = option["name"].safeString().trim()
                val value = option["value"].safeIntOrNull()
                // 名字或取值缺一个就不列这一项 —— 不拿 0 冒充它的取值（那会变成一个「选不出对类型」的选项）
                if (name.isEmpty() || value == null) return@mapNotNull null
                // `cancelled` 不投影：§5.5 —— `:data` 的 `parseTypeOptions` 已经把 cancel=1 的滤掉了
                TranscriptTypeOption(name = name, value = value)
            },
            defaultDate = data["defaultDate"].safeString(),
            defaultRequestName = data["defaultRequestName"].safeString(),
        )
    }

    // ── 第 2~7 步：serve 是**两段式**（submit → preview → download），见类 KDoc ──

    /**
     * Step 2（学籍联动）：serve **没有这一步的端点**，而且它刻意的：服务端在建申请单那一发里自己跑一遍
     * 联动（`DzpzRoutes` 的 `submit` 那一支就是 `source.getLinkageData(form, type)` 后再 `submitCreate`），
     * 而它的返回值里有**学号** —— 契约 §5.2 的红线，例外清单里没有它。
     *
     * 所以这一格给的是**空壳**：本端后续几步都不读它（[submitCreate] 不看 `linkage`），屏上也一格都不画
     *（它只画进度文案与结果卡）。空串 = 「这一端拿不到」，不是编出来的学号。
     */
    override suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult {
        requireServe()
        return LinkageResult(studentId = "", enrollYear = "", templatePath = "", categoryName = "", workflowIdField = "")
    }

    /**
     * Step 3（第一段）：`POST /api/dzpz/transcript/submit` —— 建申请单。
     *
     * ⚠️ **真的会在教务留一条待审批记录**（消费方按钮文案必须写清）；幂等：10 分钟内重复点 ⇒
     * `created:false` + 同一个 `requestId`（契约 §5.12）。两者都算成功。
     */
    override suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
    ): SubmitResult {
        requireServe()
        // `type` 省略 = 服务端取表单第一项；这里总是把屏上那一项原样发过去
        val outcome = client.serveAction(
            baseUrl,
            SUBMIT_PATH,
            buildJsonObject { put("type", typeValue) }.toString(),
        )
        if (!outcome.ok) error("提交成绩单申请：${outcome.message}")
        val requestId = outcome.data?.get("requestId").safeIntOrNull()
            ?: error("提交成绩单申请：服务端返回缺少 requestId（契约 §5.12）")
        return SubmitResult(requestId = requestId, sessionKey = "", submitToken = 0L)
    }

    /**
     * Step 4（第二段）：`POST /api/dzpz/transcript/preview` —— 生成 + 签章 + 取下载信息。
     *
     * 服务端把三件事做完了才回，回的是一张**一次性票据**（`downloadPath`）—— 上游 host 与 link 永不出现在响应里。
     * 这一发的结果先存在自己身上，[getDownloadInfo] 再交给屏（那两个步骤在这一端是同一发）。
     */
    override suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int, requestId: Int) {
        requireServe()
        val outcome = client.serveAction(baseUrl, PREVIEW_PATH, "{}")
        if (!outcome.ok) error("生成成绩单：${outcome.message}")
        val data = outcome.data ?: error("生成成绩单：服务端返回缺少 data（契约 §5.12）")
        previewInfo = DownloadInfo(
            filename = data["filename"].safeString(),
            filesize = data["filesize"].safeString(),
            downloadUrl = data["downloadPath"].safeString(),
        )
    }

    /**
     * Step 5（签章 / 第二次提交）：**两段式里没有这一步** —— 它们在服务端那一发 `preview` 里做完了
     * （`DzpzRoutes` 里 `generatePreviewPdf` → `reloadAndForward` → `getDownloadInfo` → `downloadPdf`
     * 是一口气跑的）。所以这里原样交回上一步的结果，屏上只看得到进度文案在往前走。
     */
    override suspend fun reloadAndForward(
        ctx: FormContext,
        firstResult: SubmitResult,
        typeValue: Int,
    ): SubmitResult {
        requireServe()
        return firstResult
    }

    /** Step 6（取下载信息）：交回 `preview` 那一发存下的三格（文件名 / 大小 / 一次性票据路径）。 */
    override suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo =
        previewInfo ?: error("取下载信息：还没有生成过成绩单（先走提交与生成那两步）")

    /**
     * Step 7：`GET /api/dzpz/transcript/download?ticket=` —— 票据换 PDF（合同第三条例外 `application/pdf`）。
     *
     * ⚠️ **失败那一半回的是信封（JSON）**（票据过期是 `404` + 一句中文）⇒ 这里先看状态码与内容类型，
     * 不把一段 JSON 当成 PDF 交上去。
     */
    override suspend fun downloadPdf(url: String): ByteArray {
        requireServe()
        val response = client.get(if (url.startsWith("http")) url else "$baseUrl$url")
        val type = response.contentType()
        // 不比整个 ContentType（它带 charset 参数），只看主/子类型
        val isPdf = type?.contentType.equals("application", ignoreCase = true) &&
            type?.contentSubtype.equals("pdf", ignoreCase = true)
        if (response.status.value != 200 || !isPdf) {
            error("下载成绩单：服务端没有回 PDF（HTTP ${response.status.value}），票据可能已经用过或过期了")
        }
        return response.bodyAsBytes()
    }

    /** `preview` 那一发的结果（见 [generatePreviewPdf]）。 */
    private var previewInfo: DownloadInfo? = null

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("成绩单取数", "$TRANSCRIPT_PATH + $SUBMIT_PATH + $PREVIEW_PATH + $DOWNLOAD_PATH")
    }
}

/** 成绩单建单表单与两段式（契约 §5.5 的只读那一条 + §5.12 的写那两条）。 */
private const val TRANSCRIPT_PATH = "/api/dzpz/transcript"

/** 第一段：建申请单（幂等）。 */
private const val SUBMIT_PATH = "/api/dzpz/transcript/submit"

/** 第二段：生成 + 签章 + 取一次性票据。 */
private const val PREVIEW_PATH = "/api/dzpz/transcript/preview"

/** 第三段（`preview` 回的票据就指向这里）。 */
private const val DOWNLOAD_PATH = "/api/dzpz/transcript/download"
