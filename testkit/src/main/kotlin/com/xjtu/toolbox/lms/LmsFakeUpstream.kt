package com.xjtu.toolbox.lms

import com.sun.net.httpserver.HttpExchange
import com.xjtu.toolbox.library.LibraryFakeUpstream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的**思源学堂**上游（`lms.xjtu.edu.cn`，第 18 条真数据路由的学校那一侧）。
 *
 * ## 它扮的是哪条链
 *
 * 与 [`LmsLogin`](com.xjtu.toolbox.lms.LmsLogin) + [`LmsSession`](com.xjtu.toolbox.auth.LmsSession)
 * 一起，把**搬进 `:data` 之后**的登录与取数原样演一遍：
 *
 * ```
 * GET  https://lms.xjtu.edu.cn/  →（无会话、无 ticket）302 http://login.xjtu.edu.cn/cas/login?service=https://lms.xjtu.edu.cn/
 * POST http://login.xjtu.edu.cn/cas/login?service=… → 种 TGC → 302 回 https://lms.xjtu.edu.cn/?ticket=ST-n（登录页那半台在 LibraryFakeUpstream）
 * GET  https://lms.xjtu.edu.cn/?ticket=ST-n → 200 + Set-Cookie LMS_SESSION（会话落下来）
 * GET  https://lms.xjtu.edu.cn/user/index → 200（HTML 里内嵌 globalData.user——`getUserInfo` 从它解析学号）
 * POST https://lms.xjtu.edu.cn/api/my-courses   → {"courses":[…]}
 * GET  https://lms.xjtu.edu.cn/api/courses/7/activities → {"activities":[…]}
 * GET  https://lms.xjtu.edu.cn/api/activities/101 → 详情（**没有 deadline 键**——钉 `mergeBrief` 从列表并进来）
 * GET  https://lms.xjtu.edu.cn/api/activities/101/students/1/submission_list → 提交列表
 * GET  https://lms.xjtu.edu.cn/api/uploads/501/blob → 附件字节
 * GET  https://lms.xjtu.edu.cn/api/uploads/666/blob → 403 + 「没有权限」——活动结束后学堂关闭下载那支
 * ```
 *
 * ## 刻意不演的那一档
 *
 * 课堂（lesson）/ 直播（lecture_live）的**回放**在 `rms-v5.xjtu.edu.cn` 上
 *（embed-token 交换 + captures 拉取，又是一台 https 服务），夹具的样本只给
 * homework / material / page 三类 —— 回放那两支的解析仍在 `:data:jvmTest` 里由
 * `LmsApiJvmTest` 之外的实测口径守着。不给假回放 = 那张图里不出现「回放」区块，
 * 这是如实，不是缺块。
 *
 * ## 统一认证那一半为什么不在这个文件里
 *
 * `login.xjtu.edu.cn` 是所有站点共用的一台 CAS，`FakeCampusProxy` 把它分派给
 * [LibraryFakeUpstream]；本夹具只负责 `lms.xjtu.edu.cn` 那一半 —— CAS 回跳之后的事。
 *
 * ## 业务端点不验会话
 *
 * 夹具要钉的是**解析与请求形状**；会话失效由 `SiteSession.executeWithReAuth` 那条路负责
 *（已由图书馆/教务那几条钉子守着），这里直接给数据。
 *
 * ## 夹具值全是编造的
 *
 * 课程名「数据结构（示例课）」、教师「李老师」、作业「第十三次作业」，皆非真人真课；
 * 学号与 `LibraryFakeUpstream.USERNAME` 无关（思源站内没有学号），但保持一致的人生观：
 * 同一套假身份贯穿几条链。
 */
class LmsFakeUpstream {

    companion object {
        /** 业务站点（`LmsLogin.BASE_URL` 的 host）。 */
        const val HOST = "lms.xjtu.edu.cn"

        const val BASE = "https://$HOST"

        /** 会话 cookie 值（登录成功的落点）。 */
        const val SESSION_COOKIE = "LMS_SESSION_fake"

        /** 登录取数要用的学号（`getUserInfo().id`，提交列表按它拼 URL）。 */
        const val USER_ID = "1"

        // ── 夹具样本 ──

        const val COURSE_ID = 7
        const val COURSE_NAME = "数据结构（示例课）"
        const val COURSE_CODE = "COMP1001"
        const val INSTRUCTOR_NAME = "李老师"

        const val HOMEWORK_ID = 101
        const val HOMEWORK_TITLE = "第十三次作业"
        /** 列表里才有、详情里没有的截止时间（钉 `mergeBrief`）。 */
        const val HOMEWORK_DEADLINE = "2026-06-30T11:25:00Z"

        const val MATERIAL_ID = 201
        const val MATERIAL_TITLE = "第一章课件"

        const val PAGE_ID = 301
        const val PAGE_TITLE = "课程简介"

        const val UPLOAD_ID = 501
        const val UPLOAD_NAME = "作业附件.pdf"
        const val UPLOAD_TYPE = "application/pdf"

        const val SUBMISSION_ID = 701

        /** 已结束作业的下载（/blob）→ 403 + 「没有权限」：LmsSession 把它认成业务拒绝，不重登。 */
        const val CLOSED_UPLOAD_ID = 666

        /** `/user/index`：`globalData.user` 与 `dept` 的 JS 形状（`getUserInfo` 就解析这两块）。 */
        val USER_INDEX_HTML = """
<html><body><script>
window.__TI_GLOBAL_DATA__ = {
  user: { id: 1, name: "张三", userNo: "2021000001", orgId: 2, mobile: "13800000000",
          orgName: "计算机学院", orgCode: "CS", role: "student", hasAiAbility: false },
  dept: { id: 3, name: "软件学院", code: "RJ" },
  locale: "zh_CN"
};
</script></body></html>
""".trimIndent()

        /** `POST /api/my-courses`。 */
        val MY_COURSES_JSON = """
{"courses":[
  {"id":$COURSE_ID,"name":"$COURSE_NAME","course_code":"$COURSE_CODE","course_type":1,"credit":"3.0",
   "compulsory":true,"start_date":"2026-09-01","end_date":"2027-01-15",
   "academic_year":{"id":1,"code":"2026-2027","name":"2026-2027","sort":1},
   "semester":{"id":2,"code":"1","name":"2026-2027-1","real_name":"2026-2027-1","sort":1},
   "department":{"id":3,"name":"计算机学院"},
   "instructors":[{"id":4,"name":"$INSTRUCTOR_NAME"}],
   "course_attributes":{"published":true,"student_count":60,"teaching_class_name":"计算机01"}}
]}
""".trimIndent()

        /** `GET /api/courses/7/activities`：作业（带 deadline 的列表级字段）+ 课件 + 页面。 */
        val ACTIVITIES_JSON = """
{"activities":[
  {"id":$HOMEWORK_ID,"course_id":$COURSE_ID,"type":"homework","title":"$HOMEWORK_TITLE",
   "module_id":1,"start_time":"2026-09-16T00:38:21Z","visible_start_at":null,"end_time":"2026-06-10T15:59:00Z",
   "deadline":"$HOMEWORK_DEADLINE","submit_by_group":false,"published":true,"is_closed":true,
   "created_at":"2026-09-16T00:38:21Z","updated_at":"2026-09-16T00:38:21Z"},
  {"id":$MATERIAL_ID,"course_id":$COURSE_ID,"type":"material","title":"$MATERIAL_TITLE",
   "module_id":1,"start_time":null,"visible_start_at":null,"end_time":"2027-01-15T15:59:00Z",
   "deadline":null,"submit_by_group":false,"published":true,"is_closed":false,
   "created_at":"2026-09-02T02:00:00Z","updated_at":"2026-09-02T02:00:00Z"},
  {"id":$PAGE_ID,"course_id":$COURSE_ID,"type":"page","title":"$PAGE_TITLE",
   "module_id":2,"start_time":null,"visible_start_at":null,"end_time":null,
   "deadline":null,"submit_by_group":false,"published":true,"is_closed":false,
   "created_at":"2026-09-01T01:00:00Z","updated_at":"2026-09-01T01:00:00Z"}
]}
""".trimIndent()

        /** `GET /api/activities/101`：**故意没有 `deadline` 键**（详情 79 个键里本来就没有）。 */
        val HOMEWORK_DETAIL_JSON = """
{"id":$HOMEWORK_ID,"course_id":$COURSE_ID,"type":"homework","title":"$HOMEWORK_TITLE",
 "data":{"description":"<p>完成习题并提交报告</p>","submit_times":3},
 "submit_by_group":false,"group_id":0,"user_submit_count":1,
 "average_score":90.5,"highest_score":98.0,"lowest_score":82.0,"has_score_count":60,
 "module_id":1,"start_time":"2026-09-16T00:38:21Z","visible_start_at":null,"end_time":"2026-06-10T15:59:00Z",
 "published":true,"is_closed":true,
 "uploads":[{"id":$UPLOAD_ID,"name":"$UPLOAD_NAME","type":"$UPLOAD_TYPE","size":2048,"reference_id":0,
             "created_at":"2026-09-16T00:38:21Z","updated_at":"2026-09-16T00:38:21Z","status":""}],
 "created_at":"2026-09-16T00:38:21Z","updated_at":"2026-09-16T00:38:21Z"}
""".trimIndent()

        /** `GET /api/activities/101/students/1/submission_list`。 */
        val SUBMISSION_LIST_JSON = """
{"list":[
  {"id":$SUBMISSION_ID,"activity_id":$HOMEWORK_ID,"student_id":1,"group_id":0,"can_retract":false,
   "is_draft":false,"comment":"","created_at":"2026-09-20T02:00:00Z",
   "created_by":{"id":1,"name":"张三","user_no":"2021000001"},"instructor_comment":"",
   "is_latest_version":true,"is_resubmitted":false,"is_redo":false,"mode":"file",
   "status":"graded","score":"95","final_score":"95","score_at":"2026-09-21T02:00:00Z",
   "submitted_at":"2026-09-20T02:00:00Z","submit_by_instructor":false,
   "submission_correct":{"id":801,"comment":"","instructor_score":"95","score":"95",
                          "updated_at":"2026-09-21T02:00:00Z","uploads":[]},
   "updated_at":"2026-09-21T02:00:00Z","content":"我完成了","uploads":[]}
],"uploads":[]}
""".trimIndent()

        /** `GET /api/activities/201`：课件详情，正文在 `data.description`。 */
        val MATERIAL_DETAIL_JSON = """
{"id":$MATERIAL_ID,"course_id":$COURSE_ID,"type":"material","title":"$MATERIAL_TITLE",
 "data":{"description":"<p>第一章：绪论</p>"},"module_id":1,
 "start_time":null,"visible_start_at":null,"end_time":"2027-01-15T15:59:00Z",
 "published":true,"is_closed":false,
 "uploads":[{"id":502,"name":"第一章课件.pdf","type":"application/pdf","size":102400,"reference_id":0,
             "created_at":"2026-09-02T02:00:00Z","updated_at":"2026-09-02T02:00:00Z","status":""}],
 "created_at":"2026-09-02T02:00:00Z","updated_at":"2026-09-02T02:00:00Z"}
""".trimIndent()

        /** `GET /api/activities/301`：页面型，正文在 `data.content`（description 是空的）。 */
        val PAGE_DETAIL_JSON = """
{"id":$PAGE_ID,"course_id":$COURSE_ID,"type":"page","title":"$PAGE_TITLE",
 "data":{"content":"<p>课程考核构成：期中 40%，期末 60%</p>","description":""},"module_id":2,
 "start_time":null,"visible_start_at":null,"end_time":null,
 "published":true,"is_closed":false,
 "uploads":[],"created_at":"2026-09-01T01:00:00Z","updated_at":"2026-09-01T01:00:00Z"}
""".trimIndent()

        /** `/api/uploads/501/blob` 的字节（假的 PDF 头，够下载路径走通就行）。 */
        val UPLOAD_BYTES: ByteArray = "%PDF-1.4 fake<br>".toByteArray()

        /** 活动已结束时的 /blob 响应体（`LmsSession.isAuthFailureResponse` 按它认「业务拒绝」）。 */
        const val CLOSED_UPLOAD_BODY = "没有权限"
    }

    // ── 断言用：请求计数与最近参数 ──

    /** 登录链成功落点的 200 次数（每次 `getUserInfo` 也会走 /user/index）。 */
    val indexCalls = AtomicInteger(0)

    /** `POST /api/my-courses` 被要几次。 */
    val courseListCalls = AtomicInteger(0)

    /** `GET /api/courses/{id}/activities` 被要几次。 */
    val activityListCalls = AtomicInteger(0)

    /** 最近一次活动列表请求带的课程 id。 */
    @Volatile
    var lastActivityListCourseId: Int? = null
        private set

    /** `GET /api/activities/{id}` 被要几次。 */
    val detailCalls = AtomicInteger(0)

    /** 最近一次详情请求的活动 id。 */
    @Volatile
    var lastDetailActivityId: Int? = null
        private set

    /** 提交列表被要几次。 */
    val submissionCalls = AtomicInteger(0)

    /** 附件下载（/blob）被要几次。 */
    val blobCalls = AtomicInteger(0)

    /** `lms.xjtu.edu.cn` 的网页与接口。 */
    fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        val hasSession = SESSION_COOKIE in cookieHeader(exchange)
        when {
            // 登录链：
            //  无会话、无 ticket → 302 到统一认证（service = 本域入口，CAS 签完票会带着 ticket= 回来）；
            //  带 ticket 回来 → 种会话 cookie，给 /user/index 那份 200（`LmsLogin.postLogin` 的落点判据）。
            !hasSession && "ticket=" !in query -> {
                redirect(
                    exchange,
                    "http://${LibraryFakeUpstream.CAS_HOST}/cas/login?service=" +
                        encode("$BASE${if (path == "/") "/" else path}"),
                )
            }
            !hasSession && "ticket=" in query -> {
                exchange.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=yes; Path=/")
                respondHtml(exchange, USER_INDEX_HTML)
            }
            path == "/user/index" -> {
                indexCalls.incrementAndGet()
                respondHtml(exchange, USER_INDEX_HTML)
            }
            path == "/api/my-courses" && exchange.requestMethod == "POST" -> {
                courseListCalls.incrementAndGet()
                respondJson(exchange, MY_COURSES_JSON)
            }
            path.startsWith("/api/courses/") && path.endsWith("/activities") -> {
                lastActivityListCourseId = path.substringAfter("/api/courses/").substringBefore("/activities").toIntOrNull()
                activityListCalls.incrementAndGet()
                respondJson(exchange, ACTIVITIES_JSON)
            }
            path.startsWith("/api/activities/") && "/students/" in path && path.endsWith("/submission_list") -> {
                submissionCalls.incrementAndGet()
                respondJson(exchange, SUBMISSION_LIST_JSON)
            }
            path.startsWith("/api/activities/") -> {
                lastDetailActivityId = path.substringAfterLast('/').toIntOrNull()
                detailCalls.incrementAndGet()
                respondJson(
                    exchange,
                    when (path.substringAfterLast('/')) {
                        HOMEWORK_ID.toString() -> HOMEWORK_DETAIL_JSON
                        MATERIAL_ID.toString() -> MATERIAL_DETAIL_JSON
                        PAGE_ID.toString() -> PAGE_DETAIL_JSON
                        else -> "{\"id\":0,\"type\":\"page\",\"title\":\"未知活动\"}"
                    },
                )
            }
            path.startsWith("/api/uploads/") && path.endsWith("/blob") -> {
                blobCalls.incrementAndGet()
                val id = path.substringAfter("/api/uploads/").substringBefore("/blob")
                if (id == CLOSED_UPLOAD_ID.toString()) {
                    respond(exchange, 403, "text/plain; charset=utf-8", CLOSED_UPLOAD_BODY.toByteArray())
                } else {
                    respond(exchange, 200, "application/octet-stream", UPLOAD_BYTES)
                }
            }
            else -> respond(exchange, 404, "text/plain", "夹具没有这条路径：$path".toByteArray())
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private fun cookieHeader(exchange: HttpExchange): String =
        exchange.requestHeaders.getFirst("Cookie").orEmpty()

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, json: String) =
        respond(exchange, 200, "application/json; charset=utf-8", json.toByteArray())

    private fun respondHtml(exchange: HttpExchange, html: String) =
        respond(exchange, 200, "text/html; charset=utf-8", html.toByteArray())

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }
}