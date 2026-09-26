package com.xjtu.toolbox.ywtb

import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.requireObj
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
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

class YwtbApi(private val site: SiteSession) {

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

    suspend fun getUserInfo(): UserInfo {
        val request = baseRequest("https://authx-service.xjtu.edu.cn/personal/api/v1/personal/me/user")
            .get()
        val (responseCode, body) = site.executeWithReAuth(request.build()).use { response ->
            response.code to (response.body?.string() ?: throw RuntimeException("空响应"))
        }
        val json = body.safeParseJsonObject()

        if (responseCode != 200) {
            throw RuntimeException(json.get("message")?.stringValue ?: "服务器错误")
        }

        val data = json.requireObj("data")
        val attributes = data.requireObj("attributes")

        return UserInfo(
            userName = attributes.get("userName")?.stringValue ?: data.get("username")?.stringValue ?: "",
            userUid = attributes.get("userUid")?.stringValue ?: "",
            identityTypeName = attributes.get("identityTypeName")?.stringValue ?: "",
            organizationName = attributes.get("organizationName")?.stringValue ?: ""
        )
    }

    suspend fun getStartOfTerm(timestamp: String): String {
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
        val responseBody = site.executeWithReAuth(request.build()).use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = responseBody.safeParseJsonObject()
        val dataObj = json.requireObj("data").requireObj("data")
        val dateArray = dataObj.requireArr("date")
        val semesterAliList = dataObj.requireArr("semesterAlilist")
        val semesterList = dataObj.requireArr("semesterlist")

        for (i in 0 until dateArray.size) {
            val weekStr = dateArray[i].stringValue
            val semesterName = semesterAliList[i].stringValue
            val semesterId = semesterList[i].stringValue
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
