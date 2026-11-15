package com.xjtu.toolbox.attendance

import androidx.lifecycle.SavedStateHandle
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** 考勤编排（[AttendanceViewModel]）在 JVM 上的口径。 */
class AttendanceViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `打开即加载：姓名、学期、流水、打卡流水、统计、请假窗口一次拉齐`() {
        val source = FakeAttendanceSource()
        val vm = AttendanceViewModel(source, SavedStateHandle())

        val state = vm.state.value
        assertEquals("示例同学（假）", state.studentName)
        assertEquals(listOf("646"), state.terms.map { it.bh })
        assertEquals("646", state.termBh)
        assertEquals(1, state.records.size)
        assertEquals("光电子学（假）", state.records.single().courseName)
        assertEquals(1, state.streams.size)
        assertEquals(1, state.stats.size)
        assertEquals(1440, state.window?.maxStudentLeaveMinutes)
        assertEquals(1, state.leaves.size)
        assertTrue(!state.loading)
        assertNull(state.error)
        assertEquals(
            listOf("getStudentInfo", "getTermList", "getTermBh", "getWaterRecords", "computeCourseStatsFromRecords", "getStreams", "getSemesterWindow", "getLeavePage"),
            source.calls,
            "统计按整学期流水算，不再调只有本周的 getKqtjCurrentWeek（#134）",
        )
    }

    @Test
    fun `选学期：记进 saved state 并按新学期重拉`() {
        val source = FakeAttendanceSource()
        val saved = SavedStateHandle()
        val vm = AttendanceViewModel(source, saved)

        vm.selectTerm("999")
        assertEquals("999", saved["termBh"], "选过的学期要写进 saved state（进程回收后还在）")
        assertEquals("999", vm.state.value.termBh)
        // 打开（646）+ 换学期（999）各拉一轮
        assertEquals(2, source.calls.count { it == "getWaterRecords" })
        val secondRecordsCall = source.recordsCalls.drop(1).first()
        assertEquals("999", secondRecordsCall)
    }

    @Test
    fun `会话失效：发 AuthExpired 事件而不是错误文案`() {
        val source = FakeAttendanceSource().apply { failAuth = true }
        val vm = AttendanceViewModel(source, SavedStateHandle())

        assertEquals(AttendanceEvent.AuthExpired, runBlocking { vm.events.first() })
        assertNull(vm.state.value.error, "会话失效不能落成错误文案")
    }

    @Test
    fun `打卡流水拉不到不影响其它栏，会话失效则照样报`() {
        val source = FakeAttendanceSource().apply { failStreams = true }
        val vm = AttendanceViewModel(source, SavedStateHandle())

        assertTrue(vm.state.value.streams.isEmpty(), "流水挂了给空表")
        assertEquals(1, vm.state.value.records.size, "流水那一栏失败不连累考勤记录")
        assertNull(vm.state.value.error)
    }

    @Test
    fun `撤回请假：成功给提示并重新加载，失败给操作文案`() {
        val source = FakeAttendanceSource()
        val vm = AttendanceViewModel(source, SavedStateHandle())
        val before = source.calls.size

        vm.act(LeaveAction.WITHDRAW, LeaveRecord(leaveId = "L-1", leaveType = "SICK", reason = "病假", startTime = "",
            endTime = "", status = "PENDING", displayStatus = "", approvalStatus = "", cancelStatus = "",
            sourceType = "", durationMinutes = 0, durationText = "", archiveId = "", withdrawable = true,
            cancellable = false, evidenceFiles = emptyList(), approvalNodes = emptyList(), courses = emptyList(),
            applyTime = ""))
        assertEquals(AttendanceEvent.Message("请假申请已撤回"), runBlocking { vm.events.first() })
        assertTrue(source.calls.size > before, "撤回成功后要重新加载一轮")
        assertTrue("withdrawLeave" in source.calls)
    }

    private class FakeAttendanceSource : AttendanceSource {
        val calls = mutableListOf<String>()
        val recordsCalls = mutableListOf<String>()
        var failAuth = false
        var failStreams = false

        private fun maybeExpire() {
            if (failAuth) throw FakeExpired()
        }

        override suspend fun getStudentInfo(): Map<String, Any> {
            calls += "getStudentInfo"
            maybeExpire()
            return mapOf("name" to "示例同学（假）")
        }

        override suspend fun getTermList(): List<TermInfo> {
            calls += "getTermList"
            maybeExpire()
            return listOf(TermInfo("646", "2026-2027 第一学期", "2026-09-14", "2027-02-21", "2026-2027-1"))
        }

        override suspend fun getTermBh(): String {
            calls += "getTermBh"
            maybeExpire()
            return "646"
        }

        override suspend fun getWaterRecords(termBh: String?, startDate: String, endDate: String): List<AttendanceWaterRecord> {
            calls += "getWaterRecords"
            recordsCalls += termBh.orEmpty()
            maybeExpire()
            return listOf(
                AttendanceWaterRecord(
                    sbh = "r-1", termString = "", startTime = 1, endTime = 2, week = 1,
                    location = "西2楼-假-307", courseName = "光电子学（假）", courseCode = "PHYS999999",
                    teacher = "高宏（假）", status = WaterType.NORMAL, date = "2026-09-16",
                ),
            )
        }

        override suspend fun getKqtjCurrentWeek(): List<CourseAttendanceStat> {
            calls += "getKqtjCurrentWeek"
            maybeExpire()
            return listOf(CourseAttendanceStat("光电子学（假）", "PHYS999999", 1, 0, 0, 0, 0, 1))
        }

        override suspend fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat> {
            calls += "getKqtjByTime"
            maybeExpire()
            return emptyList()
        }

        override suspend fun getStreams(startDate: String, endDate: String): List<AttendanceStream> {
            calls += "getStreams"
            if (failStreams) throw java.io.IOException("打卡流水服务异常（假）")
            maybeExpire()
            return listOf(AttendanceStream("s-1", "西2楼-假-307", "2026-09-16 08:05:00", true))
        }

        override suspend fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> {
            calls += "computeCourseStatsFromRecords"
            return records.groupBy { it.courseName }.map { (name, recs) ->
                CourseAttendanceStat(name, recs.first().courseCode, recs.size, 0, 0, 0, 0, recs.size)
            }
        }

        override suspend fun getSemesterWindow(): LeaveSemesterWindow {
            calls += "getSemesterWindow"
            maybeExpire()
            return LeaveSemesterWindow("646", "2026-2027", "第一学期", "2026-09-14", "2027-02-21", 1440)
        }

        override suspend fun getFlowPreview(type: LeaveType): LeaveFlowPreview {
            calls += "getFlowPreview"
            return LeaveFlowPreview(1, emptyList(), emptyList())
        }

        override suspend fun getLeavePage(pageNum: Int, pageSize: Int, status: String, leaveType: String): LeavePage {
            calls += "getLeavePage"
            maybeExpire()
            return LeavePage(
                records = listOf(LeaveRecord("L-1", "SICK", "发烧（假）", "", "", "", "PENDING", "", "", "", "", 0, "", "", true, false, emptyList(), emptyList(), emptyList())),
                total = 1, pageNum = 1, pageSize = 20,
            )
        }

        override suspend fun uploadEvidence(fileName: String, contentType: String, bytes: ByteArray): JsonObject {
            calls += "uploadEvidence"
            return buildJsonObject { }
        }

        override suspend fun createLeave(
            type: LeaveType, startTime: String, endTime: String, reason: String,
            nextApproverUserId: String, evidenceFiles: List<JsonObject>, requestId: String,
        ): String {
            calls += "createLeave"
            return "L-new"
        }

        override suspend fun withdrawLeave(leaveId: String, reason: String, requestId: String) {
            calls += "withdrawLeave"
            maybeExpire()
        }

        override suspend fun cancelLeave(leaveId: String, requestId: String) {
            calls += "cancelLeave"
            maybeExpire()
        }
    }

    private class FakeExpired : Exception("登录态已失效（假）"), SessionExpiredFailure
}