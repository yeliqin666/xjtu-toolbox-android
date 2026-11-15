package com.xjtu.toolbox.judge

import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.obj
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeDoubleOrNull
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeParseJson
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.pow
import kotlin.math.roundToLong

/** 一门课一位（组）教师的一份问卷；[responseId] 是 QMS 的答卷 id，提交和撤回都靠它。 */
data class QmsQuestionnaire(
    val responseId: String,
    val planTitle: String,
    val courseName: String,
    val lecturers: List<String>,
    val status: String,
) {
    /** QMS 里只有 DRAFT 算没交。 */
    val finished get() = !status.equals("DRAFT", ignoreCase = true)
}

class QmsException(message: String) : RuntimeException(message)

/**
 * 学生评教 QMS（畅评 TronGauge）接口，协议参照 XJTUToolBox #90。
 * 流程：任务列表 → 每个任务按 DRAFT / ENABLED 拉分组课程 → 每份答卷取详情、按题型填满分 → PUT 提交。
 */
class QmsJudgeApi(private val site: SiteSession) {

    private suspend fun request(method: String, path: String, body: JsonElement? = null): JsonElement? {
        val req = Request.Builder()
            .url("$BASE$path")
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$BASE/quality-client/my/tasks")
            .header("X-Requested-With", "XMLHttpRequest")
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        val (code, text) = site.executeWithReAuth(req).use { it.code to it.body.string() }
        if (code >= 400) throw QmsException(errorMessage(code, text))
        return if (text.isBlank()) null else text.safeParseJson()
    }

    private fun errorMessage(code: Int, text: String): String {
        val root = runCatching { text.safeParseJson() as? JsonObject }.getOrNull()
            ?: return text.trim().take(300).ifEmpty { "服务器返回 HTTP $code" }
        val violations = root.arr("violations").orEmpty().mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val field = o["field"].safeString()
            val msg = o["message"].safeString()
            if (field.isNotEmpty()) "$field: $msg" else msg
        }
        if (violations.isNotEmpty()) return violations.joinToString("；")
        return root["message"].safeStringOrNull() ?: root["error"].safeStringOrNull() ?: "服务器返回 HTTP $code"
    }

    private fun JsonElement?.dataArray(what: String): List<JsonObject> =
        ((this as? JsonObject)?.get("data") as? JsonArray)?.filterIsInstance<JsonObject>()
            ?: throw QmsException("QMS ${what}格式异常")

    private fun JsonObject.plan(): JsonObject? = obj("plan_instance")?.obj("plan") ?: obj("plan")

    /** 进行中的学生评教任务（QMS 里还有别的业务的任务，按 business_code 筛掉）。 */
    private suspend fun tasks(): List<JsonObject> =
        request("GET", "/api/quality/my/tasks?planStatus=IN_PROGRESS").dataArray("任务列表")
            .filter { it.plan()?.get("business_code").safeString() == BUSINESS }

    private suspend fun groupedCourses(taskId: String, status: String): List<JsonObject> =
        request("GET", "/api/quality/tasks/$taskId/$BUSINESS/grouped-courses?status=$status&page=0&size=1000&tableMode=CARD")
            .dataArray("课程列表")

    /** (未评, 已评)。 */
    suspend fun questionnaires(): Pair<List<QmsQuestionnaire>, List<QmsQuestionnaire>> {
        val all = LinkedHashMap<String, QmsQuestionnaire>()
        for (task in tasks()) {
            val taskId = task["id"].safeString()
            val planTitle = localized(task.plan()?.get("primary_name"))
            val before = all.size
            val errors = mutableListOf<QmsException>()
            for (status in listOf("DRAFT", "ENABLED")) {
                val courses = try { groupedCourses(taskId, status) } catch (e: QmsException) { errors += e; continue }
                for (course in courses) {
                    val name = course["course_name"].safeStringOrNull() ?: course["primary_plan_course_name"].safeString()
                    val lecturers = course.arr("lecturers").orEmpty().map { it.safeString() }.filter { it.isNotBlank() }
                    for (survey in course.arr("surveys").orEmpty().filterIsInstance<JsonObject>()) {
                        val response = survey.obj("response")
                        val id = response?.get("id").safeStringOrNull() ?: survey["response_id"].safeStringOrNull() ?: continue
                        if (id in all) continue
                        all[id] = QmsQuestionnaire(id, planTitle, name, lecturers, response?.get("status").safeStringOrNull() ?: status)
                    }
                }
            }
            // 一个任务两种状态都没拉到时才报错，拉到一半就先用着
            if (errors.isNotEmpty() && all.size == before) throw errors.first()
        }
        val sorted = all.values.sortedWith(compareBy<QmsQuestionnaire>({ it.courseName }, { it.lecturers.joinToString() }))
        return sorted.filterNot { it.finished } to sorted.filter { it.finished }
    }

    /** 全部按最好一档填好并提交（主观题填 [comment]）。 */
    suspend fun submitBest(q: QmsQuestionnaire, comment: String = DEFAULT_COMMENT) {
        val detail = (request("GET", "/api/quality/responses/${q.responseId}") as? JsonObject)?.obj("data")
        val response = detail?.obj("response") ?: throw QmsException("无法读取 QMS 问卷详情")
        val payload = buildJsonObject {
            put("response", response)
            put("extension_data", buildJsonObject {
                put("lecturers", JsonNull)
                put("slots", JsonArray(emptyList()))
                put("is_new_response", false)
            })
            put("video_info", JsonNull)
            put("more_survey_answers", surveyAnswers(detail, comment))
            put("status", "ENABLED")
        }
        request("PUT", "/api/quality/more-survey/responses/${q.responseId}", payload)
    }

    /** 撤回成草稿，之后可以重新提交。 */
    suspend fun recall(q: QmsQuestionnaire) {
        request("PATCH", "/api/quality/$BUSINESS/surveys/${q.responseId}/status", buildJsonObject { put("status", "DRAFT") })
    }

    private fun surveyAnswers(detail: JsonObject, comment: String): JsonArray {
        val labels = (detail["labelSurvey"] ?: detail["label_survey"]) as? JsonArray
            ?: throw QmsException("QMS 问卷结构异常：缺少 labelSurvey")
        val surveys = labels.filterIsInstance<JsonObject>().mapNotNull { label ->
            val survey = label.obj("survey") ?: return@mapNotNull null
            val surveyId = survey["id"]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
            buildJsonObject {
                put("answers", JsonArray(survey.arr("questions").orEmpty().filterIsInstance<JsonObject>().mapNotNull { answer(it, comment) }))
                put("survey_id", surveyId)
            }
        }
        if (surveys.isEmpty()) throw QmsException("QMS 问卷结构异常：没有可填写的问卷")
        return JsonArray(surveys)
    }

    /** 按题型填最好一档；[comment] 用于简答 / 填空。返回 null 表示这题不用答。 */
    private fun answer(question: JsonObject, comment: String): JsonObject? {
        val qid = question["id"].safeString().ifEmpty { return null }
        val type = question["type"].safeString()
        if (type == "TEXT_TITLE") return null
        if (type == "EVALUATION") throw QmsException("QMS 出现了暂不支持的评价题型：EVALUATION")
        val options = question.obj("options")
        val subs = question.arr("sub_questions").orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it["id"].safeStringOrNull() }
        // 选项按从好到差排，第一项就是最好的
        val best = question.arr("choices").orEmpty().filterIsInstance<JsonObject>().firstOrNull()
        val fields = mutableMapOf<String, JsonElement>(
            "answer" to JsonArray(emptyList()),
            "question_id" to JsonPrimitive(qid),
            "question_type" to JsonPrimitive(type),
            "score" to JsonNull,
            "star" to JsonNull,
            "input_answers" to JsonArray(emptyList()),
        )
        when (type) {
            "SCORING" -> {
                val score = maxScore(options)
                if (subs.isEmpty()) fields["score"] = score
                else fields["answer"] = JsonArray(subs.map { sub ->
                    buildJsonObject {
                        put("answer", JsonArray(emptyList()))
                        put("question_id", sub)
                        put("question_type", "SCORING")
                        put("score", score)
                    }
                })
            }
            "MATRIX" -> if (best != null) {
                val score = best.obj("options")?.get("score") ?: JsonNull
                fields["answer"] = JsonArray(subs.map { sub ->
                    buildJsonObject {
                        put("answer", best["id"] ?: JsonNull)
                        put("question_id", sub)
                        put("question_type", "SINGLE_SELECTION")
                        put("score", score)
                    }
                })
                fields["score"] = JsonPrimitive((score.safeDoubleOrNull() ?: 0.0) * subs.size)
            }
            "SHORT_ANSWER", "FILL_BLANK" -> {
                fields["answer"] = JsonPrimitive(subjectiveText(options, comment))
                if (type == "SHORT_ANSWER") fields["preset_replies"] = JsonArray(emptyList())
            }
            "SINGLE_SELECTION", "MULTI_SELECTION" -> if (best != null) {
                val id = best["id"] ?: JsonNull
                val choiceOptions = best.obj("options")
                fields["answer"] = if (type == "MULTI_SELECTION") buildJsonArray { if (id != JsonNull) add(id) } else id
                fields["score"] = choiceOptions?.get("score") ?: JsonNull
                if (choiceOptions?.get("star").safeBoolean()) {
                    fields["star"] = choiceOptions?.get("starCount") ?: choiceOptions?.get("star_count") ?: JsonNull
                }
                val inputEnabled = options?.get("inputtedEnabled").safeBoolean() || options?.get("inputted_enabled").safeBoolean()
                if (inputEnabled && choiceOptions?.get("inputEnabled").safeBoolean()) {
                    fields["input_answers"] = buildJsonArray {
                        add(buildJsonObject { put("choiceId", id); put("inputText", "") })
                    }
                }
            }
        }
        return JsonObject(fields)
    }

    /** 打分题取上限，按题目要求的小数位输出（0 位时写整数）。 */
    private fun maxScore(options: JsonObject?): JsonPrimitive {
        val min = options?.get("min_range").safeDoubleOrNull() ?: 0.0
        val max = options?.get("max_range").safeDoubleOrNull() ?: 100.0
        val places = options?.get("decimal_places").safeInt().coerceIn(0, 6)
        val value = maxOf(min, max)
        if (places == 0) return JsonPrimitive(value.roundToLong())
        val scale = 10.0.pow(places)
        return JsonPrimitive((value * scale).roundToLong() / scale)
    }

    /** 主观题至少要写够 input_min_length 个字。 */
    private fun subjectiveText(options: JsonObject?, comment: String): String {
        val min = options?.get("input_min_length").safeInt()
        val text = comment.trim().ifEmpty { DEFAULT_COMMENT }
        return if (text.length >= min) text else text.trimEnd('，', '。', '；', ';', ',', '.', ' ') + "，" + DEFAULT_COMMENT
    }

    private fun localized(value: JsonElement?): String {
        if (value is JsonPrimitive) return value.safeString()
        val obj = (value as? JsonObject)?.let { it.obj("text") ?: it } ?: return ""
        return listOf("zh_cn", "zh-CN", "zh", "en_us", "en-US", "en")
            .firstNotNullOfOrNull { obj[it].safeStringOrNull()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
    }

    companion object {
        private const val BASE = "https://qms.xjtu.edu.cn"
        private const val BUSINESS = "student-evaluation"
        private val JSON = "application/json".toMediaType()
        const val DEFAULT_COMMENT = "该课程整体教学情况良好，暂无进一步建议。"
    }
}
