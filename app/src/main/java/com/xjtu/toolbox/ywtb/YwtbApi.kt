package com.xjtu.toolbox.ywtb

import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.YwtbLogin
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

data class UserInfo(
    val userName: String,
    val userUid: String,
    val identityTypeName: String,
    val organizationName: String
)

class YwtbApi(private val login: YwtbLogin) {

    /**
     * 构建带通用 header 的请求 Builder（不含 x-id-token，由 executeWithReAuth 注入）
     */
    private fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("x-device-info", "PC")
            .header("x-terminal-info", "PC")
            .header("Referer", "https://ywtb.xjtu.edu.cn/main.html")
    }

    fun getUserInfo(): UserInfo {
        val request = baseRequest("https://authx-service.xjtu.edu.cn/personal/api/v1/personal/me/user")
            .get()
        val (responseCode, body) = login.executeWithReAuth(request).use { response ->
            response.code to (response.body?.string() ?: throw RuntimeException("空响应"))
        }
        val json = JsonParser.parseString(body).asJsonObject

        if (responseCode != 200) {
            throw RuntimeException(json.get("message")?.asString ?: "服务器错误")
        }

        val data = json.getAsJsonObject("data")
        val attributes = data.getAsJsonObject("attributes")

        return UserInfo(
            userName = attributes.get("userName")?.asString ?: data.get("username")?.asString ?: "",
            userUid = attributes.get("userUid")?.asString ?: "",
            identityTypeName = attributes.get("identityTypeName")?.asString ?: "",
            organizationName = attributes.get("organizationName")?.asString ?: ""
        )
    }

    /**
     * 获取今日的教学周信息（直接查询 YWTB 服务器）
     * @return Triple(教学周数, 学期名, 学期ID)，假期返回 null
     */
    fun getCurrentWeekOfTeaching(): Triple<Int, String, String>? {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val url = "https://ywtb.xjtu.edu.cn/portal-api/v1/calendar/share/schedule/getWeekOfTeaching"
            .toHttpUrl().newBuilder()
            .addQueryParameter("today", today)
            .addQueryParameter("random_number", Random.nextInt(100, 999).toString())
            .build()

        val request = baseRequest(url.toString()).get()
        val responseBody = login.executeWithReAuth(request).use { response ->
            response.body?.string() ?: return null
        }
        val json = JsonParser.parseString(responseBody).asJsonObject
        val dataObj = json.getAsJsonObject("data")?.getAsJsonObject("data") ?: return null
        val weekStr = dataObj.getAsJsonArray("date")?.get(0)?.asString ?: return null
        val semesterName = dataObj.getAsJsonArray("semesterAlilist")?.get(0)?.asString ?: ""
        val semesterId = dataObj.getAsJsonArray("semesterlist")?.get(0)?.asString ?: ""

        val week = weekStr.toIntOrNull()
        if (week == null || week <= 0) return null   // 不在教学周内 = 假期
        return Triple(week, semesterName, semesterId)
    }

    fun getStartOfTerm(timestamp: String): String {
        val parts = timestamp.split("-")
        require(parts.size == 3) { "格式错误，应为 YYYY-YYYY-S" }
        val yearStart = parts[0]
        val yearEnd = parts[1]
        val term = parts[2]

        val possibleStarts: List<String>
        val rightSemester: String

        if (term == "1") {
            possibleStarts = (1..30 step 7).map { "$yearStart-08-${it.toString().padStart(2, '0')}" } +
                    (1..30 step 7).map { "$yearStart-09-${it.toString().padStart(2, '0')}" }
            rightSemester = "第一学期"
        } else {
            possibleStarts = (1..28 step 7).map { "$yearEnd-02-${it.toString().padStart(2, '0')}" } +
                    (1..30 step 7).map { "$yearEnd-03-${it.toString().padStart(2, '0')}" }
            rightSemester = "第二学期"
        }

        val validDates = possibleStarts.filter { dateStr ->
            try { LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")); true } catch (_: Exception) { false }
        }

        val url = "https://ywtb.xjtu.edu.cn/portal-api/v1/calendar/share/schedule/getWeekOfTeaching"
            .toHttpUrl().newBuilder()
            .addQueryParameter("today", validDates.joinToString(","))
            .addQueryParameter("random_number", Random.nextInt(100, 999).toString())
            .build()

        val request = baseRequest(url.toString()).get()
        val responseBody = login.executeWithReAuth(request).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = JsonParser.parseString(responseBody).asJsonObject
        val dataObj = json.getAsJsonObject("data").getAsJsonObject("data")
        val dateArray = dataObj.getAsJsonArray("date")
        val semesterAliList = dataObj.getAsJsonArray("semesterAlilist")
        val semesterList = dataObj.getAsJsonArray("semesterlist")

        for (i in 0 until dateArray.size()) {
            val weekStr = dateArray[i].asString
            val semesterName = semesterAliList[i].asString
            val semesterId = semesterList[i].asString
            val dateStr = validDates[i]

            if (semesterId == "$yearStart-$yearEnd" && semesterName == rightSemester && weekStr == "1") {
                val dateObj = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val startOfTerm = dateObj.minusDays(dateObj.dayOfWeek.value.toLong() - 1)
                return startOfTerm.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            }
        }

        throw RuntimeException("无法确定学期开始时间")
    }
}
