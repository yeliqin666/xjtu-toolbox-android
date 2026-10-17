package com.xjtu.toolbox.feedback

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicInteger

/** 假的飞书开放平台（`open.feishu.cn`，反馈那一条链的上游）。 */
class FeedbackFakeUpstream {

    companion object {
        /** 业务站点（`FeedbackApi.HOST` 的 host）。 */
        const val HOST = "open.feishu.cn"

        const val BASE = "https://$HOST/open-apis"

        /** 租户令牌（登录形态的凭据：app_id + app_secret 换来的那一张）。 */
        const val TENANT_TOKEN = "t-fake-tenant-token"

        const val APP_ID = "fake-app-id"
        const val APP_SECRET = "fake-app-secret"
        const val BASE_TOKEN = "fakebase0000000001"
        const val SUBMIT_TABLE = "tblfakeSubmit01"
        const val REPLY_TABLE = "tblfakeReply001"
        const val VERSION_LABEL = "0.0.0-fake (1)"
        const val DEVICE_LABEL = "示例厂 示例机"
        const val OS_LABEL = "Android 14 / API 34"

        /** 本地账本里那条工单（屏提交后自己记的），夹具按它回一条回复。 */
        const val TICKET = "TEXAMPLE01"

        /** 回复正文（**富文本片段数组**那一档，见类 KDoc 第 1 条）。 */
        const val REPLY_TEXT = "示例回复：下个版本修"

        /** 公开问答（勾了「公开」的那两条）。 */
        const val QA_QUESTION = "示例提问：为什么没有夜间模式？"
        const val QA_ANSWER = "示例回答：排序在后，还没做。"
        const val QA_CATEGORY = "想要新功能"
        const val QA_PRIVATE_QUESTION = "未公开的提问（不该出现在屏上）"

        /** 飞书把「查找引用」用在 filter 上时的错误码。 */
        const val FILTER_REJECTED_CODE = 1254018

        /** 上游拒收一次提交时给的码与 msg（[submitRejected] 打开后用）。 */
        const val SUBMIT_REJECTED_CODE = 1254302
        const val SUBMIT_REJECTED_MSG = "Permission denied（示例：这张表不给写）"

        /** 飞书拒绝这个 filter 时给的 msg（实现只把它写进日志，不抛）。 */
        const val FILTER_ERROR_MSG = "FieldNameNotFound"
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String) =
        respond(exchange, 200, "application/json; charset=utf-8", json.toByteArray())


    /** 打开后：带 filter 的 search 一律回 1254018。 */
    @Volatile
    var rejectFilter: Boolean = false

    /** 换租户令牌被要了几次（登录取令牌缓存那条判据靠它）。 */
    val tokenCalls = AtomicInteger(0)

    /** 提交记录被 POST 了几次。 */
    val submitCalls = AtomicInteger(0)

    /** search 被 POST 了几次（含带 filter 与不带 filter 两种）。 */
    val searchCalls = AtomicInteger(0)

    /** 打开后：提交回 `code != 0`（上游拒收）。 */
    @Volatile
    var submitRejected: Boolean = false

    /** 最近一次提交请求体里的 `fields` 原文。 */
    @Volatile
    var lastSubmitFields: String? = null

        private set

    /** 最近一次 search 请求体原文。 */
    @Volatile
    var lastSearchBody: String? = null

        private set

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        when {
            path == "/open-apis/auth/v3/tenant_access_token/internal" -> {
                tokenCalls.incrementAndGet()
                lastTokenBody = body
                respondJson(exchange, """{"code":0,"msg":"ok","tenant_access_token":"$TENANT_TOKEN","expire":7200}""")
            }
            path.endsWith("/records") && exchange.requestMethod == "POST" -> {
                submitCalls.incrementAndGet()
                lastSubmitFields = body
                if (submitRejected) {
                    respondJson(exchange, """{"code":$SUBMIT_REJECTED_CODE,"msg":"$SUBMIT_REJECTED_MSG"}""")
                    return
                }
                respondJson(exchange, """{"code":0,"msg":"ok","data":{"record":{"record_id":"recfake01"}}}""")
            }
            path.endsWith("/records/search") -> {
                searchCalls.incrementAndGet()
                lastSearchBody = body
                val filtered = "\"filter\"" in body
                if (filtered && rejectFilter) {
                    respondJson(
                        exchange,
                        """{"code":$FILTER_REJECTED_CODE,"msg":"$FILTER_ERROR_MSG"}""",
                    )
                    return
                }
                when {
                    // 公开问答那一档：`{"公开": "is" ["true"]}`
                    "\"公开\"" in body -> respondJson(exchange, publicQaResponse())
                    else -> respondJson(exchange, repliesResponse())
                }
            }
            else -> respond(
                exchange,
                404,
                "text/plain; charset=utf-8",
                "夹具没有这条路径：$path".toByteArray(),
            )
        }
    }

    /** 最近一次取令牌的请求体。 */
    @Volatile
    var lastTokenBody: String? = null

        private set

    /** 回复表的记录：工单号与回复都是富文本数组，另有一条不在 wanted 里的记录。 */
    private fun repliesResponse(): String = """
        {"code":0,"msg":"ok","data":{"has_more":false,"items":[
          {"record_id":"recA","fields":{
            "工单号":[{"type":"text","text":"$TICKET"}],
            "回复":[{"type":"text","text":"$REPLY_TEXT"}],
            "回复时间":"2026-10-01 12:00"
          }},
          {"record_id":"recB","fields":{
            "工单号":"TOTHER999",
            "回复":"别人的回复",
            "回复时间":"2026-09-30 09:00"
          }}
        ]}}
    """.trimIndent()

    /** 回复表里的公开那几条（一条勾了「公开」，一条没勾）。 */
    private fun publicQaResponse(): String = """
        {"code":0,"msg":"ok","data":{"has_more":false,"items":[
          {"record_id":"recQ1","fields":{
            "公开":true,
            "问题":[{"type":"text","text":"$QA_QUESTION"}],
            "回复":[{"type":"text","text":"$QA_ANSWER"}],
            "类型":"$QA_CATEGORY"
          }},
          {"record_id":"recQ2","fields":{
            "公开":false,
            "问题":"$QA_PRIVATE_QUESTION",
            "回复":"不该被读到的回答"
          }}
        ]}}
    """.trimIndent()
}
