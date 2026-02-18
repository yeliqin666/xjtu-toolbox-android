package com.xjtu.toolbox.attendance

import android.util.Log
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.AttendanceLogin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 考勤流水类型
 */
enum class FlowRecordType(val value: Int) {
    INVALID(0),   // 无效（教室没课但刷了卡）
    VALID(1),     // 有效
    REPEATED(2);  // 重复

    companion object {
        fun fromValue(v: Int) = entries.first { it.value == v }
    }
}

/**
 * 考勤状态
 */
enum class WaterType(val value: Int) {
    NORMAL(1),     // 正常
    LATE(2),       // 迟到
    ABSENCE(3),    // 缺勤
    LEAVE(5);      // 请假

    val displayName: String
        get() = when (this) {
            NORMAL -> "正常"
            LATE -> "迟到"
            ABSENCE -> "缺勤"
            LEAVE -> "请假"
        }

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v } ?: NORMAL
    }
}

/**
 * 考勤打卡记录
 */
data class AttendanceFlow(
    val sbh: String,
    val place: String,
    val waterTime: String,
    val type: FlowRecordType
)

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
    val teacher: String,
    val status: WaterType,
    val date: String
)

/**
 * 学期信息
 */
data class TermInfo(
    val bh: String,
    val name: String,
    val startDate: String = "",
    val endDate: String = ""
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

/**
 * 考勤 API 封装
 */
class AttendanceApi(private val login: AttendanceLogin) {

    companion object {
        private const val TAG = "AttendanceApi"
    }

    private val baseUrl = "http://bkkq.xjtu.edu.cn"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val jsonType = "application/json".toMediaType()

    /**
     * 发送 POST 请求（与 Python _post 方法一致）
     * 自动处理 token 过期并重新认证
     */
    private fun post(path: String, jsonBody: String? = null): String {
        val url = "$baseUrl$path"
        val body = jsonBody?.toRequestBody(jsonType) ?: "".toRequestBody(null)
        val request = login.authenticatedRequest(url).post(body)
        val response = login.executeWithReAuth(request)
        val result = response.body?.string() ?: ""
        Log.d(TAG, "POST $path → ${response.code}, len=${result.length}")
        return result
    }

    /**
     * 获取学生信息
     * Python 端点: /attendance-student/global/getStuInfo
     */
    fun getStudentInfo(): Map<String, Any> {
        val result = post("/attendance-student/global/getStuInfo")
        val json = JsonParser.parseString(result).asJsonObject
        val data = json.getAsJsonObject("data")
        if (data == null) {
            Log.w(TAG, "getStudentInfo: data is null, full response=${result.take(500)}")
            return mapOf("name" to "", "sno" to "")
        }

        return mapOf(
            "name" to (data.get("name")?.asString ?: ""),
            "sno" to (data.get("sno")?.asString ?: data.get("account")?.asString ?: ""),
            "identity" to (data.get("identity")?.asString ?: ""),
            "campusName" to (data.get("campusName")?.asString ?: ""),
            "departmentName" to (data.get("departmentName")?.asString ?: "")
        )
    }

    /**
     * 获取当前学期编号
     * Python 端点: /attendance-student/global/getNearTerm
     */
    fun getTermBh(): String {
        val result = post("/attendance-student/global/getNearTerm")
        val json = JsonParser.parseString(result).asJsonObject
        val data = json.getAsJsonObject("data")
            ?: throw RuntimeException("getNearTerm: data 为空, response=$result")
        return data.get("bh")?.asString
            ?: data.get("bh")?.toString()
            ?: throw RuntimeException("getNearTerm: bh 字段缺失, data=$data")
    }

    /**
     * 获取所有学期列表
     * Python 端点: /attendance-student/global/getBeforeTodayTerm
     */
    fun getTermList(): List<TermInfo> {
        val result = post("/attendance-student/global/getBeforeTodayTerm")
        val json = JsonParser.parseString(result).asJsonObject
        val data = json.getAsJsonArray("data") ?: return emptyList()

        return data.map { item ->
            val obj = item.asJsonObject
            TermInfo(
                bh = obj.get("bh")?.asString ?: "",
                name = obj.get("name")?.asString ?: "",
                startDate = obj.get("startDate")?.asString
                    ?: obj.get("kssj")?.asString
                    ?: obj.get("startTime")?.asString ?: "",
                endDate = obj.get("endDate")?.asString
                    ?: obj.get("jssj")?.asString
                    ?: obj.get("endTime")?.asString ?: ""
            )
        }
    }

    /**
     * 查询某天的考勤打卡流水
     * Python 端点: /attendance-student/waterList/page
     */
    fun getFlowRecords(date: String? = null): List<AttendanceFlow> {
        val queryDate = date ?: dateFormat.format(Date())
        val jsonBody = """{"startdate":"$queryDate","enddate":"$queryDate","current":1,"pageSize":200,"calendarBh":""}"""
        val result = post("/attendance-student/waterList/page", jsonBody)
        val json = JsonParser.parseString(result).asJsonObject
        val dataObj = json.getAsJsonObject("data") ?: return emptyList()
        val list = dataObj.getAsJsonArray("list") ?: return emptyList()

        return list.map { item ->
            val obj = item.asJsonObject
            AttendanceFlow(
                sbh = obj.get("sBh")?.asString ?: "",
                place = obj.get("eqno")?.asString ?: "",
                waterTime = obj.get("watertime")?.asString ?: "",
                type = FlowRecordType.fromValue(obj.get("isdone")?.asInt ?: 0)
            )
        }
    }

    /**
     * 查询指定日期范围的打卡流水
     * Python 端点: /attendance-student/waterList/page
     */
    fun getFlowRecordsByRange(startDate: String, endDate: String): List<AttendanceFlow> {
        val jsonBody = """{"startdate":"$startDate","enddate":"$endDate","current":1,"pageSize":200,"calendarBh":""}"""
        val result = post("/attendance-student/waterList/page", jsonBody)
        val json = JsonParser.parseString(result).asJsonObject
        val dataObj = json.getAsJsonObject("data") ?: return emptyList()
        val list = dataObj.getAsJsonArray("list") ?: return emptyList()

        return list.map { item ->
            val obj = item.asJsonObject
            AttendanceFlow(
                sbh = obj.get("sBh")?.asString ?: "",
                place = obj.get("eqno")?.asString ?: "",
                waterTime = obj.get("watertime")?.asString ?: "",
                type = FlowRecordType.fromValue(obj.get("isdone")?.asInt ?: 0)
            )
        }
    }

    /**
     * 查询考勤统计（按学期）
     * Python 端点: /attendance-student/classWater/getClassWaterPage
     */
    fun getWaterRecords(termBh: String? = null): List<AttendanceWaterRecord> {
        val bh = termBh ?: getTermBh()
        val jsonBody = """{"startDate":"","endDate":"","current":1,"pageSize":500,"timeCondition":"","subjectBean":{"sCode":""},"classWaterBean":{"status":""},"classBean":{"termNo":"$bh"}}"""
        val result = post("/attendance-student/classWater/getClassWaterPage", jsonBody)
        val json = JsonParser.parseString(result).asJsonObject
        val dataObj = json.getAsJsonObject("data") ?: return emptyList()
        val list = dataObj.getAsJsonArray("list") ?: return emptyList()

        return list.map { item ->
            val obj = item.asJsonObject
            val classWater = obj.getAsJsonObject("classWaterBean")
            val account = obj.getAsJsonObject("accountBean")
            val build = obj.getAsJsonObject("buildBean")
            val room = obj.getAsJsonObject("roomBean")
            val calendar = obj.getAsJsonObject("calendarBean")
            val subject = obj.getAsJsonObject("subjectBean")

            AttendanceWaterRecord(
                sbh = classWater?.get("bh")?.asString ?: "",
                termString = calendar?.get("name")?.asString ?: "",
                startTime = account?.get("startJc")?.asInt ?: 0,
                endTime = account?.get("endJc")?.asInt ?: 0,
                week = account?.get("week")?.asInt ?: 0,
                location = "${build?.get("name")?.asString ?: ""}-${room?.get("roomnum")?.asString ?: ""}",
                courseName = subject?.get("sName")?.asString
                    ?: subject?.get("subjectname")?.asString ?: "",
                teacher = obj.get("teachNameList")?.asString ?: "",
                status = WaterType.fromValue(classWater?.get("status")?.asInt ?: 1),
                date = account?.get("checkdate")?.asString ?: ""
            )
        }
    }

    /**
     * 本周按课程考勤统计
     * Python 端点: /attendance-student/kqtj/getKqtjCurrentWeek
     */
    fun getKqtjCurrentWeek(): List<CourseAttendanceStat> {
        val result = post("/attendance-student/kqtj/getKqtjCurrentWeek")
        return parseKqtjList(result)
    }

    /**
     * 按时间段查每门课的考勤统计
     * Python 端点: /attendance-student/kqtj/getKqtjByTime
     */
    fun getKqtjByTime(startDate: String, endDate: String): List<CourseAttendanceStat> {
        val jsonBody = """{"startDate":"$startDate","endDate":"$endDate 23:59:59"}"""
        val result = post("/attendance-student/kqtj/getKqtjByTime", jsonBody)
        return parseKqtjList(result)
    }

    /**
     * 从考勤记录中直接计算课程统计（当 API 返回为空时的回退方案）
     */
    fun computeCourseStatsFromRecords(records: List<AttendanceWaterRecord>): List<CourseAttendanceStat> {
        if (records.isEmpty()) return emptyList()
        return records.groupBy { it.courseName }
            .filter { it.key.isNotEmpty() }
            .map { (name, recs) ->
                CourseAttendanceStat(
                    subjectName = name,
                    subjectCode = "",
                    normalCount = recs.count { it.status == WaterType.NORMAL },
                    lateCount = recs.count { it.status == WaterType.LATE },
                    absenceCount = recs.count { it.status == WaterType.ABSENCE },
                    leaveEarlyCount = 0,
                    leaveCount = recs.count { it.status == WaterType.LEAVE },
                    total = recs.size
                )
            }
    }

    private fun parseKqtjList(result: String): List<CourseAttendanceStat> {
        val json = JsonParser.parseString(result).asJsonObject
        val data = json.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item.asJsonObject
            val name = obj.get("subjectname")?.asString ?: return@mapNotNull null
            CourseAttendanceStat(
                subjectName = name,
                subjectCode = obj.get("subjectCode")?.asString ?: "",
                normalCount = obj.get("normalCount")?.asInt ?: 0,
                lateCount = obj.get("lateCount")?.asInt ?: 0,
                absenceCount = obj.get("absenceCount")?.asInt ?: 0,
                leaveEarlyCount = obj.get("leaveEarlyCount")?.asInt ?: 0,
                leaveCount = obj.get("leaveCount")?.asInt ?: 0,
                total = obj.get("total")?.asInt ?: 0
            )
        }
    }
}
