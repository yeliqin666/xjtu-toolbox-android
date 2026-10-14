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

/**
 * 电子成绩单（`dzpz.xjtu.edu.cn`）的 **serve 版取数**：给 `:web` 用，实现 [TranscriptSource]。
 *
 * | 端点 | 本类实现的方法 |
 * |---|---|
 * | `GET /api/dzpz/transcript` | [loadCreateForm]（七步里的第一步） |
 *
 * 契约 §5.5；`:server` 那一侧是 `DzpzRoutes.kt`（薄包装 `:data` 的 `AppTranscriptSource`）。
 *
 * ## 七步里只端了第一步（这张屏的**如实缺口**）
 *
 * `:core` 的 [TranscriptSource] 有七个方法，serve 契约只端了第 1 步：
 *
 * | 步骤 | 方法 | 端不端 |
 * |---|---|---|
 * | 1 建单表单（类型选项 / 默认日期 / 默认请求名） | [loadCreateForm] | **端** |
 * | 2 学籍联动 | [getLinkageData] | 不端：返回值里有**学号**（红线） |
 * | 3 生成预览 PDF | [generatePreviewPdf] | 不端：写路径 |
 * | 4 / 5 两次提交（含签章） | [submitCreate] / [reloadAndForward] | 不端：写路径 |
 * | 6 取下载链接 | [getDownloadInfo] | 不端：它吃的 `SubmitResult` 只能由第 4/5 步产生 |
 * | 7 下载 PDF 字节 | [downloadPdf] | 不端：写路径的输出，而且「存到哪儿」是宿主能力 |
 *
 * 所以 [canRunWorkflow] 报 `false`：`TranscriptScreen` 那边**不画**「一键申请成绩单」与那五步的
 * 步骤预览（点了必然失败的东西不摆出来），改画一张写清缺口的卡。
 *
 * ## 那六个「不投影」的字段：如实留空，不编一份假表单
 *
 * `FormContext` 里有六格是**提交表单的原料**：三个 JSON（`params` / `submitParams` / `maindata`）
 * 与三把签章密钥（`linkageUUID` / `signatureAttributesStr` / `signatureSecretKey`）。契约 §5.5
 * 刻意不投影它们（`signatureSecretKey` 端出去等于把签章密钥交给浏览器）⇒ 这里**留空**
 *（`JsonObject(emptyMap())` 与空串），而不是编一份看起来像真的表单体。因为第 2~7 步没有端点，
 * 这六格也永远走不进上游 —— 屏上那半条链是不画的。
 *
 * ## 默认形态（campus-api）
 *
 * 成绩单在 campus-api 里**没有对应端点**（`:app` 与桌面是走 `:data` 的 okhttp 抓的）⇒ 非 serve
 * 分支一律 [serveOnly]（抛错，不返回一份空数据）。`:web` 的装配也只在 serve 模式下 new 这个类。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（`:web` 由 `:server` 托管，必须是空）
 * @param mode 见 [ApiMode]：serve 支才走本契约的端点
 */
class CampusTranscriptApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：这一份取数只认 serve 契约（§5.5）。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : TranscriptSource {

    /** 见类 KDoc：七步里只端了第一步 ⇒ 出 PDF 那半条链在本端不存在。 */
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
            // 见类 KDoc：这六格刻意不投影 ⇒ 如实留空（不是编一份假表单）
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

    override suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int): String =
        noStep("生成预览 PDF", "写路径：它在上游建一份文档")

    override suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
        docId: String,
    ): SubmitResult = noStep("第一次提交", "写路径：建流程（那半条链只服务于出 PDF）")

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
            "这一屏只画得了第一步「选类型」（TranscriptSource.canRunWorkflow = false）",
    )
}

/** 成绩单建单表单（契约 §5.5 的七个端点之一）。 */
private const val TRANSCRIPT_PATH = "/api/dzpz/transcript"
