package com.xjtu.toolbox.dzpz

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [TranscriptSource] 在 `:data` 里的实现（Android 与桌面侧共用）—— 包住原来的
 * [TranscriptApi]（okhttp 抓 `dzpz.xjtu.edu.cn` 的工作流引擎）。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 14 条真数据路由：桌面要自己登 `dzpz` 站、自己走完
 * 生成-提交-签章-下载那七步），两件事各动一半：
 *
 * 1. **取数**（[TranscriptApi]）的类名与包路径一字未改，只是从 `:app/dzpz/` 挪到同包的
 *    `:data/dzpz/`，替掉的只有 `android.util.Log`；
 * 2. **调度器**：搬迁前是 ViewModel 每次调用自己 `withContext(Dispatchers.IO) { … }`，现在
 *    VM 不再替实现挑调度器（见 [TranscriptSource] 的 KDoc），所以由本类自己包 ——
 *    **同一层、同一个调度器，行为不变**。
 *
 * 会话仍然是 `:data` 的 [SiteSession]（`dzpz` 站点，见 `DzpzSession` / `DzpzLogin`）：
 * `userId` 从 `site.localToken["user_id"]` 取，与搬迁前逐字相同。
 *
 * 落盘那一步（把 PDF 写进系统下载目录）**不在这里**：它是宿主能力（Android = MediaStore +
 * 下载管理，桌面 = 写用户的下载目录），由宿主注入到屏上（`TranscriptScreen` 的 `savePdf` 槽位），
 * 与评教/校园卡那些「宿主能力走参数不走端口」的例子同型。
 */
class AppTranscriptSource(site: SiteSession) : TranscriptSource {

    private val api = TranscriptApi(site)

    override suspend fun loadCreateForm(workflowId: Int): FormContext =
        withContext(Dispatchers.IO) { api.loadCreateForm(workflowId) }

    override suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult =
        withContext(Dispatchers.IO) { api.getLinkageData(ctx, typeValue) }

    override suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int): String =
        withContext(Dispatchers.IO) { api.generatePreviewPdf(workflowId, typeValue) }

    override suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
        docId: String,
    ): SubmitResult =
        withContext(Dispatchers.IO) { api.submitCreate(ctx, linkage, typeValue, docId) }

    override suspend fun reloadAndForward(
        ctx: FormContext,
        firstResult: SubmitResult,
        typeValue: Int,
    ): SubmitResult =
        withContext(Dispatchers.IO) { api.reloadAndForward(ctx, firstResult, typeValue) }

    override suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo =
        withContext(Dispatchers.IO) { api.getDownloadInfo(secondResult) }

    override suspend fun downloadPdf(url: String): ByteArray =
        withContext(Dispatchers.IO) { api.downloadPdf(url) }
}
