package com.xjtu.toolbox.attendance

import android.util.Log
import com.google.gson.JsonElement
import com.xjtu.toolbox.auth.AttendanceLogin
import com.xjtu.toolbox.util.safeParseJsonObject
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

/** JsonElement 安全取 String（处理 JsonNull / 非字符串类型） */
private val JsonElement?.safeStr: String
    get() = if (this == null || this.isJsonNull) "" else try { asString } catch (_: Exception) { "" }

/** JsonElement 安全取 Int（处理 JsonNull / 非数字类型） */
private val JsonElement?.safeInt: Int
    get() = if (this == null || this.isJsonNull) 0 else try { asInt } catch (_: Exception) { 0 }

/**
 * 考勤 API 封装
 */
class AttendanceApi(private val login: AttendanceLogin) {

    companion object {
        private const val TAG = "AttendanceApi"
    }

    private val baseUrl: String get() = "https://${login.attendanceDomain}"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val jsonType = "application/json".toMediaType()

    /**
     * 发送 POST 请求（与 Python _post 方法一致）
     * 自动处理 token 过期并重新认证。
     * 考勤后端不稳定（瞬时 5xx / 超时 / 空响应常见），对可重试错误做有界重试。
     */
    private fun post(path: String, jsonBody: String? = null): String {
        val url = "$baseUrl$path"
        var lastError: Exception? = null
        for (attempt in 0..2) {
            if (attempt > 0) Thread.sleep(if (attempt == 1) 800L else 2_000L)
            try {
                val body = jsonBody?.toRequestBody(jsonType) ?: "".toRequestBody(null)
                val request = login.authenticatedRequest(url).post(body)
                val (code, result) = login.executeWithReAuth(request).use { response ->
                    response.code to (response.body?.string() ?: "")
                }
                Log.d(TAG, "POST $path → $code, len=${result.length} (attempt ${attempt + 1})")
                // 5xx / 空 body：学校网关瞬时故障，重试
                if (code in 500..599 || result.isBlank()) {
                    lastError = RuntimeException("考勤系统响应异常 (HTTP $code)")
                    continue
                }
                return result
            } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
                throw e // 认证失效：透传给 UI 层触发静默重登，重试无意义
            } catch (e: java.io.IOException) {
                Log.w(TAG, "POST $path attempt ${attempt + 1} failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("考勤系统请求失败")
    }

    /**
     * 获取学生信息
     * Python 端点: /attendance-student/global/getStuInfo
     */
    fun getStudentInfo(): Map<String, Any> {
        val result = post("/attendance-student/global/getStuInfo")
        val json = result.safeParseJsonObject()
        val dataEl = json.get("data")
        if (dataEl == null || dataEl.isJsonNull || !dataEl.isJsonObject) {
            Log.w(TAG, "getStudentInfo: data is null/missing, full response=${result.take(500)}")
            return mapOf("name" to "", "sno" to "")
        }
        val data = dataEl.asJsonObject

        return mapOf(
            "name" to data.get("name").safeStr,
            "sno" to data.get("sno").safeStr.ifEmpty { data.get("account").safeStr },
            "identity" to data.get("identity").safeStr,
            "campusName" to data.get("campusName").safeStr,
            "departmentName" to data.get("departmentName").safeStr
        )
    }

    /**
     * 获取当前学期编号
     * Python 端点: /attendance-student/global/getNearTerm
     */
    fun getTermBh(): String {
        val result = post("/attendance-student/global/getNearTerm")
        val json = result.safeParseJsonObject()
        val dataEl = json.get("data")
        val data = if (dataEl != null && dataEl.isJsonObject) dataEl.asJsonObject
            else throw RuntimeException("getNearTerm: data 为空, response=$result")
        return data.get("bh").safeStr.ifEmpty {
            data.get("bh")?.toString() ?: ""
        }.ifEmpty {
            throw RuntimeException("getNearTerm: bh 字段缺失, data=$data")
        }
    }

    /**
     * 获取所有学期列表
     * Python 端点: /attendance-student/global/getBeforeTodayTerm
     */
    fun getTermList(): List<TermInfo> {
        val result = post("/attendance-student/global/getBeforeTodayTerm")
        val json = result.safeParseJsonObject()
        val dataEl = json.get("data")
        if (dataEl == null || dataEl.isJsonNull || !dataEl.isJsonArray) return emptyList()
        val data = dataEl.asJsonArray

        return data.map { item ->
            val obj = item.asJsonObject
            TermInfo(
                bh = obj.get("bh").safeStr,
                name = obj.get("name").safeStr,
                startDate = obj.get("startDate").safeStr
                    .ifEmpty { obj.get("kssj").safeStr }
                    .ifEmpty { obj.get("startTime").safeStr },
                endDate = obj.get("endDate").safeStr
                    .ifEmpty { obj.get("jssj").safeStr }
                    .ifEmpty { obj.get("endTime").safeStr }
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
        val json = result.safeParseJsonObject()
        val dataEl1 = json.get("data")
        if (dataEl1 == null || dataEl1.isJsonNull || !dataEl1.isJsonObject) return emptyList()
        val dataObj = dataEl1.asJsonObject
        val listEl1 = dataObj.get("list")
        if (listEl1 == null || listEl1.isJsonNull || !listEl1.isJsonArray) return emptyList()
        val list = listEl1.asJsonArray

        return list.map { item ->
            val obj = item.asJsonObject
            AttendanceFlow(
                sbh = obj.get("sBh").safeStr,
                place = obj.get("eqno").safeStr,
                waterTime = obj.get("watertime").safeStr,
                type = FlowRecordType.fromValue(obj.get("isdone").safeInt)
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
        val json = result.safeParseJsonObject()
        val dataEl2 = json.get("data")
        if (dataEl2 == null || dataEl2.isJsonNull || !dataEl2.isJsonObject) return emptyList()
        val dataObj = dataEl2.asJsonObject
        val listEl2 = dataObj.get("list")
        if (listEl2 == null || listEl2.isJsonNull || !listEl2.isJsonArray) return emptyList()
        val list = listEl2.asJsonArray

        return list.map { item ->
            val obj = item.asJsonObject
            AttendanceFlow(
                sbh = obj.get("sBh").safeStr,
                place = obj.get("eqno").safeStr,
                waterTime = obj.get("watertime").safeStr,
                type = FlowRecordType.fromValue(obj.get("isdone").safeInt)
            )
        }
    }

    /**
     * 查询考勤统计（按学期）
     * Python 端点: /attendance-student/classWater/getClassWaterPage
     * @param startDate 学期起始日期 (yyyy-MM-dd)，旧学期需要提供日期范围才能查询
     * @param endDate 学期结束日期 (yyyy-MM-dd)
     */
    fun getWaterRecords(termBh: String? = null, startDate: String = "", endDate: String = ""): List<AttendanceWaterRecord> {
        val bh = termBh ?: getTermBh()
        val endDateFormatted = if (endDate.isNotEmpty() && !endDate.contains(" ")) "$endDate 23:59:59" else endDate
        val jsonBody = """{"startDate":"$startDate","endDate":"$endDateFormatted","current":1,"pageSize":500,"timeCondition":"","subjectBean":{"sCode":""},"classWaterBean":{"status":""},"classBean":{"termNo":"$bh"}}"""
        val result = post("/attendance-student/classWater/getClassWaterPage", jsonBody)
        val json = result.safeParseJsonObject()
        val dataEl = json.get("data")
        if (dataEl == null || dataEl.isJsonNull || !dataEl.isJsonObject) return emptyList()
        val dataObj = dataEl.asJsonObject
        val listEl = dataObj.get("list")
        if (listEl == null || listEl.isJsonNull || !listEl.isJsonArray) return emptyList()
        val list = listEl.asJsonArray

        return list.map { item ->
            val obj = item.asJsonObject
            val classWater = obj.get("classWaterBean")?.takeIf { it.isJsonObject }?.asJsonObject
            val account = obj.get("accountBean")?.takeIf { it.isJsonObject }?.asJsonObject
            val build = obj.get("buildBean")?.takeIf { it.isJsonObject }?.asJsonObject
            val room = obj.get("roomBean")?.takeIf { it.isJsonObject }?.asJsonObject
            val calendar = obj.get("calendarBean")?.takeIf { it.isJsonObject }?.asJsonObject
            val subject = obj.get("subjectBean")?.takeIf { it.isJsonObject }?.asJsonObject

            AttendanceWaterRecord(
                sbh = classWater?.get("bh").safeStr,
                termString = calendar?.get("name").safeStr,
                startTime = account?.get("startJc").safeInt,
                endTime = account?.get("endJc").safeInt,
                week = account?.get("week").safeInt,
                location = "${build?.get("name").safeStr}-${room?.get("roomnum").safeStr}",
                courseName = subject?.get("sName").safeStr
                    .ifEmpty { subject?.get("subjectname").safeStr },
                teacher = obj.get("teachNameList").safeStr,
                status = WaterType.fromValue(classWater?.get("status").safeInt.let { if (it == 0) 1 else it }),
                date = account?.get("checkdate").safeStr
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
        val json = result.safeParseJsonObject()
        val dataEl = json.get("data")
        if (dataEl == null || dataEl.isJsonNull || !dataEl.isJsonArray) return emptyList()
        val data = dataEl.asJsonArray
        return data.mapNotNull { item ->
            val obj = item.asJsonObject
            val name = obj.get("subjectname").safeStr
            if (name.isEmpty()) return@mapNotNull null
            CourseAttendanceStat(
                subjectName = name,
                subjectCode = obj.get("subjectCode").safeStr,
                normalCount = obj.get("normalCount").safeInt,
                lateCount = obj.get("lateCount").safeInt,
                absenceCount = obj.get("absenceCount").safeInt,
                leaveEarlyCount = obj.get("leaveEarlyCount").safeInt,
                leaveCount = obj.get("leaveCount").safeInt,
                total = obj.get("total").safeInt
            )
        }
    }
}
