package com.xjtu.toolbox.judge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.ensureSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 列表里一门课的展示内容。 */
internal data class JudgeCard(val key: String, val course: String, val teacher: String, val tag: String)

/** 评教的数据来源：本科走教务，研究生走 gste + gmis。 */
internal interface JudgeSource<Q> {
    /** 确认框里「将为 N 门课程全部提交好评」之后的话。 */
    val confirmText: String
    /** (未评, 已评)。 */
    suspend fun load(): Pair<List<Q>, List<Q>>
    fun card(q: Q): JudgeCard
    /** 一键评教前的准备（研究生要先拿一次学位课清单）。 */
    suspend fun prepare() {}
    suspend fun judge(q: Q)
    /** 能撤回时返回撤回函数，失败返回原因。 */
    val undo: (suspend (Q) -> String?)? get() = null
}

internal class UndergraduateJudgeSource(site: SiteSession, private val username: String) : JudgeSource<Questionnaire> {
    private val api = JudgeApi(site)
    override val confirmText get() = "，确定继续？"
    override suspend fun load() = api.unfinishedQuestionnaires() to api.finishedQuestionnaires()
    override fun card(q: Questionnaire) = JudgeCard(
        key = "${q.WJDM}_${q.JXBID}_${q.BPR}",
        course = q.KCM,
        teacher = q.BPJS,
        tag = when (q.PGLXDM) { "01" -> "期末评教"; "05" -> "过程评教"; else -> "评教" },
    )
    override suspend fun judge(q: Questionnaire) { api.submitQuestionnaire(q, api.autoFillQuestionnaire(q, username)) }
    override val undo: suspend (Questionnaire) -> String? = { q ->
        val (ok, msg) = api.editQuestionnaire(q, username)
        if (ok) null else msg
    }
}

/** 两个站都在用户进入本页时按需登录（允许弹短信验证），gmis 到一键评教时才登。 */
internal class GraduateJudgeSource(private val sessions: SessionManager) : JudgeSource<GraduateQuestionnaire> {
    private val mutex = Mutex()
    private var api: GraduateJudgeApi? = null
    private var degreeCourses: Set<String> = emptySet()

    private suspend fun api(): GraduateJudgeApi = mutex.withLock {
        api ?: GraduateJudgeApi(
            gste = sessions.ensureSite("gste", userInitiated = true),
            gmisProvider = { sessions.ensureSite("gmis", userInitiated = true) },
        ).also { api = it }
    }

    override val confirmText get() = "（系统不允许全部「优秀」，会有一项自动改为「良好」），确定继续？"
    override suspend fun load() = api().getQuestionnaires().let { all ->
        all.filter { it.assessment == "allow" } to all.filter { it.finished }
    }
    override fun card(q: GraduateQuestionnaire) = JudgeCard(
        key = q.key,
        course = q.kcmc,
        teacher = q.jsxm + if (q.skls_duty.isNotBlank()) "（${q.skls_duty}）" else "",
        tag = q.termname,
    )
    // 学位课 / 选修课整张成绩页取一次，所有问卷共用
    override suspend fun prepare() { degreeCourses = api().getDegreeCourseNames() }
    override suspend fun judge(q: GraduateQuestionnaire) = api().autoJudge(q, degreeCourses)
}

internal class JudgeViewModel<Q>(private val source: JudgeSource<Q>) : ViewModel() {
    var isLoading by mutableStateOf(true); private set
    var isRefreshing by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var unfinished by mutableStateOf<List<Q>>(emptyList()); private set
    var finished by mutableStateOf<List<Q>>(emptyList()); private set

    var isAutoJudging by mutableStateOf(false); private set
    var progress by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    /** 一键评教的进度 / 结果说明，用户可关掉。 */
    var autoJudgeMessage by mutableStateOf("")
    var undoingKey by mutableStateOf<String?>(null); private set

    val confirmText get() = source.confirmText
    val canUndo get() = source.undo != null
    fun card(q: Q) = source.card(q)

    private val authExpiredChannel = Channel<Unit>(Channel.CONFLATED)
    val authExpired = authExpiredChannel.receiveAsFlow()

    init { load() }

    /** [silent]：下拉刷新时保住当前列表，只转指示器。 */
    fun load(silent: Boolean = false) {
        if (silent) isRefreshing = true else isLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val (u, f) = withContext(Dispatchers.IO) { source.load() }
                unfinished = u
                finished = f
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpiredChannel.send(Unit)
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    fun autoJudgeAll() {
        if (isAutoJudging) return
        val list = unfinished
        isAutoJudging = true
        total = list.size
        progress = 0
        autoJudgeMessage = "正在准备..."
        viewModelScope.launch {
            var failed = 0
            var lastError = ""
            try {
                withContext(Dispatchers.IO) { source.prepare() }
                for ((index, q) in list.withIndex()) {
                    val name = source.card(q).course
                    autoJudgeMessage = "正在评教: $name (${index + 1}/${list.size})"
                    progress = index
                    try {
                        withContext(Dispatchers.IO) { source.judge(q) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        lastError = "$name: ${e.message}"
                    }
                    progress = index + 1
                    delay(300) // 间隔避免被限流
                }
                autoJudgeMessage = if (failed == 0) "全部评教完成！" else "${list.size - failed}门成功，${failed}门失败（$lastError）"
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                autoJudgeMessage = "评教出错: ${e.message}"
            } finally {
                isAutoJudging = false
            }
        }
    }

    fun undo(q: Q) {
        val undo = source.undo ?: return
        undoingKey = source.card(q).key
        viewModelScope.launch {
            try {
                val error = withContext(Dispatchers.IO) { undo(q) }
                if (error == null) load() else errorMessage = "撤回失败: $error"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "撤回失败: ${e.message}"
            } finally {
                undoingKey = null
            }
        }
    }
}
