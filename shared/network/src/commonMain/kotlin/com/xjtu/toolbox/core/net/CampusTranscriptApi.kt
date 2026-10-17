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
import kotlinx.serialization.json.JsonObject

/** 电子成绩单（`dzpz.xjtu.edu.cn`）的 serve 版取数：给 `:web` 用，实现 [TranscriptSource]。 */
class CampusTranscriptApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : TranscriptSource {

    /** 见类 KDoc：七步里只端了第一步。 */
    override val canRunWorkflow: Boolean get() = false

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

    // ── 第 2~7 步：契约里没有端点（见类 KDoc 的表）────────────────────

    override suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult =
        noStep("学籍联动", "它的返回值里有**学号** —— 契约 §5.2 的红线，例外清单里没有它")

    override suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
    ): SubmitResult = noStep("保存申请", "写路径：建流程（那半条链只服务于出 PDF）")

    override suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int, requestId: Int): Unit =
        noStep("生成成绩单文件", "写路径：它在上游建一份文档")

    override suspend fun reloadAndForward(
        ctx: FormContext,
        firstResult: SubmitResult,
        typeValue: Int,
    ): SubmitResult = noStep("签章 / 第二次提交", "写路径（重载 + 校验 + 转发到下载节点）")

    override suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo =
        noStep("取下载链接", "它吃的 `SubmitResult` 只能由第 4/5 步产生，而那两步没有端点")

    override suspend fun downloadPdf(url: String): ByteArray =
        noStep("下载 PDF 字节", "写路径的输出；而且「文件存到哪儿」是宿主的活（浏览器这一端没有那一档）")

    private fun requireServe() {
        if (mode != ApiMode.SERVE) serveOnly("成绩单建单表单", TRANSCRIPT_PATH)
    }

    private fun noStep(step: String, why: String): Nothing = throw UnsupportedOperationException(
        "serve 契约没有「第 ${step}」这一步的端点：$why（docs/api-contract.md §5.5）——" +
            "这一屏只画得了第一步「加载表单」（TranscriptSource.canRunWorkflow = false）",
    )
}

/** 成绩单建单表单（契约 §5.5 的七个端点之一）。 */
private const val TRANSCRIPT_PATH = "/api/dzpz/transcript"
