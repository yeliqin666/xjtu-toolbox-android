package com.xjtu.toolbox.feedback

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/** 假的反馈 Worker（`api.dzhz.top/feedback`）。 */
class FeedbackFakeUpstream {

    companion object {
        /** 业务站点（`FeedbackApi.DEFAULT_BASE_URL` 的 host）。 */
        const val HOST = "api.dzhz.top"

        const val BASE = "https://$HOST/feedback"

        const val VERSION_LABEL = "0.0.0-fake (1)"
        const val DEVICE_LABEL = "示例厂 示例机"
        const val OS_LABEL = "Android 14 / API 34"

        /** 本地账本里那条工单（屏提交后自己记的），夹具按它回一条回复。 */
        const val TICKET = "TEXAMPLE01"

        /** 回复正文。 */
        const val REPLY_TEXT = "示例回复：下个版本修"

        /** 公开问答。 */
        const val QA_QUESTION = "示例提问：为什么没有夜间模式？"
        const val QA_ANSWER = "示例回答：排序在后，还没做。"
        const val QA_CATEGORY = "想要新功能"
        const val QA_PRIVATE_QUESTION = "未公开的提问（不该出现在屏上）"

        const val SUBMIT_REJECTED_MSG = "上游拒收：测试错误"
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String, code: Int = 200) =
        respond(exchange, code, "application/json; charset=utf-8", json.toByteArray())

    val submitCalls = AtomicInteger(0)
    val repliesCalls = AtomicInteger(0)
    val publicQaCalls = AtomicInteger(0)
    val voteCalls = AtomicInteger(0)
    val pollResultsCalls = AtomicInteger(0)

    @Volatile
    var submitRejected: Boolean = false

    @Volatile
    var rejectReplies: Boolean = false

    @Volatile
    var lastSubmitBody: String? = null
        private set

    @Volatile
    var lastRepliesQuery: String? = null
        private set

    @Volatile
    var lastVoteBody: String? = null
        private set

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.rawQuery.orEmpty()
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        when {
            path == "/feedback/submit" && exchange.requestMethod == "POST" -> {
                submitCalls.incrementAndGet()
                lastSubmitBody = body
                if (submitRejected) {
                    respondJson(exchange, """{"error":"$SUBMIT_REJECTED_MSG","code":"rejected"}""", 400)
                    return
                }
                respondJson(exchange, """{"ok":true,"ticket":"$TICKET"}""")
            }
            path == "/feedback/replies" && exchange.requestMethod == "GET" -> {
                repliesCalls.incrementAndGet()
                lastRepliesQuery = URLDecoder.decode(query, "UTF-8")
                if (rejectReplies) {
                    respondJson(exchange, """{"error":"服务暂不可用（503）","code":"upstream_error"}""", 503)
                    return
                }
                val wanted = parseCsvParam(query, "tickets").map { it.uppercase() }.toSet()
                val matched = replyRecords.filter { it.first in wanted }.map { it.second }
                respondJson(exchange, """{"replies":[${matched.joinToString(",")}]}""")
            }
            path == "/feedback/public-qa" && exchange.requestMethod == "GET" -> {
                publicQaCalls.incrementAndGet()
                respondJson(exchange, """{"items":[$qaRecord]}""")
            }
            path == "/feedback/polls/vote" && exchange.requestMethod == "POST" -> {
                voteCalls.incrementAndGet()
                lastVoteBody = body
                respondJson(exchange, """{"ok":true,"pollId":"demo","voters":1,"counts":{"选项A":1}}""")
            }
            path == "/feedback/polls/results" && exchange.requestMethod == "GET" -> {
                pollResultsCalls.incrementAndGet()
                val ids = parseCsvParam(query, "ids")
                val entries = ids.joinToString(",") { id ->
                    """"$id":{"voters":3,"counts":{"面":2,"饭":1},"updatedAt":1700000000000}"""
                }
                respondJson(exchange, """{"polls":{$entries}}""")
            }
            else -> respond(
                exchange,
                404,
                "text/plain; charset=utf-8",
                "夹具没有这条路径：$path".toByteArray(),
            )
        }
    }

    private fun parseCsvParam(rawQuery: String, name: String): List<String> {
        val pair = rawQuery.split('&').firstOrNull { it.startsWith("$name=") } ?: return emptyList()
        val value = URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        return value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val replyRecords = listOf(
        TICKET to """{"ticket":"$TICKET","text":"$REPLY_TEXT","time":"2026-10-01 12:00","by":"maintainer"}""",
        "TOTHER999" to """{"ticket":"TOTHER999","text":"别人的回复","time":"2026-09-30 09:00","by":""}""",
    )

    private val qaRecord =
        """{"question":"$QA_QUESTION","answer":"$QA_ANSWER","category":"$QA_CATEGORY","by":"maintainer"}"""
}
