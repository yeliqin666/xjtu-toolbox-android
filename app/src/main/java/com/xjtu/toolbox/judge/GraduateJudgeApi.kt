package com.xjtu.toolbox.judge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.GmisSession
import com.xjtu.toolbox.auth.GsteSession
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate

// ==================== 数据类 ====================

/**
 * 研究生评教问卷基本信息，来自 gste `sshd4Stu/list.do`。
 *
 * 服务器字段是小写拼音缩写；提交和拉题目时要把这 16 个字段原样带回去（见 [params]），
 * 所以 [raw] 保留服务器给的原始字符串，别用解析后的值拼。
 */
data class GraduateQuestionnaire(
    /** already = 已评，allow = 待评 */
    val assessment: String,
    val kcbh: String,
    val kcmc: String,
    val jsxm: String,
    val skls_duty: String,
    val termname: String,
    private val raw: Map<String, String>,
) {
    val finished: Boolean get() = assessment == "already"
    val key: String get() = "${raw["data_jxb_id"]}_${raw["data_jxb_js_id"]}_${raw["jsbh"]}"

    /** genForm.do / saveForm.do 都要求带上的问卷定位参数，键名与服务器一致。 */
    fun params(): Map<String, String> = PARAM_KEYS.associateWith { raw[it].orEmpty() }

    companion object {
        val PARAM_KEYS = listOf(
            "assessment", "bjid", "bjmc", "data_jxb_id", "data_jxb_js_id", "jsbh", "jsxm", "jxb_sj_ok",
            "kcbh", "kcmc", "kcywmc", "kkdw", "lang", "skls_duty", "termcode", "termname",
        )
    }
}

/**
 * 问卷里的一道题。
 * @param id 提交字段名（控件 id/name）
 * @param name 题目名称（控件 label，或同一行前面那个标签的文字）
 * @param view webix 控件类型：radio / select / textarea / text
 * @param options 单选题的选项，每项 `id`（提交值）+ `value`（显示文字）
 */
data class GraduateQuestionItem(
    val id: String,
    val name: String,
    val view: String,
    val options: List<Pair<String, String>>,
)

/**
 * 一张问卷的题目、隐藏字段和作答。页面里是 webix 表单定义 `pjzbApp.form = {...}`，
 * 解析规则见 [GraduateJudgeApi.parseForm]。
 */
class GraduateQuestionnaireData(
    val questions: List<GraduateQuestionItem>,
    /** 表单里 hidden 的 text/hidden 控件：id -> value，提交时原样带回 */
    val meta: Map<String, String>,
    /** form.rules 里的必填字段 id */
    val requiredIds: Set<String>,
) {
    /** 题目 id -> 提交值（单选为选项 id，文本为内容） */
    val answers = LinkedHashMap<String, String>()

    private val byId = questions.associateBy { it.id }
    private val byName = questions.groupBy { norm(it.name) }

    fun unansweredRequired(): Set<String> = requiredIds - answers.keys

    /** 精确匹配题目名，匹配不到退回包含匹配（去空白后）。 */
    fun findByName(name: String): List<GraduateQuestionItem> {
        val key = norm(name)
        byName[key]?.let { return it }
        if (key.isEmpty()) return emptyList()
        return byName.filterKeys { key in it }.values.flatten()
    }

    fun setById(id: String, value: String) {
        val q = byId[id] ?: return
        answers[q.id] = resolve(q, value)
    }

    /** 表单没有这道题就跳过；真正要紧的必填项最后由 [unansweredRequired] 把关。 */
    fun setByName(name: String, value: String) {
        findByName(name).forEach { answers[it.id] = resolve(it, value) }
    }

    fun setAllTextarea(value: String) {
        questions.filter { it.view == "textarea" }.forEach { answers[it.id] = value }
    }

    private fun resolve(q: GraduateQuestionItem, value: String): String =
        if (q.view == "radio" || q.view == "select") chooseOption(q, value) ?: value else value

    /**
     * 把「人话」转换成选项 id：先当 id 精确匹配，再按显示文字精确 / 包含匹配；
     * 都对不上时挑最好的选项（显示文字含「优 / 是 / 有」，否则数值最大），再不行取第一个。
     */
    private fun chooseOption(q: GraduateQuestionItem, desired: String?): String? {
        val opts = q.options
        if (opts.isEmpty()) return desired
        if (!desired.isNullOrEmpty()) {
            opts.firstOrNull { it.first == desired }?.let { return it.first }
            opts.firstOrNull { it.second == desired }?.let { return it.first }
            opts.firstOrNull { desired in it.second }?.let { return it.first }
        }
        for (label in listOf("优", "是", "有")) {
            opts.firstOrNull { label in it.second }?.let { return it.first }
        }
        opts.mapNotNull { op -> (op.first.toDoubleOrNull() ?: op.second.toDoubleOrNull())?.let { op to it } }
            .maxByOrNull { it.second }
            ?.let { return it.first.first }
        return opts.first().first
    }

    private companion object {
        fun norm(text: String) = text.filterNot { it.isWhitespace() }
    }
}

/** gmis 课程详情里评教要用的两项。 */
data class GraduateLessonInfo(
    /** 课程教材表第一行的「教程名称」；没有教材为 null */
    val textbook: String?,
    /** 如「全英文授课」「中文授课」 */
    val teachingLanguage: String,
)

// ==================== API ====================

/**
 * 研究生评教（gste.xjtu.edu.cn），移植自 XJTUToolBox 的 gste/judge.py。
 *
 * 问卷里除了打分，还要填教材情况、授课语言、学位课/选修课——这些从研究生管理信息系统
 * （gmis）的课程详情页和成绩页取。gmis 只有真正提交时才用得到，所以 [gmisProvider]
 * 到那时才调用（才去登录）——只看列表不多走一次 CAS、不多弹一次短信验证。
 * gste 只在校园网内可达，校外经 WebVPN。
 */
class GraduateJudgeApi(
    private val gste: SiteSession,
    private val gmisProvider: suspend () -> SiteSession,
) {
    private val gmisLock = Mutex()
    private var gmisSession: SiteSession? = null

    private suspend fun gmis(): SiteSession =
        gmisSession ?: gmisLock.withLock { gmisSession ?: gmisProvider().also { gmisSession = it } }

    private suspend fun execute(site: SiteSession, request: Request): String =
        site.executeWithReAuth(request).use { response ->
            if (!response.isSuccessful) throw RuntimeException("${site.siteName} HTTP ${response.code}")
            response.body?.string() ?: ""
        }

    /** 本学期全部问卷；已评 / 待评看 [GraduateQuestionnaire.finished]。 */
    suspend fun getQuestionnaires(): List<GraduateQuestionnaire> {
        val body = execute(gste, Request.Builder().url(GsteSession.LIST_URL).get().build())
        val array = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull()
            ?: throw RuntimeException("评教问卷列表格式错误")
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val raw = o.entrySet().associate { (k, v) -> k.lowercase() to v.safeString() }
            GraduateQuestionnaire(
                assessment = raw["assessment"].orEmpty(),
                kcbh = raw["kcbh"].orEmpty(),
                kcmc = raw["kcmc"].orEmpty(),
                jsxm = raw["jsxm"].orEmpty(),
                skls_duty = raw["skls_duty"].orEmpty(),
                termname = raw["termname"].orEmpty(),
                raw = raw,
            )
        }
    }

    suspend fun getQuestionnaireData(q: GraduateQuestionnaire): GraduateQuestionnaireData {
        val url = FORM_URL.toHttpUrl().newBuilder().apply {
            q.params().forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val html = execute(gste, Request.Builder().url(url).get().build())
        return parseForm(html) ?: throw RuntimeException("无法解析「${q.kcmc}」的评教问卷")
    }

    /** gmis 课程详情；学年按 9 月切换，与网页默认一致。 */
    suspend fun getLessonInfo(kcbh: String, today: LocalDate = LocalDate.now()): GraduateLessonInfo {
        val year = if (today.monthValue >= 9) today.year else today.year - 1
        val html = execute(gmis(),
            Request.Builder().url("https://gmis.xjtu.edu.cn/pyxx/pygl/kckk/view/new/$kcbh/$year").get().build()
        )
        return parseLessonInfo(Jsoup.parse(html))
    }

    /** 成绩页「学位课程」表里的课程名，用来判断问卷的「选修情况」。 */
    suspend fun getDegreeCourseNames(): Set<String> {
        val html = execute(gmis(), Request.Builder().url(GmisSession.SCORE_URL).get().build())
        return parseDegreeCourseNames(Jsoup.parse(html))
    }

    suspend fun submitQuestionnaire(q: GraduateQuestionnaire, data: GraduateQuestionnaireData) {
        val missing = data.unansweredRequired()
        if (missing.isNotEmpty()) throw IllegalStateException("「${q.kcmc}」还有 ${missing.size} 道必填题没填")
        val fields = LinkedHashMap<String, String>().apply {
            putAll(q.params())
            putAll(data.meta)
            putAll(data.answers)
        }
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val body = execute(gste, Request.Builder().url(SAVE_URL).post(form).build())
            .ifEmpty { throw RuntimeException("空响应") }
        val root = body.safeParseJsonObject()
        if (!root.get("ok").safeBoolean()) {
            throw RuntimeException(root.get("msg").safeStringOrNull() ?: "提交失败（${root.get("code").safeString()}）")
        }
    }

    /** 自动评完一门：拉题目 → 取课程信息 → 填写 → 提交。 */
    suspend fun autoJudge(q: GraduateQuestionnaire, degreeCourses: Set<String>, grade: Int = 3) {
        val data = getQuestionnaireData(q)
        val lesson = getLessonInfo(q.kcbh)
        completeQuestionnaire(q, data, lesson, isDegreeCourse = q.kcmc in degreeCourses, grade = grade)
        submitQuestionnaire(q, data)
    }

    companion object {
        private const val FORM_URL = "http://gste.xjtu.edu.cn/app/student/genForm.do"
        private const val SAVE_URL = "http://gste.xjtu.edu.cn/app/student/saveForm.do"
        const val DEFAULT_COMMENT = "老师授课认真，课程收益良多。"

        /**
         * 填好一张问卷。不覆盖已填的题。
         * @param grade 0..3 对应 不合格 / 合格 / 良好 / 优秀。系统不允许全部「优秀」，
         *   选 3 时会把第一道「优秀」开头的单选改成「良好」。
         * @param comment 所有主观题的内容
         */
        fun completeQuestionnaire(
            q: GraduateQuestionnaire,
            data: GraduateQuestionnaireData,
            lesson: GraduateLessonInfo,
            isDegreeCourse: Boolean,
            grade: Int = 3,
            comment: String = DEFAULT_COMMENT,
        ) {
            val level = grade.coerceIn(0, 3)
            data.setByName("课程名称", q.kcmc)
            data.setByName("上课教师", q.jsxm)
            // 网页上没有教材显示「无指定书籍」，只有讲义显示「自编讲义」；
            // 「教材情况」选项 id：0 无教材或讲义，1 自编讲义，2 有教材。
            val book = lesson.textbook?.takeIf { it.isNotBlank() && it != "无指定书籍" }
            if (book != null) {
                data.setByName("教材情况", if ("自编讲义" in book) "1" else "2")
                data.setByName("教材名称", book)
                data.setByName("教材使用语言", if (book.all { it.code < 128 }) "英文" else "中文")
            } else {
                data.setByName("教材情况", "0")
                // 没有教材也得填教材名称和语言，不然过不了必填校验
                data.setByName("教材名称", "无")
                data.setByName("教材使用语言", "无教材")
            }
            val language = lesson.teachingLanguage.trim('授', '课').let { if (it == "全中文") "中文" else it }
            data.setByName("授课语言", if (language in setOf("全英文", "中英文", "中文")) language else "其他")
            data.setByName("选修情况", if (isDegreeCourse) "学位课" else "选修课")

            data.setAllTextarea(comment)
            val gradeText = listOf("不合格", "合格", "良好", "优秀")[level]
            data.questions.filter { it.view == "radio" && it.id !in data.answers }
                .forEach { data.setById(it.id, gradeText) }
            if (level == 3) {
                data.questions.firstOrNull { it.view == "radio" && it.options.firstOrNull()?.second == "优秀" }
                    ?.let { data.setById(it.id, "良好") }
            }
        }

        /**
         * 从整页 HTML 里取出 `pjzbApp.form = {...}` 并解析题目。
         * 对象里夹着 `webix.rules.isNotEmpty` 这类 JS 引用，先换成字符串再当 JSON 解析。
         */
        internal fun parseForm(html: String): GraduateQuestionnaireData? {
            val anchor = html.indexOf("pjzbApp.form").takeIf { it >= 0 } ?: return null
            val eq = html.indexOf('=', anchor).takeIf { it >= 0 } ?: return null
            val start = html.indexOf('{', eq).takeIf { it >= 0 } ?: return null
            val end = matchingBrace(html, start) ?: return null
            val text = html.substring(start, end).replace(Regex(""":\s*webix\.rules\.\w+"""), ": \"isNotEmpty\"")
            val form = (runCatching { JsonParser.parseString(text) }.getOrNull()
                ?: runCatching { JsonParser.parseString(text.replace(Regex(""",\s*([}\]])"""), "$1")) }.getOrNull())
                as? JsonObject ?: return null

            val questions = mutableListOf<GraduateQuestionItem>()
            val meta = LinkedHashMap<String, String>()
            walk(form, null, -1, questions, meta)
            val required = (form.get("rules") as? JsonObject)?.keySet()?.toSet().orEmpty()
            return GraduateQuestionnaireData(questions, meta, required)
        }

        /** 配对括号找对象结尾（跳过字符串里的括号）。返回结尾下标 + 1。 */
        private fun matchingBrace(s: String, start: Int): Int? {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until s.length) {
                val ch = s[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                } else when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i + 1
                }
            }
            return null
        }

        private fun JsonObject.text(key: String): String? =
            get(key).safeStringOrNull()?.takeIf { it.isNotEmpty() }

        /**
         * 递归遍历 webix 表单。[siblings]/[index] 是节点在所属 `cols` 里的位置：
         * 文本框的题目名写在同一行前一个控件里，没标题的单选则取同一行的 label 控件。
         */
        private fun walk(
            node: JsonElement,
            siblings: JsonArray?,
            index: Int,
            questions: MutableList<GraduateQuestionItem>,
            meta: MutableMap<String, String>,
        ) {
            if (node.isJsonArray) {
                node.asJsonArray.forEach { walk(it, null, -1, questions, meta) }
                return
            }
            val obj = node as? JsonObject ?: return
            val view = obj.get("view").safeString()
            val hidden = obj.get("hidden").safeString().equals("true", ignoreCase = true)
            if (hidden && (view == "text" || view == "hidden")) {
                (obj.text("id") ?: obj.text("name"))?.let { meta[it] = obj.get("value").safeString() }
            } else if (!hidden && view in setOf("radio", "textarea", "text", "select")) {
                val id = obj.text("id") ?: obj.text("name")
                var name = obj.text("label") ?: obj.text("value")
                if (siblings != null && ((view == "radio" && name == null) || view == "textarea")) {
                    if (view == "textarea" && index - 1 >= 0) {
                        (siblings[index - 1] as? JsonObject)?.let { prev -> name = prev.text("value") ?: prev.text("label") }
                    }
                    if (view == "radio" && name == null) {
                        name = siblings.asSequence()
                            .mapNotNull { it as? JsonObject }
                            .filter { it.get("view").safeString() == "label" }
                            .firstNotNullOfOrNull { it.text("label") ?: it.text("value") }
                    }
                }
                val options = (obj.get("options") as? JsonArray)?.mapNotNull { op ->
                    (op as? JsonObject)?.let { it.get("id").safeString() to it.get("value").safeString() }
                }.orEmpty()
                if (id != null && name != null) questions += GraduateQuestionItem(id, name!!, view, options)
            }
            for (key in listOf("elements", "rows", "cols")) {
                val children = obj.get(key) as? JsonArray ?: continue
                children.forEachIndexed { i, child ->
                    if (key == "cols") walk(child, children, i, questions, meta)
                    else walk(child, null, -1, questions, meta)
                }
            }
        }

        /** 课程详情页：`td.tdCaption` 是字段名，紧挨着的下一个 td 是值；教材在 `table#jcxx`。 */
        internal fun parseLessonInfo(doc: Document): GraduateLessonInfo {
            fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            val captions = doc.select("td.tdCaption").associate { cap ->
                norm(cap.text()).trimEnd('：', ':').trim() to norm(cap.nextElementSibling()?.text().orEmpty())
            }
            val textbook = doc.selectFirst("table#jcxx")?.let { table ->
                if (table.select("tbody tr").any { "没有相关数据" in it.text() }) return@let null
                val headers = table.select("thead th, thead td").map { norm(it.text()) }
                val column = headers.indexOf("教程名称").takeIf { it >= 0 } ?: return@let null
                table.select("tbody > tr")
                    .map { tr -> tr.select("> td").map { norm(it.text()) } }
                    .firstOrNull { cells -> cells.any { it.isNotEmpty() } }
                    ?.getOrNull(column)
            }
            return GraduateLessonInfo(textbook = textbook, teachingLanguage = captions["授课语言"].orEmpty())
        }

        /** 成绩页有三张 `table#sample-table-1`，依次是 学位课程 / 选修课程 / 必修环节。 */
        internal fun parseDegreeCourseNames(doc: Document): Set<String> =
            doc.select("table#sample-table-1").firstOrNull()
                ?.select("tr")?.drop(1)
                ?.mapNotNull { tr -> tr.selectFirst("> td")?.text()?.trim()?.takeIf { it.isNotEmpty() } }
                ?.toSet()
                .orEmpty()
    }
}
