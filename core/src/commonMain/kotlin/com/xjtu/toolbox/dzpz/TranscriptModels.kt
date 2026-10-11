package com.xjtu.toolbox.dzpz

import kotlinx.serialization.json.JsonObject

/**
 * 电子成绩单（`dzpz.xjtu.edu.cn`，工作流引擎里的 `workflowId = 29`）的**模型 + 取数端口**。
 *
 * ## 这几个类为什么在 `:core`
 *
 * 它们原来嵌在 `:app` 的 `TranscriptApi` 里（`TranscriptApi.FormContext` 那一族）。屏与
 * ViewModel 按五步法搬进 `:core` 之后，屏要认的就是**这一族形状**：表单上下文、联动结果、
 * 两次提交的结果、下载信息 —— 而 `:data` 那份 `TranscriptApi` 是这些形状的**生产者**。
 * 于是它们跟 `library/LibraryModels.kt`、`venue/VenueModels.kt` 走同一条路：形状进 `:core`，
 * 取数实现留在 `:data`（同一个包名 ⇒ 两边都不用加 import）。
 *
 * **口径一行未改**：字段名、类型、默认值、语义都与搬迁前逐字相同（唯一的变化是嵌套类
 * `TranscriptApi.X` 升级成同包的顶层声明 `X`）—— 调用点的 `TranscriptApi.` 前缀没了，
 * 名字本身没变。
 */

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

/**
 * 成绩单的**取数端口**：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。
 *
 * 七个方法就是 [TranscriptApi]（`:data`，两个端共用同一份实现）那七个步骤 —— 名称、参数、
 * 返回形状与搬迁前逐字相同，所以「流程」那半段（[TranscriptViewModel] 里那串
 * 学籍 → 生成 → 提交 → 签章 → 下载）在两端是同一条代码路径。
 *
 * ## 为什么 IO 调度不在端口上
 *
 * `:app` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`；Web 走挂起接口，
 * 不需要。VM 只在自己的可取消作用域里调用（与 `VenueSource` / `CampusCardSource` 同一条约定）。
 *
 * ## 两端各自的实现
 *
 * | 端 | 实现 | 备注 |
 * |---|---|---|
 * | Android / 桌面 | `:data` 的 `AppTranscriptSource`（包住 `TranscriptApi`） | 走自己的站点会话（`dzpz`） |
 * | Web | 还没有 | 屏搬好了，取数要等 campus-api 或 `:server` 的 `/api` 端点给出这条链（如实记在 `:web` 的未搬清单里） |
 */
interface TranscriptSource {

    /** Step 1：加载创建表单（含成绩单类型选项、默认日期、签名参数）。 */
    suspend fun loadCreateForm(workflowId: Int): FormContext

    /** Step 2：联动查询 — 学号 / 入学年份 / 模板路径 / 业务分类名。 */
    suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult

    /** Step 3：生成成绩单预览 PDF，返回文档 ID。 */
    suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int): String

    /** Step 4：第一次提交（创建流程 → 拿到 `requestId` 与 `sessionKey`）。 */
    suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
        docId: String
    ): SubmitResult

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
