package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.COLLEGE
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.COURSE_CODE
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.COURSE_NAME
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.LEAVE_ID
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.LEAVE_REASON
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.NAME
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.REQUEST_ID
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.ROOM
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.TERM_CURRENT_BH
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.TERM_CURRENT_CODE
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.TERM_LAST_CODE
import com.xjtu.toolbox.attendance.AttendanceFakeUpstream.Companion.TOKEN
import com.xjtu.toolbox.auth.AttendanceSession
import com.xjtu.toolbox.auth.withAttendanceLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 考勤取数的字段级口径：`AttendanceApi` + `LeaveApi`（以及它们包的 `:core` 端口 [AttendanceSource]）对着 `:testkit` 的 [AttendanceFakeUpstream] 逐行读。 */
class AttendanceApiJvmTest {

    @Test
    fun `登录：直连业务站那条链走完，业务令牌与站点根地址落进会话`() {
        withAttendanceLogin { site, fake ->
            assertEquals(TOKEN, site.localToken["business_token"], "业务令牌来自换票响应（onLoginSuccess）")
            assertEquals("https://bk-kq.xjtu.edu.cn/sa", site.localToken[AttendanceSession.BASE_URL_KEY], "根地址来自 resolvedBaseUrl（本科那套部署）")
            assertTrue(site.hasLogin, "真登录应当成功")

            // 换票真的发生：body 里带着 loginRequestId + ticket（CAS 签的 ST-）
            assertEquals(1, fake.attendance.exchangeBodies.size)
            assertTrue(REQUEST_ID in fake.attendance.exchangeBodies.single())
            assertTrue("\"ticket\":\"ST-" in fake.attendance.exchangeBodies.single())
            assertTrue(fake.library.credentialPosts.get() > 0, "应当真提交过一次凭据")
        }
    }

    @Test
    fun `学期与流水：按夹具字段读出（学期码、起止、状态映射、分页 body 带学期）`() {
        withAttendanceLogin { site, fake ->
            val api = AttendanceApi(site)

            val terms = runBlocking { api.getTermList() }
            assertEquals(2, terms.size)
            val current = terms.first()
            assertEquals(TERM_CURRENT_BH, current.bh)
            assertEquals(TERM_CURRENT_CODE, current.code)
            assertEquals("2026-09-14", current.startDate)
            assertEquals("2026-02-23", terms.last().startDate)
            assertEquals(TERM_LAST_CODE, terms.last().code)

            val records = runBlocking { api.getWaterRecords(TERM_CURRENT_BH, "2026-09-01", "2026-09-30") }
            assertEquals(2, records.size)
            val first = records.first()
            assertEquals(COURSE_NAME, first.courseName)
            assertEquals(COURSE_CODE, first.courseCode)
            assertEquals(ROOM, first.location)
            assertEquals(WaterType.LATE, first.status, "按日期降序，最近那条（09-18 迟到）在前")
            assertEquals(3, first.startTime)
            assertEquals(4, first.endTime)
            assertEquals(WaterType.NORMAL, records.last().status)
            // 分页请求 body 里带着学期与起止（fetchAttendanceRecords 的形状）
            val body = fake.attendance.recordsBodies.single()
            assertTrue(TERM_CURRENT_BH in body, "学期要带进分页 body")
            assertTrue("2026-09-01" in body && "2026-09-30" in body, "起止日期要带进分页 body")

            // 业务请求带着会话装饰器的两个头（decorateRequest）
            assertEquals(TOKEN, fake.attendance.lastBusinessToken)
            assertEquals("WEB", fake.attendance.lastSystemHeader)
        }
    }

    @Test
    fun `学生信息与打卡流水：姓名、学院与原始刷卡字段按夹具读出`() {
        withAttendanceLogin { site, fake ->
            val api = AttendanceApi(site)
            val info = runBlocking { api.getStudentInfo() }
            assertEquals(NAME, info["name"])
            assertEquals(COLLEGE, info["departmentName"])

            val streams = runBlocking { api.getStreams("2026-09-01", "2026-09-30") }
            assertEquals(1, streams.size)
            assertEquals(ROOM, streams.single().location)
            assertTrue(streams.single().effective)
        }
    }

    @Test
    fun `请假：窗口、流程预览、分页、提交、撤回的字段与请求形状按夹具钉住`() {
        withAttendanceLogin { site, fake ->
            val leave = LeaveApi(site)

            val window = runBlocking { leave.getSemesterWindow() }
            assertEquals(1440, window.maxStudentLeaveMinutes)
            assertEquals("2026-2027", window.academicYear)

            val preview = runBlocking { leave.getFlowPreview(LeaveType.SICK) }
            assertEquals(1, preview.nextApprovers.size)
            assertEquals("U-假-1001", preview.nextApprovers.single().userId)

            val page = runBlocking { leave.getLeavePage() }
            assertEquals(1, page.total)
            val rec = page.records.single()
            assertEquals(LEAVE_ID, rec.leaveId)
            assertEquals("SICK", rec.leaveType)
            assertEquals(LEAVE_REASON, rec.reason)
            assertEquals("PENDING", rec.effectiveStatus)
            assertTrue(rec.withdrawable)
            assertTrue(!rec.cancellable)

            val created = runBlocking {
                leave.createLeave(
                    type = LeaveType.SICK,
                    startTime = "2026-09-20 08:00:00",
                    endTime = "2026-09-20 18:00:00",
                    reason = LEAVE_REASON,
                    nextApproverUserId = "U-假-1001",
                    evidenceFiles = emptyList(),
                )
            }
            assertEquals(LEAVE_ID, created)
            assertTrue(fake.attendance.createLeaveBodies.single().contains("\"requestId\""), "幂等键要带（实现方补 UUID）")
            assertTrue(fake.attendance.createLeaveBodies.single().contains(LEAVE_REASON))

            runBlocking {
                leave.withdrawLeave(LEAVE_ID, "学生撤回")
                leave.cancelLeave(LEAVE_ID)
            }
        }
    }
}

