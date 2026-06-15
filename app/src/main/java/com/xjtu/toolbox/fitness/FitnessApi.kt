package com.xjtu.toolbox.fitness

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.util.safeParseJsonObject
import okhttp3.FormBody
import okhttp3.Request

data class FitnessYear(
    val yearNum: String,
    val name: String,
    val checked: Boolean,
)

data class FitnessScore(
    val studentNumber: String,
    val studentName: String,
    val totalScore: String,
    val totalGrade: String,
    val reportType: String,
    val reportStatus: String,
    val sex: String,
    val grade: String,
    val items: List<FitnessItem>,
)

data class FitnessItem(
    val name: String,
    val value: String,
    val grade: String,
    val tone: String,
)

class FitnessApi(private val login: FitnessLogin) {

    fun getYears(): List<FitnessYear> {
        val root = post(
            "${FitnessLogin.API_ROOT}/fitness/fitnessYear",
            FormBody.Builder().add("from", "1").build()
        )
        val list = root.getAsJsonObject("data")?.getAsJsonArray("list")
            ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element.asJsonObject
            val yearNum = item.get("year_num")?.asString ?: return@mapNotNull null
            FitnessYear(
                yearNum = yearNum,
                name = item.get("name")?.asString ?: yearNum,
                checked = item.get("checked")?.let {
                    runCatching { it.asBoolean }.getOrDefault(false)
                } ?: false
            )
        }
    }

    fun getScore(yearNum: String): FitnessScore {
        val root = post(
            "${FitnessLogin.API_ROOT}/Report/getStudentScore",
            FormBody.Builder().add("year_num", yearNum).build()
        )
        val data = root.getAsJsonObject("data")
            ?: throw RuntimeException(root.get("info")?.asString ?: "暂无体测数据")

        fun value(key: String): String =
            data.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        fun item(name: String, key: String, display: String = value(key)) = FitnessItem(
            name = name,
            value = display.ifBlank { "未测" },
            grade = value("${key}_grade").ifBlank { "缺项" },
            tone = value("${key}_class")
        )

        val bmiDisplay = value("bmi_score_new").ifBlank { value("bmi_score") }
        val strengthName = if (value("sex") == "女") "仰卧起坐" else "引体向上"
        val runName = if (value("sex") == "女") "800 米" else "1000 米"

        return FitnessScore(
            studentNumber = value("student_num"),
            studentName = value("student_name"),
            totalScore = value("total_score").ifBlank { "--" },
            totalGrade = value("total_grade").ifBlank { "未测" },
            reportType = value("report_type"),
            reportStatus = value("report_status"),
            sex = value("sex"),
            grade = value("grade"),
            items = listOf(
                item("身高 / 体重", "bmi", bmiDisplay),
                item("肺活量", "vc"),
                item("立定跳远", "jump"),
                item("坐位体前屈", "sit_and_reach"),
                item(strengthName, "pull_and_sit"),
                item("50 米", "50m"),
                item(runName, "run"),
            )
        )
    }

    private fun post(url: String, body: FormBody) =
        login.client.newCall(
            Request.Builder()
                .url(url)
                .header("Origin", FitnessLogin.ORIGIN)
                .header("Referer", login.refererUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body)
                .build()
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("体测服务响应 ${response.code}")
            val root = text.safeParseJsonObject()
            if (root.get("status")?.asInt != 1) {
                val message = root.get("info")?.asString ?: "体测查询失败"
                if ("登录" in message || "验证" in message || "会话" in message) {
                    throw AuthExpiredException("体测查询", message)
                }
                throw RuntimeException(message)
            }
            root
        }
}
