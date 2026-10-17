package com.xjtu.toolbox.dzpz

import kotlinx.serialization.json.JsonObject

/** 电子成绩单（`dzpz.xjtu.edu.cn`，工作流引擎里的 `workflowId = 29`）的模型 + 取数端口。 */

/** 成绩单类型选项（从 `loadForm` 的 field 定义中解析）。 */
data class TranscriptTypeOption(
    val name: String,   // 显示名称，如 "本科生中文成绩单"
    val value: Int,     // 选项值，如 0
    val cancelled: Boolean = false  // 是否已取消
)

/** 表单上下文 — 包含后续操作所需的全部状态。 */
data class FormContext(
    val workflowId: Int,
    val params: JsonObject,
    val submitParams: JsonObject,
    val maindata: JsonObject,
    val typeOptions: List<TranscriptTypeOption>,
    val linkageUUID: String,
    val signatureAttributesStr: String,
    val signatureSecretKey: String,
    val defaultDate: String,
    val defaultRequestName: String
)

/** 联动查询结果。 */
data class LinkageResult(
    val studentId: String,       // 学号 (field7237)
    val enrollYear: String,      // 入学年份 (field7536)
    val templatePath: String,    // CPT 模板路径 (field7247)
    val categoryName: String,    // 业务分类名 (field7241)
    val workflowIdField: String  // 流程 ID (field7245)
)

/** 提交结果。 */
data class SubmitResult(
    val requestId: Int,
    val sessionKey: String,
    val submitToken: Long
)

/** 下载信息。 */
data class DownloadInfo(
    val filename: String,
    val downloadUrl: String,
    val filesize: String
)

/** 成绩单的取数端口：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。 */
interface TranscriptSource {

    /** 本端这一份取数能不能走完那七步（出 PDF 那半条链：联动 → 生成 → 两次提交 → 签章 → 下载）。 */
    val canRunWorkflow: Boolean get() = true

    /** Step 1：加载创建表单（含成绩单类型选项、默认日期、签名参数）。 */
    suspend fun loadCreateForm(workflowId: Int): FormContext

    /** Step 2：联动查询 — 学号 / 入学年份 / 模板路径 / 业务分类名。 */
    suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult

    /** Step 3：先保存（创建流程 → 拿到 `requestId` 与 `sessionKey`）。成绩单文档 ID 此时留空。 */
    suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int
    ): SubmitResult

    /**
     * Step 4：按 [requestId] 生成成绩单文件（必须在申请单保存之后）。学校只回 "ok"，
     * 文档 ID 由学校写进申请单的 field7244，下一步重新加载时读。
     */
    suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int, requestId: Int)

    /** Step 5：重新加载 → 提交前校验 → 第二次提交（转发到下载节点）。 */
    suspend fun reloadAndForward(
        ctx: FormContext,
        firstResult: SubmitResult,
        typeValue: Int
    ): SubmitResult

    /** Step 6：取最终的文件下载链接。 */
    suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo

    /** Step 7：下载 PDF 二进制。 */
    suspend fun downloadPdf(url: String): ByteArray
}
