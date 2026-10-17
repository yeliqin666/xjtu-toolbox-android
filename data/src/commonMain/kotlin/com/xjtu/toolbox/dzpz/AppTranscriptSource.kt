package com.xjtu.toolbox.dzpz

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `:core` 的 [TranscriptSource] 在 `:data` 里的实现（Android 与桌面侧共用） */
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
