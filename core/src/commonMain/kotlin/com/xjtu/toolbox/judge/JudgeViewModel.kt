package com.xjtu.toolbox.judge

import androidx.compose.runtime.getValue
import com.xjtu.toolbox.error.FriendlyError
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 列表里一门课的展示内容。 */
data class JudgeCard(val key: String, val course: String, val teacher: String, val tag: String)

/** 评教的数据来源：本科走教务，研究生走 gste + gmis。 */
interface JudgeSource<Q> {
    /**
     * 这一端能不能**提交**评教。
     *
     * false 时屏上不出现「一键全部好评」与撤回按钮 —— 评教是写操作，Web 端
     * （campus-api 的 evaluations 模块是**只读**的，它自己也写明「永不实现提交/撤销评教」）
     * 只能看不能交，那就不要画一个点了会失败的按钮。:app 默认 true。
     */
    val canSubmit: Boolean get() = true

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

class JudgeViewModel<Q>(private val source: JudgeSource<Q>) : ViewModel() {
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
    /** 见 [JudgeSource.canSubmit]：false 时屏上不出现提交/撤回按钮。 */
    val canSubmit get() = source.canSubmit
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
                val (u, f) = source.load()
                unfinished = u
                finished = f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 会话失效判 :core 的标记接口（:app 的 AuthExpiredException 实现了它）——
                // `catch` 抓不了接口，所以先抓 Exception 再判。
                if (e is SessionExpiredFailure) authExpiredChannel.send(Unit)
                else errorMessage = FriendlyError.of(e, "加载")
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
                source.prepare()
                for ((index, q) in list.withIndex()) {
                    val name = source.card(q).course
                    autoJudgeMessage = "正在评教: $name (${index + 1}/${list.size})"
                    progress = index
                    try {
                        source.judge(q)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        lastError = "$name: ${FriendlyError.of(e, "评教")}"
                    }
                    progress = index + 1
                    delay(300) // 间隔避免被限流
                }
                autoJudgeMessage = if (failed == 0) "全部评教完成！" else "${list.size - failed}门成功，${failed}门失败（$lastError）"
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                autoJudgeMessage = FriendlyError.of(e, "评教")
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
                val error = undo(q)
                if (error == null) load() else errorMessage = "撤回失败: $error"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = FriendlyError.of(e, "撤回")
            } finally {
                undoingKey = null
            }
        }
    }
}
