package com.xjtu.toolbox.dzpz

import androidx.compose.runtime.getValue
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 电子凭证：按身份加载申请表，一键走完生成、提交、签章、下载；整个流程不随界面重建中断。 */
internal class TranscriptViewModel(
    private val source: TranscriptSource,
    private val document: DzpzDocument,
) : ViewModel() {
    private val authExpiredChannel = Channel<Unit>(Channel.CONFLATED)
    val authExpired = authExpiredChannel.receiveAsFlow()

    var isLoading by mutableStateOf(true); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var formContext by mutableStateOf<FormContext?>(null); private set
    var selectedTypeIndex by mutableIntStateOf(0)

    var workflowState by mutableStateOf(WorkflowState.IDLE); private set
    var workflowProgress by mutableStateOf(""); private set
    var downloadInfo by mutableStateOf<DownloadInfo?>(null); private set
    var pdfBytes by mutableStateOf<ByteArray?>(null); private set

    init { loadForm() }

    fun loadForm() {
        val workflowId = document.workflowId
        isLoading = true
        errorMessage = null
        workflowState = WorkflowState.IDLE
        downloadInfo = null
        pdfBytes = null
        viewModelScope.launch {
            try {
                formContext = source.loadCreateForm(workflowId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) authExpiredChannel.send(Unit)
                else errorMessage = FriendlyError.of(e, "加载")
            } finally {
                isLoading = false
            }
        }
    }

    fun start() {
        val ctx = formContext ?: return
        val type = ctx.typeOptions.getOrNull(selectedTypeIndex)?.value ?: return
        if (workflowState == WorkflowState.RUNNING) return
        workflowState = WorkflowState.RUNNING
        viewModelScope.launch {
            try {
                suspend fun <T> step(label: String, block: suspend () -> T): T {
                    workflowProgress = label
                    return block()
                }
                val linkage = step("正在获取学籍信息...") { source.getLinkageData(ctx, type) }
                val docId = step("正在生成成绩单...") { source.generatePreviewPdf(ctx.workflowId, type) }
                val first = step("正在提交申请...") { source.submitCreate(ctx, linkage, type, docId) }
                val second = step("正在处理签章...") { source.reloadAndForward(ctx, first, type) }
                val info = step("正在获取下载链接...") { source.getDownloadInfo(second) }
                downloadInfo = info
                pdfBytes = step("正在下载成绩单...") { source.downloadPdf(info.downloadUrl) }
                workflowState = WorkflowState.SUCCESS
                workflowProgress = "成绩单已生成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpiredChannel.send(Unit)
                } else {
                    workflowState = WorkflowState.ERROR
                    workflowProgress = FriendlyError.of(e, "提交申请")
                }
            }
        }
    }
}
