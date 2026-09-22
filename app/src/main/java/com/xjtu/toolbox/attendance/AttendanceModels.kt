package com.xjtu.toolbox.attendance

/**
 * 考勤状态
 *
 * 这几个数据类原来跟旧版考勤（bkkq/yjskq，域名已停用）的网络调用代码挤在同一个文件里；
 * 新版考勤（newattendance 包，kq.xjtu.edu.cn）复用的正是这几个类型，所以旧版下线时
 * 把它们单独挪出来，新旧版都不用改一行引用（包名没变，只是搬了文件）。
 */
enum class WaterType(val value: Int) {
    NORMAL(1),     // 正常
    LATE(2),       // 迟到
    ABSENCE(3),    // 缺勤
    LEAVE(5),      // 请假
    /**
     * 接口返回了未识别的状态码。不能落到 [NORMAL]——那会把说不清的记录悄悄算成
     * 正常出勤，统计数字看着很漂亮但是假的。UI/统计都要显式处理这个分支。
     */
    UNKNOWN(-1);

    val displayName: String
        get() = when (this) {
            NORMAL -> "正常"
            LATE -> "迟到"
            ABSENCE -> "缺勤"
            LEAVE -> "请假"
            UNKNOWN -> "未知"
        }

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v } ?: UNKNOWN

        fun fromCode(code: String?): WaterType = when (code?.trim()?.uppercase()) {
            "NORMAL", "PRESENT", "1" -> NORMAL
            "LATE", "2" -> LATE
            "ABSENCE", "ABSENT", "3" -> ABSENCE
            "LEAVE", "5" -> LEAVE
            else -> UNKNOWN
        }
    }
}

/**
 * 考勤流水记录（已结束的课程）
 */
data class AttendanceWaterRecord(
    val sbh: String,
    val termString: String,
    val startTime: Int,
    val endTime: Int,
    val week: Int,
    val location: String,
    val courseName: String,
    /**
     * 学校课程号，与教务课表的 `courseCode` 是同一个编码（实测 `PHYS405309` 两边逐字相同）。
     * 用它跟课表关联比用课程名可靠：课名带「（甲）」「(实验)」后缀时两边写法未必一致。
     */
    val courseCode: String,
    val teacher: String,
    val status: WaterType,
    val date: String
) {
    /**
     * 磁盘缓存反序列化兜底，原理见 [com.xjtu.toolbox.schedule.CourseItem.sanitized]。
     * 这条路径的消费点（[com.xjtu.toolbox.schedule.CourseLinks]）没有任何 try/catch，
     * 缺字段的旧缓存一读就是未捕获 NPE，风险高于其它同类场景。
     */
    fun sanitized(): AttendanceWaterRecord = copy(
        sbh = (sbh as String?) ?: "",
        termString = (termString as String?) ?: "",
        location = (location as String?) ?: "",
        courseName = (courseName as String?) ?: "",
        courseCode = (courseCode as String?) ?: "",
        teacher = (teacher as String?) ?: "",
        status = (status as WaterType?) ?: WaterType.UNKNOWN,
        date = (date as String?) ?: "",
    )
}

/**
 * 打卡流水（考勤记录之外的原始刷卡数据）
 */
data class AttendanceStream(
    val id: String,
    val location: String,
    val collectTime: String,
    val effective: Boolean,
)

/**
 * 学期信息
 */
data class TermInfo(
    val bh: String,
    /** 人类可读展示名，如"2026-2027 第一学期"，仅供 UI 展示，不参与匹配。 */
    val name: String,
    val startDate: String = "",
    val endDate: String = "",
    /** 学期总周数。0 表示接口没给，按周拉课表时不能用。 */
    val weeks: Int = 0,
    /**
     * 教务风格的学期码，如"2026-2027-1"，与 jwxt 课表模块的 termCode 同格式，
     * 用于跨模块匹配（[com.xjtu.toolbox.schedule.CourseLinks] 靠它对齐考勤与课表）。
     * 为空表示无法从接口字段推出年份/学期序号。
     */
    val code: String = "",
)

/**
 * 按课程的考勤统计
 */
data class CourseAttendanceStat(
    val subjectName: String,
    val subjectCode: String,
    val normalCount: Int,
    val lateCount: Int,
    val absenceCount: Int,
    val leaveEarlyCount: Int,
    val leaveCount: Int,
    val total: Int
) {
    val actualCount: Int get() = normalCount + leaveCount
    val abnormalCount: Int get() = lateCount + absenceCount
}
