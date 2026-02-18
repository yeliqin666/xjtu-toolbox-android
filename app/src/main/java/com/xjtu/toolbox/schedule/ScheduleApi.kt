package com.xjtu.toolbox.schedule

import android.util.Log
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.ui.ScheduleSlot
import com.xjtu.toolbox.util.safeInt
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
     * FineReport 专用 client：手动跟随重定向并强制 HTTPS
     * FineReport 报表可能 302 到 HTTP(port 80)，校外无法直连 → 超时
     * 解决方案：禁用自动重定向 + 应用级拦截器手动跟随并升级 HTTPS
     * （不能用 NetworkInterceptor，因为 ConnectInterceptor 在其之前已尝试 TCP 连接）
     */
    private val frClient by lazy {
        login.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                var request = chain.request()
                // 确保初始请求也是 HTTPS
                if (request.url.scheme == "http") {
                    request = request.newBuilder()
                        .url(request.url.newBuilder().scheme("https").port(443).build())
                        .build()
                }
                var response = chain.proceed(request)
                var count = 0
                while (response.isRedirect && count < 10) {
                    val location = response.header("Location") ?: break
                    response.close()
                    val nextUrl = request.url.resolve(location) ?: break
                    val safeUrl = if (nextUrl.scheme == "http" && nextUrl.host.endsWith("xjtu.edu.cn")) {
                        nextUrl.newBuilder().scheme("https").port(443).build()
                    } else nextUrl
                    Log.d(TAG, "FR redirect($count): ${request.url.host}${request.url.encodedPath} → ${safeUrl.scheme}://${safeUrl.host}${safeUrl.encodedPath}")
                    request = request.newBuilder().url(safeUrl).build()
                    response = chain.proceed(request)
                    count++
                }
                response
            }
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
        val json = JsonParser.parseString(responseBody).asJsonObject
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
        val json = JsonParser.parseString(responseBody).asJsonObject
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
        val json = JsonParser.parseString(responseBody).asJsonObject
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
        val json = JsonParser.parseString(responseBody).asJsonObject
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

        // Step 1: GET 帆软报表初始页面（与成绩报表同模式，不需要 BBKEY/QUERYID）
        // BBKEY/QUERYID 是 EMAP 会话令牌，硬编码会触发"报表数据越权"
        val initUrl = "$baseUrl/jwapp/sys/frReport2/show.do" +
                "?reportlet=jcgl/wdjc.cpt" +
                "&xh=$studentId" +
                "&xnxqdm=$term"
        val initRequest = Request.Builder().url(initUrl).get().build()
        val initHtml = frClient.newCall(initRequest).execute().use { it.body?.string() ?: "" }
        Log.d(TAG, "getTextbooks: init response len=${initHtml.length}, preview=${initHtml.take(500)}")

        // Step 2: 提取 FR Session ID（与 ScoreReportApi 相同模式）
        val sessionId = extractFrSessionId(initHtml)
        if (sessionId == null) {
            Log.w(TAG, "getTextbooks: no sessionID found in init HTML")
            return emptyList()
        }
        Log.d(TAG, "getTextbooks: sessionId=$sessionId")

        // Step 3: 获取报表内容页（带 timestamp 和 boxModel）
        return fetchAndParseContent(sessionId)
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

    private fun extractTotalPages(html: String): Int {
        val pattern = Regex("""FR\._p\.reportTotalPage\s*=\s*(\d+)""")
        return pattern.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun fetchAndParseContent(sessionId: String): List<TextbookItem> {
        val frUrl = "$baseUrl/jwapp/sys/frReport2/show.do"
        // 获取第一页
        val firstPageUrl = "$frUrl?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=1"
        val firstPageReq = Request.Builder().url(firstPageUrl).get().build()
        val firstPageHtml = frClient.newCall(firstPageReq).execute().use { it.body?.string() ?: "" }
        Log.d(TAG, "getTextbooks: page 1 len=${firstPageHtml.length}, preview=${firstPageHtml.take(300)}")

        val totalPages = extractTotalPages(firstPageHtml)
        Log.d(TAG, "getTextbooks: totalPages=$totalPages")

        val allItems = mutableListOf<TextbookItem>()
        allItems.addAll(parseTextbookTable(firstPageHtml))

        // 获取后续页
        for (pn in 2..totalPages) {
            val pageUrl = "$frUrl?_=${System.currentTimeMillis()}&__boxModel__=true&op=page_content&sessionID=$sessionId&pn=$pn"
            val pageReq = Request.Builder().url(pageUrl).get().build()
            val pageHtml = frClient.newCall(pageReq).execute().use { it.body?.string() ?: "" }
            Log.d(TAG, "getTextbooks: page $pn len=${pageHtml.length}")
            allItems.addAll(parseTextbookTable(pageHtml))
        }
        return allItems
    }

    private fun parseTextbookTable(html: String): List<TextbookItem> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table")
        if (tables.isEmpty()) return emptyList()

        // FineReport 通常用最大的 table 渲染数据
        val dataTable = tables.maxByOrNull { it.select("tr").size } ?: return emptyList()
        val rows = dataTable.select("tr")
        if (rows.size < 2) return emptyList()

        // FineReport 的表格结构：前几行可能是标题/副标题（带 rowSpan/colSpan），
        // 真正的列头行是包含多个独立单元格且含有关键词的行。
        // 扫描所有行，找到真正的列头行
        var headerRowIndex = -1
        val colMap = mutableMapOf<String, Int>()

        for (ri in rows.indices) {
            val cells = rows[ri].select("td, th").map { it.text().trim() }
            // 列头行至少有 3 个有效单元格，且包含课程相关关键词
            if (cells.size >= 3 && cells.any { "课程" in it } && cells.any { "书名" in it || "教材" in it || "ISBN" in it.uppercase() }) {
                headerRowIndex = ri
                cells.forEachIndexed { index, header ->
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
                Log.d(TAG, "parseTextbookTable: found header at row $ri, cells=$cells, colMap=$colMap")
                break
            }
        }

        // 如果未找到列头行，尝试回退：取单元格数 >= 4 的第一行作为列头
        if (headerRowIndex == -1) {
            for (ri in rows.indices) {
                val cells = rows[ri].select("td, th").map { it.text().trim() }
                if (cells.size >= 4 && cells.count { it.isNotBlank() } >= 4) {
                    headerRowIndex = ri
                    // 按 FineReport 教材报表的标准列序：课程号、课程名、开课单位、书名、ISBN、主编、版次、出版社
                    if (cells.size >= 8) {
                        colMap["course"] = 1   // 课程名
                        colMap["textbook"] = 3 // 书名
                        colMap["isbn"] = 4
                        colMap["author"] = 5   // 主编
                        colMap["edition"] = 6  // 版次
                        colMap["publisher"] = 7 // 出版社
                    } else {
                        colMap["course"] = 0
                        colMap["textbook"] = 1
                        if (cells.size > 2) colMap["author"] = 2
                        if (cells.size > 3) colMap["publisher"] = 3
                        if (cells.size > 4) colMap["isbn"] = 4
                    }
                    Log.d(TAG, "parseTextbookTable: fallback header at row $ri, cells=$cells")
                    break
                }
            }
        }

        if (headerRowIndex == -1) {
            Log.w(TAG, "parseTextbookTable: no header row found")
            return emptyList()
        }

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
        Log.d(TAG, "parseTextbookTable: parsed ${textbooks.size} textbook items")
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
            val json = JsonParser.parseString(responseBody).asJsonObject
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
