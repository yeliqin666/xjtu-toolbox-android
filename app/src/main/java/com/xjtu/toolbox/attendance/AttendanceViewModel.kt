package com.xjtu.toolbox.attendance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

class AttendanceViewModel(site: SiteSession, private val saved: SavedStateHandle) : ViewModel() {
    private val api = AttendanceApi(site)
    val leaveApi = LeaveApi(site)

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
                val next = withContext(Dispatchers.IO) { fetch(requested) }
                saved[TERM_KEY] = next.termBh
                mutable.value = next
            } catch (_: AuthExpiredException) {
                eventChannel.send(AttendanceEvent.AuthExpired)
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "加载失败") }
            } finally {
                mutable.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    private suspend fun fetch(requestedBh: String): AttendanceUiState {
        val name = api.getStudentInfo()["name"] as? String ?: ""
        val terms = api.getTermList()
        val bh = requestedBh.ifBlank { api.getTermBh() }
        val term = terms.firstOrNull { it.bh == bh }
        val records = api.getWaterRecords(bh, term?.startDate.orEmpty(), term?.endDate.orEmpty())
        val stats = try {
            if (bh == api.getTermBh()) api.getKqtjCurrentWeek() else api.computeCourseStatsFromRecords(records)
        } catch (_: Exception) {
            api.computeCourseStatsFromRecords(records)
        }
        // 打卡流水只是原始刷卡数据，拉不到不影响其它栏
        val streams = try {
            api.getStreams(term?.startDate.orEmpty(), term?.endDate.orEmpty())
        } catch (e: AuthExpiredException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return AttendanceUiState(
            studentName = name,
            terms = terms,
            termBh = bh,
            records = records,
            streams = streams,
            stats = stats,
            window = runCatching { leaveApi.getSemesterWindow() }.getOrNull(),
            leaves = leaveApi.getLeavePage().records,
        )
    }

    fun act(action: LeaveAction, record: LeaveRecord) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        LeaveAction.WITHDRAW -> leaveApi.withdrawLeave(record.leaveId, "学生撤回")
                        LeaveAction.CANCEL -> leaveApi.cancelLeave(record.leaveId)
                    }
                }
                eventChannel.send(AttendanceEvent.Message(if (action == LeaveAction.WITHDRAW) "请假申请已撤回" else "已提交销假申请"))
                load()
            } catch (_: AuthExpiredException) {
                eventChannel.send(AttendanceEvent.AuthExpired)
            } catch (e: Exception) {
                eventChannel.send(AttendanceEvent.Message(e.message ?: "操作失败"))
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
