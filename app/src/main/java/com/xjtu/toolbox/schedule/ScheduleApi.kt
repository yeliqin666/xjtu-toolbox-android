package com.xjtu.toolbox.schedule

import android.util.Log
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.ui.ScheduleSlot
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeParseJsonObject
import com.xjtu.toolbox.util.safeString
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val TAG = "ScheduleApi"

data class CourseItem(
    val courseName: String,
    val teacher: String,
    val location: String,
    val weekBits: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val courseCode: String,
    val courseType: String
) : ScheduleSlot {
    override val slotName get() = courseName
    override val slotLocation get() = location
    override val slotDayOfWeek get() = dayOfWeek
    override val slotStartSection get() = startSection
    override val slotEndSection get() = endSection

    fun getWeeks(): List<Int> = weekBits.mapIndexedNotNull { index, c -> if (c == '1') index + 1 else null }

    fun isInWeek(week: Int): Boolean {
        val idx = week - 1
        return idx in weekBits.indices && weekBits[idx] == '1'
    }
}

data class ExamItem(
    val courseName: String,
    val courseCode: String,
    val examDate: String,
    val examTime: String,
    val location: String,
    val seatNumber: String
)

data class TextbookItem(
    val courseName: String,
    val textbookName: String,
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
    val price: String = "",
    val edition: String = ""
)

class ScheduleApi(private val login: JwxtLogin) {

    private val baseUrl = "https://jwxt.xjtu.edu.cn"
    private var cachedTermCode: String? = null

    /**
     * FineReport 专用 client：加长超时（帆软报表渲染可能需要 30s+）
     */
    private val frClient by lazy {
        login.client.newBuilder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun getCurrentTerm(): String {
        cachedTermCode?.let { return it }
        val request = Request.Builder()
            .url("$baseUrl/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do")
            .post(FormBody.Builder().build())
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = responseBody.safeParseJsonObject()
        val code = json.getAsJsonObject("datas")
            .getAsJsonObject("dqxnxq")
            .getAsJsonArray("rows")[0].asJsonObject
            .get("DM").asString
        cachedTermCode = code
        return code
    }

    fun getSchedule(termCode: String? = null): List<CourseItem> {
        val term = termCode ?: getCurrentTerm()
        val formBody = FormBody.Builder().add("XNXQDM", term).build()
        val request = Request.Builder()
            .url("$baseUrl/jwapp/sys/wdkb/modules/xskcb/xskcb.do")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = responseBody.safeParseJsonObject()
        val rows = json.getAsJsonObject("datas")
            .getAsJsonObject("xskcb")
            .getAsJsonArray("rows") ?: return emptyList()

        // 首条记录打印全部字段(调试用)
        if (rows.size() > 0) {
            val sample = rows[0].asJsonObject
            Log.d(TAG, "schedule sample keys: ${sample.keySet()}")
            Log.d(TAG, "schedule KCXZDM=${sample.get("KCXZDM")}, KCXZDM_DISPLAY=${sample.get("KCXZDM_DISPLAY")}, KCXZMC=${sample.get("KCXZMC")}, KCFLMC=${sample.get("KCFLMC")}")
        }

        return rows.map { item ->
            val obj = item.asJsonObject
            // 课程性质：优先 KCXZMC（课程性质名称），回退 KCXZDM_DISPLAY / KCFLMC
            val courseType = obj.get("KCXZMC").safeString().ifEmpty {
                obj.get("KCXZDM_DISPLAY").safeString().ifEmpty {
                    obj.get("KCFLMC").safeString()
                }
            }
            CourseItem(
                courseName = obj.get("KCM").safeString(),
                teacher = obj.get("SKJS").safeString(),
                location = obj.get("JASMC").safeString(),
                weekBits = obj.get("SKZC").safeString(),
                dayOfWeek = obj.get("SKXQ").safeInt(1),
                startSection = obj.get("KSJC").safeInt(1),
                endSection = obj.get("JSJC").safeInt(1),
                courseCode = obj.get("KCH").safeString(),
                courseType = courseType
            )
        }
    }

    fun getExamSchedule(termCode: String? = null): List<ExamItem> {
        val term = termCode ?: getCurrentTerm()
        val formBody = FormBody.Builder()
            .add("XNXQDM", term)
            .add("*order", "-KSRQ,-KSSJMS")
            .build()

        val request = Request.Builder()
            .url("$baseUrl/jwapp/sys/studentWdksapApp/modules/wdksap/wdksap.do")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = responseBody.safeParseJsonObject()
        val rows = json.getAsJsonObject("datas")
            .getAsJsonObject("wdksap")
            .getAsJsonArray("rows") ?: return emptyList()

        return rows.map { item ->
            val obj = item.asJsonObject
            val rawDate = obj.get("KSRQ").safeString()
            val rawTimeDesc = obj.get("KSSJMS").safeString()
            // 从 KSRQ 提取日期部分（去掉时间 "2025-01-15 00:00:00" → "2025-01-15"）
            val examDate = rawDate.split(" ").firstOrNull() ?: rawDate
            // KSSJMS 可能包含日期，去除与 examDate 重复的部分
            val examTime = rawTimeDesc
                .replace(examDate, "")   // 去除日期
                .replace(rawDate, "")    // 去除完整日期（含时间）
                .trim()
                .trimStart('-', ' ')
            ExamItem(
                courseName = obj.get("KCM").safeString().ifEmpty {
                    obj.get("KCMC").safeString().ifEmpty {
                        obj.get("KCH").safeString()  // 最后 fallback 到课程代码
                    }
                },
                courseCode = obj.get("KCH").safeString(),
                examDate = examDate,
                examTime = examTime.ifEmpty { rawTimeDesc },  // fallback 到原始描述
                location = obj.get("JASMC").safeString(),
                seatNumber = obj.get("ZWH").safeString()
            )
        }
    }

    fun getStartOfTerm(termCode: String? = null): LocalDate {
        val term = termCode ?: getCurrentTerm()
        val parts = term.split("-")
        val formBody = FormBody.Builder()
            .add("XN", "${parts[0]}-${parts[1]}")
            .add("XQ", parts[2])
            .build()

        val request = Request.Builder()
            .url("$baseUrl/jwapp/sys/wdkb/modules/jshkcb/cxjcs.do")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val json = responseBody.safeParseJsonObject()
        val dateStr = json.getAsJsonObject("datas")
            .getAsJsonObject("cxjcs")
            .getAsJsonArray("rows")[0].asJsonObject
            .get("XQKSRQ").asString
            .split(" ")[0]

        return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    /**
     * 获取教材信息（通过 FineReport 报表接口）
     * @param studentId 学号
     * @param termCode 学期代码
     * @return 教材列表
     */
    fun getTextbooks(studentId: String, termCode: String? = null): List<TextbookItem> {
        val term = termCode ?: getCurrentTerm()
        Log.d(TAG, "getTextbooks: studentId=$studentId, term=$term")

        val frUrl = "$baseUrl/jwapp/sys/frReport2/show.do"

        // Step 1: POST 初始化 → 服务器返回带 BBKEY 的自动提交表单页
        val reportletJson = "{'xh':'$studentId','xnxqdm':'$term','reportlet':'jcgl/wdjc.cpt'}"
        val initBody = FormBody.Builder()
            .add("reportlets", "[$reportletJson]")
            .add("__cumulatepagenumber__", "false")
            .build()
        val initRequest = Request.Builder()
            .url(frUrl)
            .post(initBody)
            .header("Referer", "$frUrl?__cumulatepagenumber__=false")
            .build()
        var html = frClient.newCall(initRequest).execute().use { resp ->
            Log.d(TAG, "getTextbooks: init code=${resp.code}, url=${resp.request.url}")
            resp.body?.string() ?: ""
        }
        Log.d(TAG, "getTextbooks: init response len=${html.length}, preview=${html.take(500)}")

        // Step 2: 检测 JS 自动提交表单 → 手动提取并重新 POST
        // 服务器可能返回中间页: <form submitForm> + <script>submit()</script>
        // OkHttp 不执行 JS，需手动解析并提交
        if (html.contains("submitForm") && html.contains(".submit()")) {
            Log.d(TAG, "getTextbooks: detected auto-submit form, extracting and resubmitting")
            val formDoc = Jsoup.parse(html)
            val formAction = formDoc.select("form[name=submitForm]").attr("action")
                .let { if (it.startsWith("http")) it else "$baseUrl$it" }
            val formBuilder = FormBody.Builder()
            formDoc.select("form[name=submitForm] input[type=hidden]").forEach { input ->
                val name = input.attr("name")
                val value = input.attr("value")
                if (name.isNotEmpty()) {
                    formBuilder.add(name, value)
                    Log.d(TAG, "getTextbooks: form field $name=${value.take(80)}")
                }
            }
            val resubmitRequest = Request.Builder()
                .url(formAction)
                .post(formBuilder.build())
                .header("Referer", "$frUrl?__cumulatepagenumber__=false")
                .build()
            html = frClient.newCall(resubmitRequest).execute().use { resp ->
                Log.d(TAG, "getTextbooks: resubmit code=${resp.code}, url=${resp.request.url}")
                resp.body?.string() ?: ""
            }
            Log.d(TAG, "getTextbooks: resubmit response len=${html.length}, preview=${html.take(500)}")
        }

        // 检测是否被重定向到登录页
        if (html.contains("openplatform") || html.contains("login") && !html.contains("SessionMgr")) {
            throw RuntimeException("教材报表会话已过期，请返回重新进入")
        }

        // 检测 FineReport 服务端错误页（数据库连接失败等后端问题）
        if (html.contains("FR-Engine_Error") || html.contains("error_iframe") || html.contains("出错页面")) {
            val serverError = extractFrErrorMessage(html)
            Log.w(TAG, "getTextbooks: FineReport server error: $serverError")
            throw RuntimeException("教务服务器报表异常：$serverError")
        }

        // Step 3: 先尝试从 HTML 直接解析（部分 FineReport 报表会内联渲染数据）
        val inlineResult = parseTextbookTable(html)
        if (inlineResult.isNotEmpty()) {
            Log.d(TAG, "getTextbooks: parsed ${inlineResult.size} items from HTML (inline)")
            return inlineResult
        }

        // Step 4: 提取 FR Session ID → 走 page_content 流程
        val sessionId = extractFrSessionId(html)
        if (sessionId == null) {
            Log.w(TAG, "getTextbooks: no sessionID found in HTML, response: ${html.take(2000)}")
            throw RuntimeException("教材报表初始化失败（未获取到会话ID），请重试")
        }
        Log.d(TAG, "getTextbooks: sessionId=$sessionId")

        // Step 5: 获取报表内容页
        val result = fetchAndParseContent(sessionId)

        // 如果 page_content 返回了 FineReport 错误页，告知用户
        if (result.isEmpty()) {
            Log.w(TAG, "getTextbooks: page_content returned 0 items (possibly FR error page)")
        }
        return result
    }

    private fun extractFrSessionId(html: String): String? {
        // 优先匹配 FR.SessionMgr.register('sessionId', ...)
        val registerPattern = Regex("""FR\.SessionMgr\.register\(\s*['"](\d+)['"]""", RegexOption.IGNORE_CASE)
        registerPattern.find(html)?.let { return it.groupValues[1] }

        // 备选：匹配 sessionID=xxx
        val sessionIdPattern = Regex("""sessionID=(\d+)""", RegexOption.IGNORE_CASE)
        sessionIdPattern.find(html)?.let { return it.groupValues[1] }

        // 备选2：currentSessionID='xxx'
        val currentPattern = Regex("""currentSessionID\s*=\s*['"](\d+)['"]""")
        currentPattern.find(html)?.let { return it.groupValues[1] }

        return null
    }

    /** 从 FineReport 错误页面提取服务器端错误信息 */
    private fun extractFrErrorMessage(html: String): String {
        // FineReport 错误页将错误信息编码为 Unicode 转义 [XXXX] 形式
        // 例: [9519][8bef][4ee3][7801]:1301 → 错误代码:1301
        val messagePattern = Regex("""value='((?:\[\w{4}]|[^'])+)'""")
        val matches = messagePattern.findAll(html).toList()
        for (m in matches) {
            val raw = m.groupValues[1]
            if (raw.contains("[") && raw.length > 10) {
                // 解码 Unicode 转义
                val decoded = raw.replace(Regex("""\[([0-9a-fA-F]{4})]""")) { mr ->
                    mr.groupValues[1].toInt(16).toChar().toString()
                }
                // 提取关键信息（错误代码 + 简短描述）
                val brief = decoded.take(200)
                    .replace("java.lang.RuntimeException:", "")
                    .replace("java.sql.SQLException:", "")
                    .trim()
                if (brief.isNotBlank()) return brief
            }
        }
        // 回退：从 title 提取
        if (html.contains("出错页面")) return "服务器报表引擎出错，请稍后重试"
        return "未知服务器错误"
    }

    private fun extractTotalPages(html: String): Int {
        val pattern = Regex("""FR\._p\.reportTotalPage\s*=\s*(\d+)""")
        return pattern.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun fetchAndParseContent(sessionId: String): List<TextbookItem> {
        val frUrl = "$baseUrl/jwapp/sys/frReport2/show.do"
        // 获取第一页（需要 X-Requested-With + Referer 模拟 AJAX 请求，否则 FR 返回错误页）
        val firstPageUrl = "$frUrl?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=1"
        val firstPageReq = Request.Builder()
            .url(firstPageUrl)
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", frUrl)
            .build()
        val firstPageHtml = frClient.newCall(firstPageReq).execute().use { it.body?.string() ?: "" }
        Log.d(TAG, "getTextbooks: page 1 len=${firstPageHtml.length}, preview=${firstPageHtml.take(300)}")

        // 检测 FineReport 错误页
        if (firstPageHtml.contains("FR-Engine_Error") || firstPageHtml.contains("error_iframe")) {
            Log.w(TAG, "getTextbooks: FineReport returned error page: ${firstPageHtml.take(1000)}")
            throw RuntimeException("教材报表服务端渲染失败，请稍后重试")
        }

        val totalPages = extractTotalPages(firstPageHtml)
        Log.d(TAG, "getTextbooks: totalPages=$totalPages")

        val allItems = mutableListOf<TextbookItem>()
        allItems.addAll(parseTextbookTable(firstPageHtml))

        // 获取后续页
        for (pn in 2..totalPages) {
            val pageUrl = "$frUrl?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=$pn"
            val pageReq = Request.Builder()
                .url(pageUrl)
                .get()
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", frUrl)
                .build()
            val pageHtml = frClient.newCall(pageReq).execute().use { it.body?.string() ?: "" }
            Log.d(TAG, "getTextbooks: page $pn len=${pageHtml.length}")
            allItems.addAll(parseTextbookTable(pageHtml))
        }
        return allItems
    }

    private fun parseTextbookTable(html: String): List<TextbookItem> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table")
        Log.d(TAG, "parseTextbookTable: found ${tables.size} tables")

        // ── 策略1: 标准 <table> 解析 ──
        if (tables.isNotEmpty()) {
            val dataTable = tables.maxByOrNull { it.select("tr").size } ?: return emptyList()
            val rows = dataTable.select("tr")
            Log.d(TAG, "parseTextbookTable: largest table has ${rows.size} rows")

            if (rows.size >= 2) {
                val result = parseTableRows(rows)
                if (result.isNotEmpty()) return result
            }
        }

        // ── 策略2: FineReport div 绝对定位解析 ──
        // FineReport 新版用 <div style="position:absolute;..."> 渲染单元格
        Log.d(TAG, "parseTextbookTable: trying div-based parsing")
        val textDivs = doc.select("div[style*=position]")
            .filter { it.children().isEmpty() || it.select("span").isNotEmpty() }
            .mapNotNull { div ->
                val style = div.attr("style")
                val left = Regex("""left\s*:\s*(\d+(?:\.\d+)?)""").find(style)?.groupValues?.get(1)?.toFloatOrNull()
                val top = Regex("""top\s*:\s*(\d+(?:\.\d+)?)""").find(style)?.groupValues?.get(1)?.toFloatOrNull()
                val text = div.text().trim()
                if (left != null && top != null && text.isNotBlank()) Triple(left, top, text) else null
            }
        Log.d(TAG, "parseTextbookTable: found ${textDivs.size} positioned div cells")

        if (textDivs.isNotEmpty()) {
            val result = parseDivBasedReport(textDivs)
            if (result.isNotEmpty()) return result
        }

        // ── 策略3: 全文本暴力提取 ──
        // 如果以上都失败，从全文中查找所有文本节点
        val allText = doc.body()?.text() ?: ""
        Log.d(TAG, "parseTextbookTable: body text len=${allText.length}, preview=${allText.take(500)}")
        Log.w(TAG, "parseTextbookTable: all strategies failed, html structure: " +
                "tables=${tables.size}, divs=${textDivs.size}, body_preview=${doc.body()?.html()?.take(1000)}")
        return emptyList()
    }

    /** 从 <tr> 行列表中解析教材（原有逻辑） */
    private fun parseTableRows(rows: org.jsoup.select.Elements): List<TextbookItem> {
        var headerRowIndex = -1
        val colMap = mutableMapOf<String, Int>()

        for (ri in rows.indices) {
            val cells = rows[ri].select("td, th").map { it.text().trim() }
            if (cells.size >= 3 && cells.any { "课程" in it } && cells.any { "书名" in it || "教材" in it || "ISBN" in it.uppercase() }) {
                headerRowIndex = ri
                cells.forEachIndexed { index, header ->
                    mapHeaderColumn(header, index, colMap)
                }
                Log.d(TAG, "parseTableRows: found header at row $ri, cells=$cells, colMap=$colMap")
                break
            }
        }

        if (headerRowIndex == -1) {
            for (ri in rows.indices) {
                val cells = rows[ri].select("td, th").map { it.text().trim() }
                if (cells.size >= 4 && cells.count { it.isNotBlank() } >= 4) {
                    headerRowIndex = ri
                    if (cells.size >= 8) {
                        colMap["course"] = 1; colMap["textbook"] = 3; colMap["isbn"] = 4
                        colMap["author"] = 5; colMap["edition"] = 6; colMap["publisher"] = 7
                    } else {
                        colMap["course"] = 0; colMap["textbook"] = 1
                        if (cells.size > 2) colMap["author"] = 2
                        if (cells.size > 3) colMap["publisher"] = 3
                        if (cells.size > 4) colMap["isbn"] = 4
                    }
                    Log.d(TAG, "parseTableRows: fallback header at row $ri, cells=$cells")
                    break
                }
            }
        }

        if (headerRowIndex == -1) {
            Log.w(TAG, "parseTableRows: no header row found, dumping first 5 rows:")
            for (ri in 0..minOf(4, rows.size - 1)) {
                val cells = rows[ri].select("td, th").map { it.text().trim() }
                Log.d(TAG, "  row[$ri]: cells=${cells.size}, content=$cells")
            }
            return emptyList()
        }

        return extractTextbooksFromRows(rows, headerRowIndex, colMap)
    }

    /** 从 div 绝对定位渲染的单元格中解析教材 */
    private fun parseDivBasedReport(cells: List<Triple<Float, Float, String>>): List<TextbookItem> {
        // 按 Y 坐标分组（同一行的 Y 坐标接近）
        val sortedByY = cells.sortedBy { it.second }
        val rows = mutableListOf<MutableList<Pair<Float, String>>>() // [(x, text)]
        var currentRowY = -1f
        var currentRow = mutableListOf<Pair<Float, String>>()

        for ((x, y, text) in sortedByY) {
            if (currentRowY < 0 || kotlin.math.abs(y - currentRowY) > 3f) {
                if (currentRow.isNotEmpty()) rows.add(currentRow)
                currentRow = mutableListOf()
                currentRowY = y
            }
            currentRow.add(x to text)
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        Log.d(TAG, "parseDivBasedReport: ${rows.size} rows detected")
        if (rows.size < 2) return emptyList()

        // 每行按 X 坐标排序
        rows.forEach { it.sortBy { cell -> cell.first } }

        // 查找列头行
        val colMap = mutableMapOf<String, Int>()
        var headerRowIndex = -1
        for (ri in rows.indices) {
            val texts = rows[ri].map { it.second }
            if (texts.size >= 3 && texts.any { "课程" in it } && texts.any { "书名" in it || "教材" in it || "ISBN" in it.uppercase() }) {
                headerRowIndex = ri
                texts.forEachIndexed { index, header -> mapHeaderColumn(header, index, colMap) }
                Log.d(TAG, "parseDivBasedReport: header at row $ri, texts=$texts, colMap=$colMap")
                break
            }
        }

        if (headerRowIndex == -1) return emptyList()

        val textbooks = mutableListOf<TextbookItem>()
        for (ri in (headerRowIndex + 1) until rows.size) {
            val texts = rows[ri].map { it.second }
            if (texts.isEmpty() || texts.all { it.isBlank() }) continue

            fun col(key: String): String = colMap[key]?.let { texts.getOrNull(it) } ?: ""
            val courseName = col("course")
            val textbookName = col("textbook")
            if (courseName.isBlank() && textbookName.isBlank()) continue

            textbooks.add(TextbookItem(courseName, textbookName, col("author"), col("publisher"), col("isbn"), col("price"), col("edition")))
        }
        Log.d(TAG, "parseDivBasedReport: parsed ${textbooks.size} items")
        return textbooks
    }

    /** 列头关键词映射 */
    private fun mapHeaderColumn(header: String, index: Int, colMap: MutableMap<String, Int>) {
        when {
            header == "课程名" || ("课程" in header && "名" in header && "号" !in header) -> colMap["course"] = index
            header == "书名" || "教材名" in header || ("教材" in header && "名" in header) -> colMap["textbook"] = index
            "主编" in header || "作者" in header || "编者" in header -> colMap["author"] = index
            "出版社" in header || ("出版" in header && "社" in header) -> colMap["publisher"] = index
            "ISBN" in header.uppercase() || "书号" in header -> colMap["isbn"] = index
            "价" in header || "定价" in header -> colMap["price"] = index
            "版次" in header || "版本" in header -> colMap["edition"] = index
        }
    }

    /** 从 table 的行中提取教材条目 */
    private fun extractTextbooksFromRows(rows: org.jsoup.select.Elements, headerRowIndex: Int, colMap: Map<String, Int>): List<TextbookItem> {
        val textbooks = mutableListOf<TextbookItem>()
        for (i in (headerRowIndex + 1) until rows.size) {
            val cells = rows[i].select("td, th").map { it.text().trim() }
            if (cells.isEmpty() || cells.all { it.isBlank() }) continue

            fun col(key: String): String = colMap[key]?.let { cells.getOrNull(it) } ?: ""

            val courseName = col("course")
            val textbookName = col("textbook")
            if (courseName.isBlank() && textbookName.isBlank()) continue

            textbooks.add(
                TextbookItem(
                    courseName = courseName,
                    textbookName = textbookName,
                    author = col("author"),
                    publisher = col("publisher"),
                    isbn = col("isbn"),
                    price = col("price"),
                    edition = col("edition")
                )
            )
        }
        Log.d(TAG, "extractTextbooksFromRows: parsed ${textbooks.size} textbook items")
        return textbooks
    }

    /**
     * 获取可用学期列表（从教务系统查询）
     * @return 学期代码列表，如 ["2024-2025-2", "2024-2025-1", "2023-2024-2", ...]
     */
    fun getTermList(): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/jwapp/sys/wdkb/modules/jshkcb/cxxnxqgl.do")
            .post(FormBody.Builder().build())
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }

        return try {
            val json = responseBody.safeParseJsonObject()
            val rows = json.getAsJsonObject("datas")
                .getAsJsonObject("cxxnxqgl")
                .getAsJsonArray("rows")
            rows.map { it.asJsonObject.get("DM").asString }
        } catch (_: Exception) {
            // 如果接口不可用，基于当前学期生成最近 6 个学期
            generateRecentTerms()
        }
    }

    private fun generateRecentTerms(): List<String> {
        val current = getCurrentTerm()
        val parts = current.split("-")
        val year1 = parts[0].toInt()
        val year2 = parts[1].toInt()
        val sem = parts[2].toInt()

        val terms = mutableListOf<String>()
        var y1 = year1; var y2 = year2; var s = sem
        repeat(6) {
            terms.add("$y1-$y2-$s")
            if (s == 1) { y1--; y2--; s = 2 } else { s = 1 }
        }
        return terms
    }
}
