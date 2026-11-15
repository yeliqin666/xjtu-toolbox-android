package com.xjtu.toolbox.qms

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的学生评教 QMS 上游（`qms.xjtu.edu.cn`），请求形状照 `QmsJudgeApi`：
 * 任务列表 → 按 DRAFT / ENABLED 拉分组课程 → 答卷详情 → PUT 提交。
 * 登录与思源同型：没会话就 302 去统一认证，带 ticket 回来种 cookie。
 */
class QmsFakeUpstream {

    companion object {
        const val HOST = "qms.xjtu.edu.cn"
        const val BASE = "https://$HOST"
        const val SESSION_COOKIE = "QMS_SESSION_fake"

        const val TASK_ID = "T-1"
        const val OTHER_TASK_ID = "T-other"
        const val PLAN_TITLE = "2026-2027-1 期末评教"

        /** 未评（DRAFT）两份：一门课两位教师 + 一门课没有教师名。 */
        const val RESPONSE_DRAFT_A = "R-draft-a"
        const val COURSE_A = "高等数学（上）"
        const val LECTURER_A1 = "示例甲"
        const val LECTURER_A2 = "示例乙"

        const val RESPONSE_DRAFT_B = "R-draft-b"
        const val COURSE_B = "大学物理"

        /** 已评（ENABLED）一份。 */
        const val RESPONSE_DONE = "R-done"
        const val COURSE_DONE = "线性代数"
        const val LECTURER_DONE = "示例丙"

        const val SURVEY_ID = "S-1"

        /** 拒收时 QMS 回的 violations 文案。 */
        const val REJECT_MESSAGE = "这份问卷当前不在评教窗口里"
    }

    /** 打开后，PUT 提交回 400 + violations（`QmsJudgeApi` 会抛 `QmsException`）。 */
    val rejectSubmit = AtomicBoolean(false)

    val taskListCalls = AtomicInteger(0)
    val groupedCourseCalls = AtomicInteger(0)
    val detailCalls = AtomicInteger(0)
    val submitCalls = AtomicInteger(0)

    /** 每次 PUT 提交的 (responseId, 请求体)。 */
    val submissions = CopyOnWriteArrayList<Pair<String, String>>()

    /** 提交成功过的答卷：再拉列表时它们从 DRAFT 变成 ENABLED。 */
    private val submitted = ConcurrentHashMap.newKeySet<String>()

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.rawQuery.orEmpty()
        val hasSession = SESSION_COOKIE in exchange.requestHeaders.getFirst("Cookie").orEmpty()
        when {
            !hasSession && "ticket=" !in query -> redirect(
                exchange,
                "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=" + encode("$BASE$path"),
            )
            !hasSession -> {
                exchange.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=yes; Path=/")
                respond(exchange, 200, "text/html; charset=utf-8", "<html><body>quality-client</body></html>")
            }
            path == "/api/quality/my/tasks" -> {
                taskListCalls.incrementAndGet()
                respondJson(exchange, tasksJson())
            }
            path == "/api/quality/tasks/$TASK_ID/student-evaluation/grouped-courses" -> {
                groupedCourseCalls.incrementAndGet()
                respondJson(exchange, groupedCoursesJson(param(query, "status").orEmpty()))
            }
            path.startsWith("/api/quality/responses/") && exchange.requestMethod == "GET" -> {
                detailCalls.incrementAndGet()
                respondJson(exchange, detailJson(path.substringAfterLast('/')))
            }
            path.startsWith("/api/quality/more-survey/responses/") && exchange.requestMethod == "PUT" -> {
                val id = path.substringAfterLast('/')
                val body = exchange.requestBody.readBytes().decodeToString()
                submitCalls.incrementAndGet()
                submissions += id to body
                if (rejectSubmit.get()) {
                    respond(
                        exchange, 400, "application/json; charset=utf-8",
                        """{"violations":[{"field":"","message":"$REJECT_MESSAGE"}]}""",
                    )
                } else {
                    submitted += id
                    respondJson(exchange, """{"data":{"id":"$id","status":"ENABLED"}}""")
                }
            }
            path.startsWith("/quality-client/") -> respond(exchange, 200, "text/html; charset=utf-8", "<html><body>quality-client</body></html>")
            else -> respond(exchange, 404, "text/plain; charset=utf-8", "夹具没有这条路径：$path")
        }
    }

    // ── 夹具 JSON ──────────────────────────────────────────────────

    /** 一条学生评教任务 + 一条别的业务（`QmsJudgeApi` 按 business_code 过滤掉它）。 */
    private fun tasksJson() = """
{"data":[
  {"id":"$TASK_ID","plan_instance":{"plan":{"business_code":"student-evaluation","primary_name":{"text":{"zh_cn":"$PLAN_TITLE"}}}}},
  {"id":"$OTHER_TASK_ID","plan":{"business_code":"teacher-evaluation","primary_name":"别的业务"}}
]}
""".trimIndent()

    private fun status(id: String, initial: String) = if (id in submitted) "ENABLED" else initial

    private fun groupedCoursesJson(wanted: String): String {
        val rows = listOf(
            Triple(RESPONSE_DRAFT_A, COURSE_A, """["$LECTURER_A1","$LECTURER_A2"]""") to "DRAFT",
            Triple(RESPONSE_DRAFT_B, COURSE_B, "[]") to "DRAFT",
            Triple(RESPONSE_DONE, COURSE_DONE, """["$LECTURER_DONE"]""") to "ENABLED",
        ).filter { (row, initial) -> status(row.first, initial) == wanted }
        val courses = rows.joinToString(",") { (row, initial) ->
            val (id, course, lecturers) = row
            """{"course_name":"$course","lecturers":$lecturers,"surveys":[{"response":{"id":"$id","status":"${status(id, initial)}"}}]}"""
        }
        return """{"data":[$courses]}"""
    }

    /** 答卷详情：打分 / 单选 / 矩阵 / 简答 / 标题各一题。 */
    private fun detailJson(id: String) = """
{"data":{
  "response":{"id":"$id","status":"DRAFT"},
  "labelSurvey":[{"survey":{"id":"$SURVEY_ID","questions":[
    {"id":"Q-title","type":"TEXT_TITLE"},
    {"id":"Q-score","type":"SCORING","options":{"min_range":0,"max_range":100,"decimal_places":0}},
    {"id":"Q-single","type":"SINGLE_SELECTION","choices":[
      {"id":"C-best","options":{"score":5}},{"id":"C-worse","options":{"score":1}}]},
    {"id":"Q-matrix","type":"MATRIX","sub_questions":[{"id":"Q-m1"},{"id":"Q-m2"}],"choices":[
      {"id":"C-m-best","options":{"score":5}},{"id":"C-m-worse","options":{"score":1}}]},
    {"id":"Q-text","type":"SHORT_ANSWER","options":{"input_min_length":4}}
  ]}}]
}}
""".trimIndent()

    // ── 小工具 ───────────────────────────────────────────────────

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String) =
        respond(exchange, 200, "application/json; charset=utf-8", json)

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }
}