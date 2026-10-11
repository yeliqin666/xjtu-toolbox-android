package com.xjtu.toolbox.attendance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AttendanceUiState(
    val studentName: String = "",
    val terms: List<TermInfo> = emptyList(),
    val termBh: String = "",
    val records: List<AttendanceWaterRecord> = emptyList(),
    val streams: List<AttendanceStream> = emptyList(),
    val stats: List<CourseAttendanceStat> = emptyList(),
    val window: LeaveSemesterWindow? = null,
    val leaves: List<LeaveRecord> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val hasData get() = records.isNotEmpty() || leaves.isNotEmpty()
}

sealed interface AttendanceEvent {
    data object AuthExpired : AttendanceEvent
    data class Message(val text: String) : AttendanceEvent
}

enum class LeaveAction { WITHDRAW, CANCEL }

/**
 * 考勤（`kq.xjtu.edu.cn`）：姓名 / 学期 / 流水 / 打卡流水 / 统计 / 请假六件事的编排。
 *
 * 从 `:app` 的 `attendance/AttendanceViewModel.kt` 搬进 `:core`（桌面端第 16 条真数据路由），
 * **编排逻辑一行未改**（加载顺序、选学期重载、拉不到打卡流水不连累其它栏、撤回/销假的成败文案），
 * 只换了三处「住址」：
 *
 *  - 取数从 `AttendanceApi(site)` + `LeaveApi(site)` 换成端口 [AttendanceSource]（Android / 桌面 =
 *    `:data` 的 `AppAttendanceSource`）—— VM 不再认识 `SiteSession`，也不再自己挑 `Dispatchers.IO`；
 *  - 会话失效由 `:core` 的标记接口 [SessionExpiredFailure] 认领（`:app`/`:data` 的
 *    `AuthExpiredException` 实现了它）——`catch` 抓不了接口，先抓 `Exception` 再判，与评教/成绩同一条缝；
 *  - 学期选择的记忆（[saved]）由屏传入 `SavedStateHandle`（Android = `createSavedStateHandle()`，
 *    与搬迁前同一句；桌面/Web = 内存版）。
 *
 * `state` / `events` 的字段与类型一字未改。
 */
class AttendanceViewModel(
    private val source: AttendanceSource,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val mutable = MutableStateFlow(AttendanceUiState(termBh = saved[TERM_KEY] ?: ""))
    val state = mutable.asStateFlow()
    private val eventChannel = Channel<AttendanceEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var loadJob: Job? = null

    init { load() }

    fun selectTerm(bh: String) {
        saved[TERM_KEY] = bh
        mutable.update { it.copy(termBh = bh) }
        load()
    }

    fun load(fromPull: Boolean = false) {
        loadJob?.cancel()
        mutable.update { if (fromPull) it.copy(refreshing = true, error = null) else it.copy(loading = true, error = null) }
        val requested = mutable.value.termBh
        loadJob = viewModelScope.launch {
            try {
                val next = fetch(requested)
                saved[TERM_KEY] = next.termBh
                mutable.value = next
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) eventChannel.send(AttendanceEvent.AuthExpired)
                else mutable.update { it.copy(error = FriendlyError.of(e, "加载考勤")) }
            } finally {
                mutable.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    private suspend fun fetch(requestedBh: String): AttendanceUiState {
        val name = source.getStudentInfo()["name"] as? String ?: ""
        val terms = source.getTermList()
        val bh = requestedBh.ifBlank { source.getTermBh() }
        val term = terms.firstOrNull { it.bh == bh }
        val records = source.getWaterRecords(bh, term?.startDate.orEmpty(), term?.endDate.orEmpty())
        val stats = try {
            if (bh == source.getTermBh()) source.getKqtjCurrentWeek() else source.computeCourseStatsFromRecords(records)
        } catch (_: Exception) {
            source.computeCourseStatsFromRecords(records)
        }
        // 打卡流水只是原始刷卡数据，拉不到不影响其它栏
        val streams = try {
            source.getStreams(term?.startDate.orEmpty(), term?.endDate.orEmpty())
        } catch (e: Exception) {
            if (e is SessionExpiredFailure) throw e
            emptyList()
        }
        return AttendanceUiState(
            studentName = name,
            terms = terms,
            termBh = bh,
            records = records,
            streams = streams,
            stats = stats,
            window = runCatching { source.getSemesterWindow() }.getOrNull(),
            leaves = source.getLeavePage().records,
        )
    }

    fun act(action: LeaveAction, record: LeaveRecord) {
        viewModelScope.launch {
            try {
                when (action) {
                    LeaveAction.WITHDRAW -> source.withdrawLeave(record.leaveId, "学生撤回")
                    LeaveAction.CANCEL -> source.cancelLeave(record.leaveId)
                }
                eventChannel.send(AttendanceEvent.Message(if (action == LeaveAction.WITHDRAW) "请假申请已撤回" else "已提交销假申请"))
                load()
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) eventChannel.send(AttendanceEvent.AuthExpired)
                else eventChannel.send(AttendanceEvent.Message(FriendlyError.of(e, "操作")))
            }
        }
    }

    fun submitted() {
        viewModelScope.launch { eventChannel.send(AttendanceEvent.Message("请假申请已提交")) }
        load()
    }

    private companion object {
        const val TERM_KEY = "termBh"
    }
}