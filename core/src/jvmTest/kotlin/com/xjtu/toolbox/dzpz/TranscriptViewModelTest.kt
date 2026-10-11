package com.xjtu.toolbox.dzpz

import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 成绩单**流程**（[TranscriptViewModel]）在 JVM 上的口径 —— 搬屏之前先钉住，搬完一个字不改。
 *
 * ## 它钉的是什么
 *
 * 屏搬进 `:core` 时，取数从 `TranscriptApi(site)` 换成了 [TranscriptSource] 端口，编排逻辑
 * （七步的顺序、每步亮出来的进度文案、成功与失败落到哪个状态）**一行未改**。这个类就用一个
 * 记账用的假 [TranscriptSource] 把那七步的顺序与参数钉住：
 *
 * ```
 * getLinkageData → generatePreviewPdf → submitCreate → reloadAndForward → getDownloadInfo → downloadPdf
 * ```
 *
 * 断言里那些进度文案（「正在获取学籍信息...」那一串）是**搬迁前 ViewModel 里的原文**
 * —— 它们会显示在屏上，所以它们是行为的一部分，不是可以随便改的实现细节。
 *
 * ## 为什么在 jvmTest
 *
 * `viewModelScope` 要一个 Main 调度器，`Dispatchers.setMain` 只在 JVM 侧稳定（wasm 上没有
 * 「主线程调度器」这一档）。所以这个类与 `LibraryFavoritesJvmTest` 一起留在 `core/src/jvmTest`。
 */
class TranscriptViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `加载表单：成功后拿到类型选项，失败落在 errorMessage 上`() {
        val source = FakeTranscriptSource()
        val vm = TranscriptViewModel(source, DzpzDocuments.TRANSCRIPT)

        assertEquals(1, source.formCalls.size)
        assertEquals(DzpzDocuments.TRANSCRIPT.workflowId, source.formCalls.single())
        assertNotNull(vm.formContext)
        assertEquals(listOf("本科生中文成绩单（假）"), vm.formContext!!.typeOptions.map { it.name })
        assertTrue(!vm.isLoading, "加载完应当退出 loading")
        assertNull(vm.errorMessage)
    }

    @Test
    fun `加载表单：会话失效走 authExpired 那条流，不写成错误文案`() {
        val source = FakeTranscriptSource().apply { failLoadWith = FakeSessionExpired() }
        val vm = TranscriptViewModel(source, DzpzDocuments.TRANSCRIPT)

        assertNull(vm.errorMessage, "会话失效不是「加载失败」，交给导航层去重登")
        assertNull(vm.formContext)
        val emitted = runBlocking { withTimeout0(vm) }
        assertTrue(emitted, "authExpired 应当发出一枪")
    }

    @Test
    fun `加载表单：别的异常落成错误文案，可重试`() {
        val source = FakeTranscriptSource().apply { failLoadWith = IllegalStateException("boom") }
        val vm = TranscriptViewModel(source, DzpzDocuments.TRANSCRIPT)

        assertNotNull(vm.errorMessage)
        assertNull(vm.formContext)

        // 重试：这一次让上游好起来，同一个 VM 上再调一次 loadForm
        source.failLoadWith = null
        vm.loadForm()
        assertNotNull(vm.formContext)
        assertNull(vm.errorMessage)
    }

    @Test
    fun `一键申请：七步按序走完，成功态拿到下载信息与字节`() {
        val source = FakeTranscriptSource()
        val vm = TranscriptViewModel(source, DzpzDocuments.TRANSCRIPT)
        val ctx = vm.formContext!!
        val type = ctx.typeOptions.single().value

        vm.start()

        assertEquals(
            listOf(
                "getLinkageData($type)",
                "generatePreviewPdf(${ctx.workflowId}, $type)",
                "submitCreate($type, docId=9001)",
                "reloadAndForward($type, first=555)",
                "getDownloadInfo(second=555)",
                "downloadPdf(https://dzpz.xjtu.edu.cn/weaver/file/download?fid=9001)",
            ),
            source.calls,
        )
        assertEquals(WorkflowState.SUCCESS, vm.workflowState)
        assertEquals("成绩单已生成", vm.workflowProgress)
        assertEquals("成绩单（假）.pdf", vm.downloadInfo!!.filename)
        assertEquals("PDF-假".toByteArray().toList(), vm.pdfBytes!!.toList())
    }

    @Test
    fun `一键申请：中途失败落到 ERROR 态，进度停在那一句上`() {
        val source = FakeTranscriptSource().apply { failSubmitWith = IllegalStateException("提交被拒绝") }
        val vm = TranscriptViewModel(source, DzpzDocuments.TRANSCRIPT)

        vm.start()

        assertEquals(WorkflowState.ERROR, vm.workflowState)
        // 失败的那一步是 submitCreate（它记完账才抛），它后面的三步一步都没走到
        assertEquals(
            listOf("getLinkageData(0)", "generatePreviewPdf(29, 0)", "submitCreate(0, docId=9001)"),
            source.calls,
        )
        // 文案来自 FriendlyError（不是异常原文），且停在失败那一步的标签上
        assertNotNull(vm.workflowProgress)
        assertNull(vm.downloadInfo)
        assertNull(vm.pdfBytes)
    }

    @Test
    fun `一键申请：会话失效走 authExpired 那条流，状态不停在错误态`() {
        val source = FakeTranscriptSource().apply { failReloadWith = FakeSessionExpired() }
        val vm = TranscriptViewModel(source, DzpzDocuments.TRANSCRIPT)

        vm.start()

        assertEquals(WorkflowState.RUNNING, vm.workflowState, "交给导航层重登，不写成失败")
        assertTrue(runBlocking { withTimeout0(vm) }, "authExpired 应当发出一枪")
    }

    /**
 * 「站点登录态已失效」的假异常：只实现 `:core` 的标记接口（`:app` 的 `AuthExpiredException`
 * 与 `:data` 的 `SessionExpiredException` 都实现了它，而 `:core` 看不见那两个类）。
 */
private class FakeSessionExpired : RuntimeException("登录态已失效"), SessionExpiredFailure

/** 收一枪 `authExpired`（CONFLATED 通道；用单测调度器时它已经发出来了）。 */
    private suspend fun withTimeout0(vm: TranscriptViewModel): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(1_000L) { vm.authExpired.first() } != null
}

/**
 * 记账用的假取数实现：把七步的**调用顺序与参数**记下来，并允许在某一步注入失败。
 *
 * 它不碰网络、不碰 Android —— 它存在的意义只有一个：让「搬屏时流程一行没改」这句话可验证。
 */
private class FakeTranscriptSource : TranscriptSource {

    val calls: MutableList<String> = mutableListOf()
    val formCalls: MutableList<Int> = mutableListOf()
    var failLoadWith: Throwable? = null
    var failSubmitWith: Throwable? = null
    var failReloadWith: Throwable? = null

    private val empty = JsonObject(emptyMap())
    private val form = FormContext(
        workflowId = DzpzDocuments.TRANSCRIPT.workflowId,
        params = empty,
        submitParams = empty,
        maindata = JsonObject(mapOf("field7249" to JsonObject(mapOf("value" to JsonPrimitive("2026-10-11"))))),
        typeOptions = listOf(TranscriptTypeOption("本科生中文成绩单（假）", 0)),
        linkageUUID = "LG-假",
        signatureAttributesStr = "",
        signatureSecretKey = "",
        defaultDate = "2026-10-11",
        defaultRequestName = "成绩单申请（假）",
    )

    override suspend fun loadCreateForm(workflowId: Int): FormContext {
        formCalls += workflowId
        failLoadWith?.let { throw it }
        return form
    }

    override suspend fun getLinkageData(ctx: FormContext, typeValue: Int): LinkageResult {
        calls += "getLinkageData($typeValue)"
        return LinkageResult("2021000001", "2021", "/CPT/假.cpt", "电子证明（假）", ctx.workflowId.toString())
    }

    override suspend fun generatePreviewPdf(workflowId: Int, typeValue: Int): String {
        calls += "generatePreviewPdf($workflowId, $typeValue)"
        return "9001"
    }

    override suspend fun submitCreate(
        ctx: FormContext,
        linkage: LinkageResult,
        typeValue: Int,
        docId: String,
    ): SubmitResult {
        calls += "submitCreate($typeValue, docId=$docId)"
        failSubmitWith?.let { throw it }
        return SubmitResult(555, "SK-假-1", 1L)
    }

    override suspend fun reloadAndForward(
        ctx: FormContext,
        firstResult: SubmitResult,
        typeValue: Int,
    ): SubmitResult {
        calls += "reloadAndForward($typeValue, first=${firstResult.requestId})"
        failReloadWith?.let { throw it }
        return SubmitResult(555, "SK-假-2", 2L)
    }

    override suspend fun getDownloadInfo(secondResult: SubmitResult): DownloadInfo {
        calls += "getDownloadInfo(second=${secondResult.requestId})"
        return DownloadInfo("成绩单（假）.pdf", "https://dzpz.xjtu.edu.cn/weaver/file/download?fid=9001", "12.3 KB")
    }

    override suspend fun downloadPdf(url: String): ByteArray {
        calls += "downloadPdf($url)"
        return "PDF-假".toByteArray()
    }
}
