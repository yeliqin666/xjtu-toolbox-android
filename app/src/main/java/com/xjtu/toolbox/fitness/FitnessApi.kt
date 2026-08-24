package com.xjtu.toolbox.fitness

import com.google.gson.JsonObject
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.runBlocking
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

fun FitnessScore.hasUsableTotal(): Boolean {
    val s = totalScore.trim()
    return s.isNotEmpty() && s != "--" && s != "未测"
}

fun FitnessYear.yearValue(): Int? =
    Regex("""\d{4}""").find(yearNum)?.value?.toIntOrNull()
        ?: Regex("""\d{4}""").find(name)?.value?.toIntOrNull()

/**
 * 体测系统会把尚未开测的下一学年也列在最前，[checked] 也经常指到那一档。
 * 按当前学年（9 月起算）往前排，丢掉还没考的年份。
 */
fun orderedFitnessYears(
    years: List<FitnessYear>,
    academicYear: Int = com.xjtu.toolbox.util.XjtuTime.currentAcademicYear(),
): List<FitnessYear> {
    val ranked = years.sortedByDescending { it.yearValue() ?: Int.MIN_VALUE }
    val eligible = ranked.filter { (it.yearValue() ?: Int.MAX_VALUE) <= academicYear }
    return eligible.ifEmpty { ranked }
}

/**
 * 从用户/模型传入的学年参数里取出起始年。
 * `2025`、`2025-2026`、`2025-2026-1` 都表示 2025-2026 学年。
 */
fun parseFitnessAcademicYear(raw: String?): Int? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return null
    Regex("""(20\d{2})\s*[-~—/到至]\s*(20\d{2})""").find(s)?.let {
        return it.groupValues[1].toInt()
    }
    return Regex("""20\d{2}""").find(s)?.value?.toIntOrNull()
}

fun pickFitnessYear(
    years: List<FitnessYear>,
    yearKey: String?,
    academicYear: Int = com.xjtu.toolbox.util.XjtuTime.currentAcademicYear(),
): FitnessYear? {
    val ordered = orderedFitnessYears(years, academicYear)
    val want = parseFitnessAcademicYear(yearKey) ?: return ordered.firstOrNull()
    return ordered.firstOrNull { it.yearValue() == want }
        ?: years.firstOrNull { it.yearValue() == want }
        ?: yearKey?.let { key ->
            years.firstOrNull { it.name.contains(key) || it.yearNum.contains(key) }
        }
}

class FitnessApi(private val site: SiteSession) {
    private val refererUrl
        get() = site.localToken["referer_url"] ?: FitnessProtocol.H5_HOME_URL

    fun getYears(): List<FitnessYear> {
        val data = fetchData(
            v3Path = "fitness/fitnessYear",
            extra = mapOf("from" to 1),
            phpPath = "${FitnessProtocol.LEGACY_API_ROOT}/fitness/fitnessYear",
            phpForm = FormBody.Builder().add("from", "1").build(),
            accept = { it.get("list")?.isJsonArray == true },
        )
        val list = data.getAsJsonArray("list") ?: return emptyList()
        return list.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val item = element.asJsonObject
            val yearNum = text(item, "year_num").ifBlank { return@mapNotNull null }
            FitnessYear(
                yearNum = yearNum,
                name = text(item, "name").ifBlank { yearNum },
                checked = item.get("checked")?.let {
                    runCatching { it.asBoolean }.getOrDefault(false)
                } ?: false
            )
        }
    }

    fun getScore(yearNum: String): FitnessScore {
        val data = fetchData(
            v3Path = "Report/getStudentScore",
            extra = mapOf("year_num" to yearNum),
            phpPath = "${FitnessProtocol.LEGACY_API_ROOT}/Report/getStudentScore",
            phpForm = FormBody.Builder().add("year_num", yearNum).build(),
            accept = { it.has("student_num") || it.has("total_score") || it.has("bmi_score") || it.has("bmi_grade") },
        )
        fun value(key: String): String = text(data, key)
        fun formatScore(raw: String): String =
            raw.trim().toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.2f", it) }
                ?: raw
        fun item(name: String, key: String, display: String = value("${key}_score")) = FitnessItem(
            name = name,
            value = formatScore(display).ifBlank { "未测" },
            grade = value("${key}_grade").ifBlank { "缺项" },
            tone = value("${key}_class")
        )

        val bmiDisplay = value("bmi_score_new").ifBlank { value("bmi_score") }
        val strengthName = if (value("sex") == "女") "仰卧起坐" else "引体向上"
        val runName = if (value("sex") == "女") "800 米" else "1000 米"

        return FitnessScore(
            studentNumber = value("student_num"),
            studentName = value("student_name"),
            totalScore = formatScore(value("total_score")).ifBlank { "--" },
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

    private fun fetchData(
        v3Path: String,
        extra: Map<String, Any>,
        phpPath: String,
        phpForm: FormBody,
        accept: (JsonObject) -> Boolean,
    ): JsonObject {
        if (FitnessProtocol.sessionFromTokens(site.localToken) == null) {
            throw AuthExpiredException("体测查询", "体测会话未初始化")
        }
        val v3Body = try {
            FitnessProtocol.postEncrypted(site, v3Path, extra, refererUrl)
        } catch (e: AuthExpiredException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val v3Data = v3Body?.let { FitnessProtocol.parseEnvelope(it) }
        if (v3Data != null && accept(v3Data)) return v3Data

        val phpRoot = postLegacy(phpPath, phpForm)
        val dataElement = phpRoot.get("data")?.takeUnless { it.isJsonNull }
            ?: throw RuntimeException(phpRoot.get("info")?.asString ?: "暂无体测数据")
        if (!dataElement.isJsonObject) {
            val info = phpRoot.get("info")?.asString.orEmpty()
            throw RuntimeException(info.takeIf { it.isNotBlank() && it != "查询成功" } ?: "该学年暂无体测数据")
        }
        return dataElement.asJsonObject
    }

    private fun postLegacy(url: String, body: FormBody) =
        runBlocking {
            site.executeWithReAuth(
                Request.Builder()
                    .url(url)
                    .header("Origin", FitnessProtocol.ORIGIN)
                    .header("Referer", refererUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(body)
                    .build()
            )
        }.use { response ->
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

    private fun text(data: JsonObject, key: String): String {
        val el = data.get(key) ?: return ""
        if (el.isJsonNull) return ""
        return runCatching { el.asString }.getOrDefault(el.toString().trim('"'))
    }
}
