package com.xjtu.toolbox.fitness

import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.booleanValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.isObject
import com.xjtu.toolbox.util.isArray
import com.xjtu.toolbox.util.arr
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeParseJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * 体测取数（`:app` 端）。模型（[FitnessYear] / [FitnessScore] / [FitnessItem]）、
 * 学年排序与解析（`orderedFitnessYears` / `pickFitnessYear` / `parseFitnessAcademicYear`）、
 * 分数格式化（[formatFitnessScore]）已全部搬进 `:core`（见 `fitness/FitnessModels.kt`），
 * 屏幕也搬走了（`fitness/FitnessScreen.kt`）—— 这个文件只剩**取数**：v3 加密协议优先，
 * 失败退回 legacy PHP 路径（两条都是 okhttp，行为与搬迁前一致）。
 *
 * 唯一被替换的写法：`String.format(java.util.Locale.US, "%.2f", it)` 换成共享的
 * [formatFitnessScore]（`String.format` 是 JVM 专属，且在 JVM 上是默认导入）。
 */
class FitnessApi(private val site: SiteSession) : FitnessSource {
    private val refererUrl
        get() = site.localToken["referer_url"] ?: FitnessProtocol.H5_HOME_URL

    override suspend fun years(): List<FitnessYear> = withContext(Dispatchers.IO) { loadYears() }

    private suspend fun loadYears(): List<FitnessYear> {
        val data = fetchData(
            v3Path = "fitness/fitnessYear",
            extra = mapOf("from" to 1),
            phpPath = "${FitnessProtocol.LEGACY_API_ROOT}/fitness/fitnessYear",
            phpForm = FormBody.Builder().add("from", "1").build(),
            accept = { it.get("list")?.isArray == true },
        )
        val list = data.arr("list") ?: return emptyList()
        return list.mapNotNull { element ->
            if (!element.isObject) return@mapNotNull null
            val item = element.jsonObject
            val yearNum = text(item, "year_num").ifBlank { return@mapNotNull null }
            FitnessYear(
                yearNum = yearNum,
                name = text(item, "name").ifBlank { yearNum },
                checked = item.get("checked")?.let {
                    runCatching { it.booleanValue }.getOrDefault(false)
                } ?: false
            )
        }
    }

    override suspend fun score(yearNum: String): FitnessScore = withContext(Dispatchers.IO) { loadScore(yearNum) }

    private suspend fun loadScore(yearNum: String): FitnessScore {
        val data = fetchData(
            v3Path = "Report/getStudentScore",
            extra = mapOf("year_num" to yearNum),
            phpPath = "${FitnessProtocol.LEGACY_API_ROOT}/Report/getStudentScore",
            phpForm = FormBody.Builder().add("year_num", yearNum).build(),
            accept = { it.containsKey("student_num") || it.containsKey("total_score") || it.containsKey("bmi_score") || it.containsKey("bmi_grade") },
        )
        fun value(key: String): String = text(data, key)
        fun formatScore(raw: String): String = formatFitnessScore(raw)
        fun item(name: String, key: String, display: String = value("${key}_score")) = FitnessItem(
            name = name,
            value = formatScore(display).ifBlank { "未测" },
            grade = value("${key}_grade").ifBlank { "缺项" },
            tone = value("${key}_class")
        )

        val bmiDisplay = value("bmi_score_new").ifBlank { value("bmi_score") }
        val strengthName = fitnessItemName("pull_and_sit", value("sex"))
        val runName = fitnessItemName("run", value("sex"))

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

    private suspend fun fetchData(
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
        val dataElement = phpRoot.get("data")?.takeUnless { it.isNull }
            ?: throw RuntimeException(phpRoot.get("info")?.stringValue ?: "暂无体测数据")
        if (!dataElement.isObject) {
            val info = phpRoot.get("info")?.stringValue.orEmpty()
            throw RuntimeException(info.takeIf { it.isNotBlank() && it != "查询成功" } ?: "该学年暂无体测数据")
        }
        return dataElement.jsonObject
    }

    private suspend fun postLegacy(url: String, body: FormBody) =
        site.executeWithReAuth(
                Request.Builder()
                    .url(url)
                    .header("Origin", FitnessProtocol.ORIGIN)
                    .header("Referer", refererUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(body)
                    .build()
            ).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("体测服务响应 ${response.code}")
            val root = text.safeParseJsonObject()
            if (root.get("status")?.intValue != 1) {
                val message = root.get("info")?.stringValue ?: "体测查询失败"
                if ("登录" in message || "验证" in message || "会话" in message) {
                    throw AuthExpiredException("体测查询", message)
                }
                throw RuntimeException(message)
            }
            root
        }

    private fun text(data: JsonObject, key: String): String {
        val el = data.get(key) ?: return ""
        if (el.isNull) return ""
        return runCatching { el.stringValue }.getOrDefault(el.toString().trim('"'))
    }
}
