package com.xjtu.toolbox.dzpz

import com.sun.net.httpserver.HttpExchange
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的**电子凭证系统**（`dzpz.xjtu.edu.cn` —— 成绩单只是这个工作流引擎的一个 `workflowId`）。
 *
 * ## 它扮的是哪条链
 *
 * 与 [`DzpzLogin`] + [`DzpzSession`] 一起，把**搬进 `:data` 之后**的那七步原样演一遍：
 *
 * ```
 * GET  /login/Login.jsp                     → 302 CAS OAuth2 authorize（client_id=new9940）
 * GET  https://login.xjtu.edu.cn/cas/oauth2.0/authorize
 *        （没 TGC → 302 /cas/login?service=<authorize>，登录页在 LibraryFakeUpstream 那半台 CAS 里）
 * GET  /login/Login.jsp?code=OC-fake-1      → 种 loginidweaver cookie（= userId）→ 200
 * GET  /api/system/info/getOSinfo           → {"resourceid":"72439"}（DzpzSession.validateLogin 的探针）
 *
 * POST /api/workflow/reqform/loadForm        （iscreate=1）→ 表单默认值 + 成绩单类型选项 + tableInfo
 * POST /api/workflow/linkage/reqDataInputResult ×2       → assignInfo_64 / assignInfo_43
 * POST /api/xjtuapi/procfiles                → "9001"（预览文档 id，纯文本）
 * POST /api/workflow/reqform/requestOperation（第一次，src=save）   → data.type=SUCCESS + requestid/sessionkey
 * POST /api/workflow/reqform/loadForm        （requestid=555）      → 重载后的 params/submitParams/maindata
 * POST /api/xjtuapi/checksubmit              → "1"
 * POST /api/workflow/reqform/requestOperation（第二次，src=submit） → 转发到下载节点成功
 * POST /api/workflow/reqform/loadForm        （isRefresh=1）       → field7564 里的 filedatas（下载链接）
 * GET  /weaver/weaver.file.FileDownload?fileid=9001…              → PDF 字节
 * ```
 *
 * ## 为什么在 `:testkit` 而不是 `:data:jvmTest`
 *
 * 与其余夹具同一条理由：`:data:jvmTest`（`TranscriptApiJvmTest`）与 `:desktop` 的
 * `DesktopAuthLibraryJvmTest`（桌面自己登 `dzpz` 那条路）要看到**同一批响应**，而 Gradle 跨模块
 * 共享不了 test 源集。CAS 那半台不在这个文件里 —— `login.xjtu.edu.cn` 按 host 分派，它属于
 * `LibraryFakeUpstream`（这一份只用它的 OAuth2 授权入口）。
 *
 * ## 夹具值全是编造的
 *
 * 学号 `2021000001`、OA id `72439`、文档 id `9001`、模板 `/CPT/transcript-fake.cpt` 都是编造值
 * （与 `LibraryFakeUpstream.USERNAME` 一致只是为了「同一个假账号贯穿几条链」）。
 */
class DzpzFakeUpstream {

    companion object {
        /** 与生产代码里 `TranscriptApi.BASE` / `DzpzLogin.BASE_URL` 的 host 一致（URL 不重写，只走代理）。 */
        const val HOST = "dzpz.xjtu.edu.cn"

        /** 登录入口页（`DzpzLogin.DZPZ_LOGIN_ENTRY` 的路径部分）。 */
        const val LOGIN_PATH = "/login/Login.jsp"

        /** 登录态探针（`DzpzLogin.OS_INFO_URL` / `DzpzSession.validateLogin`）。 */
        const val OS_INFO_PATH = "/api/system/info/getOSinfo"

        /** OA id（= `loginidweaver` cookie 的值，也是每个请求体里的 `f_weaver_belongto_userid`）。 */
        const val OA_ID = "72439"

        /** 成绩单流程的 workflowId（`DzpzDocuments.TRANSCRIPT`）。 */
        const val WORKFLOW_ID = 29

        // ── 夹具样本：表单 ──
        const val TYPE_NAME = "本科生中文成绩单（夹具）"
        const val TYPE_VALUE = 0
        const val CANCELLED_TYPE_NAME = "已停用的成绩单（夹具）"
        const val DEFAULT_DATE = "2026-10-11"
        const val DEFAULT_REQUEST_NAME = "成绩单申请（夹具）"
        const val LINKAGE_UUID = "LG-FAKE-1"

        // ── 夹具样本：联动 ──
        const val STUDENT_ID = "2021000001"
        const val ENROLL_YEAR = "2021"
        const val TEMPLATE_PATH = "/CPT/transcript-fake.cpt"
        const val CATEGORY_NAME = "电子证明（夹具）"

        // ── 夹具样本：提交 ──
        const val DOC_ID = "9001"
        const val REQUEST_ID = 555
        const val SESSION_KEY_FIRST = "SK-FAKE-1"
        const val SESSION_KEY_SECOND = "SK-FAKE-2"
        const val SUBMIT_TOKEN_FIRST = 1700000000001L
        const val SUBMIT_TOKEN_RELOADED = 1700000000004L
        const val SUBMIT_TOKEN_ADD_RELOADED = 1700000000005L
        const val SUBMIT_TOKEN_FORWARD = 1700000000003L

        // ── 夹具样本：下载 ──
        const val PDF_FILENAME = "电子成绩单（夹具）.pdf"
        const val PDF_LOADLINK = "/weaver/file/download?fid=9001"
        const val PDF_FILESIZE = "12.3 KB"
        const val PDF_AUTH_STR = "AUTH-FAKE"
        const val PDF_AUTH_SIG = "AUTHSIG-FAKE"
        val PDF_BYTES: ByteArray = "%PDF-1.4\n（夹具）这不是真成绩单\n%%EOF\n".toByteArray()

        /** 下载那一枪必须带的 `Referer`（`TranscriptApi.downloadPdf` 里硬编码的那个）。 */
        const val PDF_REFERER = "https://$HOST/spa/workflow/static4form/index.html"
    }

    // ── 断言用：请求原文与计数（不涉及任何真人数据） ──

    val loadFormBodies: MutableList<String> = mutableListOf()
    val linkageBodies: MutableList<String> = mutableListOf()
    val submitBodies: MutableList<String> = mutableListOf()
    var procfilesBody: String? = null
        private set
    var checksubmitBody: String? = null
        private set
    var downloadReferer: String? = null
        private set
    var downloadUrl: String? = null
        private set
    val osInfoCalls = AtomicInteger(0)
    val loginEntryCalls = AtomicInteger(0)

    /**
     * 打开之后 `isRefresh` 那一枪**不给** `field7564`，只给 `field7244`（预览文档 id）
     * —— 对应 `TranscriptApi.getDownloadInfo` 的 fallback 分支（拼 `FileDownload` 地址）。
     */
    var downloadInfoFallback = false

    // ── 路由 ─────────────────────────────────────────────────────

    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == LOGIN_PATH -> handleLoginEntry(exchange, query)
            path == OS_INFO_PATH -> {
                osInfoCalls.incrementAndGet()
                respondJson(exchange, """{"resourceid":"$OA_ID","code":"0"}""")
            }
            path == "/api/workflow/reqform/loadForm" -> handleLoadForm(exchange)
            path == "/api/workflow/linkage/reqDataInputResult" -> handleLinkage(exchange)
            path == "/api/xjtuapi/procfiles" -> {
                procfilesBody = formBody(exchange)
                respondText(exchange, DOC_ID)
            }
            path == "/api/xjtuapi/checksubmit" -> {
                checksubmitBody = formBody(exchange)
                respondText(exchange, "1")
            }
            path == "/api/workflow/reqform/requestOperation" -> handleRequestOperation(exchange)
            path == PDF_LOADLINK.substringBefore('?') -> {
                downloadReferer = exchange.requestHeaders.getFirst("Referer")
                downloadUrl = exchange.requestURI.toString()
                respond(exchange, 200, "application/pdf", PDF_BYTES)
            }
            path == "/wui/index.html" -> respondHtml(exchange, "<html><body>电子凭证（夹具）</body></html>")
            else -> respond(exchange, 404, "text/plain", "夹具没有这条路径：$path".toByteArray())
        }
    }

    /**
     * 登录入口页：`?code=` 回来（OAuth2 回调）就种 [OA_ID] 那枚 `loginidweaver` 并落到首页；
     * 没有 code 就照真实站点 302 到 CAS 的 OAuth2 授权入口（`redirect_uri` 指回本页）。
     */
    private fun handleLoginEntry(exchange: HttpExchange, query: String) {
        loginEntryCalls.incrementAndGet()
        if ("code=" in query) {
            exchange.responseHeaders.add("Set-Cookie", "loginidweaver=$OA_ID; Path=/")
            exchange.responseHeaders.add("Set-Cookie", "oauth2_access_token=fake-access-token; Path=/")
            redirect(exchange, "https://$HOST/wui/index.html")
            return
        }
        val authorize = "https://login.xjtu.edu.cn/cas/oauth2.0/authorize" +
            "?response_type=code" +
            "&client_id=new9940" +
            "&redirect_uri=${encode("https://$HOST$LOGIN_PATH")}" +
            "&state=1995"
        redirect(exchange, authorize)
    }

    /**
     * `/api/workflow/reqform/loadForm` 三种形态（真站点是同一条路径，靠 body 区分）：
     * `iscreate=1` = 建单表单；`isRefresh=1` = 取下载信息那一枪；其余 = 第一次提交后的重载。
     */
    private fun handleLoadForm(exchange: HttpExchange) {
        val body = formBody(exchange)
        loadFormBodies += body
        when {
            "iscreate=1" in body -> respondJson(exchange, createFormJson)
            "isRefresh=1" in body ->
                respondJson(exchange, if (downloadInfoFallback) downloadInfoFallbackJson else downloadInfoJson)
            else -> respondJson(exchange, reloadFormJson)
        }
    }

    /**
     * 联动那一枪：`linkageid=43,64` 要学号 + 入学年，`linkageid=43` 要模板路径 + 业务分类名。
     * 分清这两种形态正是 `getLinkageData` 里那两段独立请求的依据。
     */
    private fun handleLinkage(exchange: HttpExchange) {
        val body = formBody(exchange)
        linkageBodies += body
        when {
            "field7250=" in body -> respondJson(
                exchange,
                """{"assignInfo_64":{"changeValue":{"field7237":{"value":"$STUDENT_ID"},"field7536":{"value":"$ENROLL_YEAR"}}}}""",
            )
            else -> respondJson(
                exchange,
                """{"assignInfo_43":{"changeValue":{"field7247":{"value":"$TEMPLATE_PATH"},"field7241":{"value":"$CATEGORY_NAME"}}}}""",
            )
        }
    }

    /** 两次 `requestOperation`：第一次建单（src=save），第二次转发到下载节点（src=submit）。 */
    private fun handleRequestOperation(exchange: HttpExchange) {
        val body = formBody(exchange)
        submitBodies += body
        if ("src=submit" in body) {
            respondJson(
                exchange,
                """
                {"data":{"type":"SUCCESS","messageInfo":{"message":"已转发","sessionkey":"$SESSION_KEY_SECOND",
                  "nextNodeNames":"申请人下载"},
                  "resultInfo":{"requestid":$REQUEST_ID,"sessionkey":"$SESSION_KEY_SECOND"},
                  "submitParams":{"${OA_ID}_${REQUEST_ID}_request_submit_token":$SUBMIT_TOKEN_FORWARD}}}
                """.trimIndent(),
            )
        } else {
            respondJson(
                exchange,
                """
                {"data":{"type":"SUCCESS","resultInfo":{"requestid":$REQUEST_ID,"sessionkey":"$SESSION_KEY_FIRST"},
                  "submitParams":{"${OA_ID}_${WORKFLOW_ID}_addrequest_submit_token":$SUBMIT_TOKEN_FIRST}}}
                """.trimIndent(),
            )
        }
    }

    // ── 响应原文（样本值全部编造） ───────────────────────────────

    /** 建单表单：两个类型选项（一个 `cancel=1`，由 `parseTypeOptions` 滤掉）+ 默认日期与请求名。 */
    private val createFormJson: String = """
        {
          "params": {
            "linkageUUID": "$LINKAGE_UUID",
            "signatureAttributesStr": "SIG-ATTR-FAKE",
            "signatureSecretKey": "SIG-KEY-FAKE"
          },
          "submitParams": {"${OA_ID}_${WORKFLOW_ID}_addrequest_submit_token": $SUBMIT_TOKEN_FIRST},
          "maindata": {
            "field7249": {"value": "$DEFAULT_DATE"},
            "field-1": {"value": "$DEFAULT_REQUEST_NAME"}
          },
          "tableInfo": {
            "main": {
              "fieldinfomap": {
                "7243": {
                  "selectattr": {
                    "selectitemlist": [
                      {"selectname": "$TYPE_NAME", "selectvalue": "$TYPE_VALUE"},
                      {"selectname": "$CANCELLED_TYPE_NAME", "selectvalue": "7", "cancel": "1"}
                    ]
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    /** 第一次提交后的重载：新的 auth/签名参数 + 一批表单字段（第二次提交就是从它读值回填的）。 */
    private val reloadFormJson: String = """
        {
          "params": {
            "authStr": "$PDF_AUTH_STR",
            "authSignatureStr": "$PDF_AUTH_SIG",
            "signatureAttributesStr": "SIG-ATTR-2",
            "signatureSecretKey": "SIG-KEY-2",
            "linkageUUID": "LG-FAKE-2",
            "lastOperateDate": "$DEFAULT_DATE",
            "lastOperateTime": "09:00:00",
            "billid": "BILL-FAKE-1"
          },
          "submitParams": {
            "${OA_ID}_${REQUEST_ID}_request_submit_token": $SUBMIT_TOKEN_RELOADED,
            "${OA_ID}_${WORKFLOW_ID}_addrequest_submit_token": $SUBMIT_TOKEN_ADD_RELOADED
          },
          "maindata": {
            "field7249": {"value": "$DEFAULT_DATE"},
            "field7501": {"value": "1"},
            "field7244": {"value": "$DOC_ID"},
            "field7237": {"value": "$STUDENT_ID"},
            "field7241": {"value": "$CATEGORY_NAME"},
            "field7247": {"value": "$TEMPLATE_PATH"},
            "field-1": {"value": "$DEFAULT_REQUEST_NAME"}
          }
        }
    """.trimIndent()

    /** 取下载信息那一枪：`field7564.specialobj.filedatas[0]` 就是最终文件名与下载地址。 */
    private val downloadInfoJson: String = """
        {
          "params": {"authStr": "$PDF_AUTH_STR", "authSignatureStr": "$PDF_AUTH_SIG"},
          "maindata": {
            "field7564": {
              "specialobj": {
                "filedatas": [
                  {"filename": "$PDF_FILENAME", "filesize": "$PDF_FILESIZE", "loadlink": "$PDF_LOADLINK"}
                ]
              }
            }
          }
        }
    """.trimIndent()

    /** fallback 形态：没有 `field7564`，只有 `field7244`（预览文档 id）—— 屏上应退回拼 FileDownload 地址。 */
    private val downloadInfoFallbackJson: String = """
        {
          "params": {"authStr": "$PDF_AUTH_STR", "authSignatureStr": "$PDF_AUTH_SIG"},
          "maindata": {
            "field7244": {"value": "$DOC_ID", "specialobj": {"name": "$PDF_FILENAME"}}
          }
        }
    """.trimIndent()

    // ── 小工具 ───────────────────────────────────────────────────

    private fun formBody(exchange: HttpExchange): String =
        exchange.requestBody.readBytes().decodeToString()

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String) =
        respond(exchange, 200, "application/json; charset=utf-8", json.toByteArray())

    private fun respondText(exchange: HttpExchange, text: String) =
        respond(exchange, 200, "text/plain; charset=utf-8", text.toByteArray())

    private fun respondHtml(exchange: HttpExchange, html: String) =
        respond(exchange, 200, "text/html; charset=utf-8", html.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}
