package com.xjtu.toolbox.attendance

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.newattendance.NewAttendanceApi

/**
 * 考勤数据的稳定接口。课表徽章、首页统计卡、Agent 工具、课表源这些外部消费者
 * 都只认这个接口，不直接依赖 [NewAttendanceApi] 的具体实现。
 *
 * 起这层的原因：新版考勤（kq.xjtu.edu.cn）刚从旧版（bkkq/yjskq，域名已失效）切过来，
 * 稳不稳还没经过足够验证；万一它自己又出岔子需要换一版实现，只用改 [attendanceProvider]
 * 这一处工厂函数，四个消费者不用跟着动。
 */
interface AttendanceProvider {
    fun getTermList(): List<TermInfo>
    fun getTermBh(): String
    fun getWaterRecords(termBh: String? = null, startDate: String = "", endDate: String = ""): List<AttendanceWaterRecord>
    fun getKqtjCurrentWeek(): List<CourseAttendanceStat>
    fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat>
}

/** 唯一的构造入口。site 应当是 `ensureSite(LoginType.NEW_ATTENDANCE)` 拿到的会话。 */
fun attendanceProvider(site: SiteSession): AttendanceProvider = NewAttendanceApi(site)
