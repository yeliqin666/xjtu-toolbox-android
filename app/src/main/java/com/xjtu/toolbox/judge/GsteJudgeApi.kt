package com.xjtu.toolbox.judge

import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.GsteLogin
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.regex.Pattern

// ==================== 数据类 ====================

/**
 * 研究生评教问卷基本信息
 */
data class GraduateQuestionnaire(
    val ASSESSMENT: String,     // 评教状态: "already"=已评, "allow"=待评
    val BJID: String,           // 班级 ID
    val BJMC: String,           // 班级名称
    val DATA_JXB_ID: Int,       // 教学班 ID
    val DATA_JXB_JS_ID: Int,    // 教学班教师 ID
    val JSBH: String,           // 教师编号
    val JSXM: String,           // 教师姓名
    val JXB_SJ_OK: String,     // "yes" / "no"
    val KCBH: String,           // 课程编号
    val KCMC: String,           // 课程名称
    val KCYWMC: String,         // 课程英文名称
    val KKDW: String,           // 开课单位
    val LANG: String,           // 语言: "cn" / "en"
    val SKLS_DUTY: String,      // 授课老师职责: "主讲" / "辅讲"
    val TERMCODE: String,       // 学期代码
    val TERMNAME: String        // 学期名称
)

/**
 * 问卷中的单个题目
 */
data class FormQuestion(
    val id: String,          // 题目提交字段的唯一 ID
    val name: String,        // 题目名称
    val view: String,        // 控件类型: radio / textarea / text / select
    val options: List<FormOption>? = null  // 选项列表（单选/下拉题）
)

/**
 * 题目选项
 */
data class FormOption(
    val id: String,    // 选项 ID（提交用）
    val value: String  // 选项显示文本
)

// ==================== API 类 ====================

/**
 * 研究生评教 API
 * 封装了研究生评教系统 (gste.xjtu.edu.cn) 的所有请求接口
 */
class GsteJudgeApi(private val login: GsteLogin) {

    /**
     * 获取当前学期的评教问卷列表
     */
    fun getQuestionnaires(): List<GraduateQuestionnaire> {
        val request = Request.Builder()
            .url("http://gste.xjtu.edu.cn/app/sshd4Stu/list.do")
            .get()
            .build()

        val body = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val jsonArray = JsonParser.parseString(body).asJsonArray

        return jsonArray.map { el ->
            val obj = el.asJsonObject
            GraduateQuestionnaire(
                ASSESSMENT = obj.get("assessment")?.asString ?: "",
                BJID = obj.get("bjid")?.asString ?: "",
                BJMC = obj.get("bjmc")?.asString ?: "",
                DATA_JXB_ID = obj.get("data_jxb_id")?.asInt ?: 0,
                DATA_JXB_JS_ID = obj.get("data_jxb_js_id")?.asInt ?: 0,
                JSBH = obj.get("jsbh")?.asString ?: "",
                JSXM = obj.get("jsxm")?.asString ?: "",
                JXB_SJ_OK = obj.get("jxb_sj_ok")?.asString ?: "",
                KCBH = obj.get("kcbh")?.asString ?: "",
                KCMC = obj.get("kcmc")?.asString ?: "",
                KCYWMC = obj.get("kcywmc")?.asString ?: "",
                KKDW = obj.get("kkdw")?.asString ?: "",
                LANG = obj.get("lang")?.asString ?: "",
                SKLS_DUTY = obj.get("skls_duty")?.asString ?: "",
                TERMCODE = obj.get("termcode")?.asString ?: "",
                TERMNAME = obj.get("termname")?.asString ?: ""
            )
        }
    }

    /**
     * 获取指定问卷的 HTML 表单页面
     */
    fun getQuestionnaireHtml(q: GraduateQuestionnaire): String {
        val urlBuilder = "http://gste.xjtu.edu.cn/app/student/genForm.do".toHttpUrl().newBuilder()
            .addQueryParameter("assessment", q.ASSESSMENT)
            .addQueryParameter("bjid", q.BJID)
            .addQueryParameter("bjmc", q.BJMC)
            .addQueryParameter("data_jxb_id", q.DATA_JXB_ID.toString())
            .addQueryParameter("data_jxb_js_id", q.DATA_JXB_JS_ID.toString())
            .addQueryParameter("jsbh", q.JSBH)
            .addQueryParameter("jsxm", q.JSXM)
            .addQueryParameter("jxb_sj_ok", q.JXB_SJ_OK)
            .addQueryParameter("kcbh", q.KCBH)
            .addQueryParameter("kcmc", q.KCMC)
            .addQueryParameter("kcywmc", q.KCYWMC)
            .addQueryParameter("kkdw", q.KKDW)
            .addQueryParameter("lang", q.LANG)
            .addQueryParameter("skls_duty", q.SKLS_DUTY)
            .addQueryParameter("termcode", q.TERMCODE)
            .addQueryParameter("termname", q.TERMNAME)
            .build()

        val request = Request.Builder()
            .url(urlBuilder)
            .get()
            .build()

        return login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
    }

    /**
     * 从 HTML 中解析表单结构
     * @return Pair<隐藏字段(meta), 可见题目列表>
     */
    fun parseFormFromHtml(html: String): Pair<Map<String, String>, List<FormQuestion>> {
        val formObj = extractFormObject(html) ?: return Pair(emptyMap(), emptyList())

        val meta = mutableMapOf<String, String>()
        val questions = mutableListOf<FormQuestion>()

        // 递归遍历 form JSON 对象，提取题目和隐藏字段
        walkFormNode(formObj, meta, questions, null, -1)

        return Pair(meta, questions)
    }

    /**
     * 自动填写问卷
     * @param questions 题目列表
     * @param meta 隐藏字段
     * @param q 问卷信息
     * @param score 评价等级 3=优, 2=良, 1=合格, 0=不合格
     * @return 提交用的表单数据
     */
    fun autoFill(
        questions: List<FormQuestion>,
        meta: Map<String, String>,
        q: GraduateQuestionnaire,
        score: Int = 3
    ): Map<String, String> {
        val formData = mutableMapOf<String, String>()

        // 填入隐藏字段
        formData.putAll(meta)

        val scoreLabels = arrayOf("不合格", "合格", "良好", "优秀")
        val actualScore = score.coerceIn(0, 3)
        val scoreLabel = scoreLabels[actualScore]

        var firstRadioId: String? = null

        for (question in questions) {
            when (question.view) {
                "radio" -> {
                    if (firstRadioId == null) firstRadioId = question.id
                    val chosen = chooseOptionValue(question.options, scoreLabel)
                    if (chosen != null) {
                        formData[question.id] = chosen
                    }
                }
                "select" -> {
                    val chosen = chooseOptionValue(question.options, scoreLabel)
                    if (chosen != null) {
                        formData[question.id] = chosen
                    }
                }
                "textarea" -> {
                    formData[question.id] = "无"
                }
                "text" -> {
                    // 文本题: 尝试根据题目名称填写课程信息
                    val nameLower = question.name.lowercase()
                    formData[question.id] = when {
                        "课程名称" in nameLower || "课程名" in nameLower -> q.KCMC
                        "教师" in nameLower || "老师" in nameLower -> q.JSXM
                        else -> q.KCMC
                    }
                }
            }
        }

        // 系统要求不能全优：如果 score=3(优)，把第一个 radio 改成"良好"
        if (actualScore == 3 && firstRadioId != null) {
            val firstQ = questions.find { it.id == firstRadioId }
            if (firstQ != null) {
                val lhOption = chooseOptionValue(firstQ.options, "良好")
                if (lhOption != null) {
                    formData[firstRadioId] = lhOption
                }
            }
        }

        return formData
    }

    /**
     * 提交问卷
     * @return true=提交成功
     */
    fun submitQuestionnaire(
        q: GraduateQuestionnaire,
        formData: Map<String, String>
    ): Boolean {
        val bodyBuilder = FormBody.Builder()

        // 添加问卷基本参数
        bodyBuilder.add("assessment", q.ASSESSMENT)
        bodyBuilder.add("bjid", q.BJID)
        bodyBuilder.add("bjmc", q.BJMC)
        bodyBuilder.add("data_jxb_id", q.DATA_JXB_ID.toString())
        bodyBuilder.add("data_jxb_js_id", q.DATA_JXB_JS_ID.toString())
        bodyBuilder.add("jsbh", q.JSBH)
        bodyBuilder.add("jsxm", q.JSXM)
        bodyBuilder.add("jxb_sj_ok", q.JXB_SJ_OK)
        bodyBuilder.add("kcbh", q.KCBH)
        bodyBuilder.add("kcmc", q.KCMC)
        bodyBuilder.add("kcywmc", q.KCYWMC)
        bodyBuilder.add("kkdw", q.KKDW)
        bodyBuilder.add("lang", q.LANG)
        bodyBuilder.add("skls_duty", q.SKLS_DUTY)
        bodyBuilder.add("termcode", q.TERMCODE)
        bodyBuilder.add("termname", q.TERMNAME)

        // 添加表单数据（隐藏字段 + 答案）
        for ((key, value) in formData) {
            bodyBuilder.add(key, value)
        }

        val request = Request.Builder()
            .url("http://gste.xjtu.edu.cn/app/student/saveForm.do")
            .post(bodyBuilder.build())
            .build()

        val body = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = JsonParser.parseString(body).asJsonObject
        return json.get("ok")?.asBoolean ?: false
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 从 HTML 中提取 pjzbApp.form = {...} 的 JSON 对象
     */
    private fun extractFormObject(html: String): Map<*, *>? {
        if (html.isEmpty()) return null

        val anchor = html.indexOf("pjzbApp.form")
        if (anchor < 0) return null

        val eq = html.indexOf("=", anchor)
        if (eq < 0) return null

        val start = html.indexOf("{", eq)
        if (start < 0) return null

        // 使用配对括号法提取完整 JSON 片段
        var depth = 0
        var inStr = false
        var esc = false
        var end = -1

        for (i in start until html.length) {
            val ch = html[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (ch == '\\') {
                    esc = true
                } else if (ch == '"') {
                    inStr = false
                }
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i + 1
                            break
                        }
                    }
                }
            }
        }

        if (end < 0) return null

        var objText = html.substring(start, end)

        // 将 webix.rules.isNotEmpty 等非 JSON 值替换为字符串
        objText = objText.replace(Regex(""":\s*webix\.rules\.isNotEmpty"""), ": \"isNotEmpty\"")

        return try {
            val parsed = JsonParser.parseString(objText)
            if (parsed.isJsonObject) {
                jsonObjectToMap(parsed.asJsonObject)
            } else null
        } catch (_: Exception) {
            // 尝试去掉尾逗号
            val cleaned = objText.replace(Regex(""",\s*([}\]])"""), "$1")
            try {
                val parsed = JsonParser.parseString(cleaned)
                if (parsed.isJsonObject) jsonObjectToMap(parsed.asJsonObject) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * JsonObject 转 Map（支持嵌套）
     */
    private fun jsonObjectToMap(obj: com.google.gson.JsonObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for ((key, value) in obj.entrySet()) {
            map[key] = jsonElementToAny(value)
        }
        return map
    }

    private fun jsonElementToAny(el: com.google.gson.JsonElement): Any? {
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive -> {
                val prim = el.asJsonPrimitive
                when {
                    prim.isBoolean -> prim.asBoolean
                    prim.isNumber -> prim.asNumber
                    else -> prim.asString
                }
            }
            el.isJsonArray -> el.asJsonArray.map { jsonElementToAny(it) }
            el.isJsonObject -> jsonObjectToMap(el.asJsonObject)
            else -> null
        }
    }

    /**
     * 递归遍历 form 节点，提取隐藏字段和可见题目
     */
    @Suppress("UNCHECKED_CAST")
    private fun walkFormNode(
        node: Any?,
        meta: MutableMap<String, String>,
        questions: MutableList<FormQuestion>,
        parentCols: List<Any?>?,
        indexInParent: Int
    ) {
        when (node) {
            is Map<*, *> -> {
                val view = node["view"]?.toString() ?: ""
                val hidden = node["hidden"]
                val isHidden = hidden == true || hidden == "true" || hidden == "True"

                if (isHidden && view in listOf("text", "hidden")) {
                    // 隐藏字段 -> 存入 meta
                    val key = (node["id"] ?: node["name"])?.toString()
                    if (key != null) {
                        meta[key] = node["value"]?.toString() ?: ""
                    }
                } else if (view in listOf("radio", "textarea", "text", "select") && !isHidden) {
                    val qid = (node["id"] ?: node["name"])?.toString()
                    var qname = (node["label"] ?: node["value"])?.toString()

                    // 特殊处理：radio 没有 label 时从兄弟节点找
                    if ((view == "radio" && qname.isNullOrEmpty()) || view == "textarea") {
                        if (parentCols != null && indexInParent >= 0) {
                            if (view == "textarea" && indexInParent - 1 >= 0) {
                                val prev = parentCols[indexInParent - 1]
                                if (prev is Map<*, *>) {
                                    qname = (prev["value"] ?: prev["label"])?.toString() ?: qname
                                }
                            }
                            if (view == "radio" && qname.isNullOrEmpty()) {
                                for (cand in parentCols) {
                                    if (cand is Map<*, *> && cand["view"] == "label") {
                                        qname = (cand["label"] ?: cand["value"])?.toString()
                                        if (!qname.isNullOrEmpty()) break
                                    }
                                }
                            }
                        }
                    }

                    if (qid != null && !qname.isNullOrEmpty()) {
                        // 解析选项
                        val rawOptions = node["options"]
                        val options = if (rawOptions is List<*>) {
                            rawOptions.mapNotNull { opt ->
                                if (opt is Map<*, *>) {
                                    FormOption(
                                        id = opt["id"]?.toString() ?: "",
                                        value = opt["value"]?.toString() ?: ""
                                    )
                                } else null
                            }
                        } else null

                        questions.add(
                            FormQuestion(
                                id = qid,
                                name = qname,
                                view = view,
                                options = options
                            )
                        )
                    }
                }

                // 递归遍历子节点
                for (key in listOf("elements", "rows", "cols")) {
                    val arr = node[key]
                    if (arr is List<*>) {
                        for ((i, child) in arr.withIndex()) {
                            val pc = if (key == "cols") arr else null
                            val idx = if (key == "cols") i else -1
                            walkFormNode(child, meta, questions, pc, idx)
                        }
                    }
                }
            }
            is List<*> -> {
                for (child in node) {
                    walkFormNode(child, meta, questions, null, -1)
                }
            }
        }
    }

    /**
     * 从选项列表中选择最优选项
     */
    private fun chooseOptionValue(options: List<FormOption>?, desired: String?): String? {
        if (options.isNullOrEmpty()) return null

        // 如果指定了期望值，尝试匹配
        if (!desired.isNullOrEmpty()) {
            // 精确匹配 value
            for (opt in options) {
                if (opt.value == desired) return opt.id
            }
            // 包含匹配
            for (opt in options) {
                if (desired in opt.value) return opt.id
            }
        }

        // 启发式选择最优：优先含"优/是/有"
        val bestLabels = listOf("优", "是", "有")
        for (label in bestLabels) {
            for (opt in options) {
                if (label in opt.value) return opt.id
            }
        }

        // 选数值最大的
        var bestOpt: FormOption? = null
        var bestScore: Double? = null
        for (opt in options) {
            val num = opt.id.toDoubleOrNull() ?: opt.value.toDoubleOrNull()
            if (num != null && (bestScore == null || num > bestScore)) {
                bestScore = num
                bestOpt = opt
            }
        }
        if (bestOpt != null) return bestOpt.id

        // 兜底：返回第一个
        return options.firstOrNull()?.id
    }
}
