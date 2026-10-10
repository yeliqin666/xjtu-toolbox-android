package com.xjtu.toolbox.emptyroom

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假的「空闲教室」上游 —— 扮演**这一屏三档数据源里的两档**（第三档「直查教务」在
 * [com.xjtu.toolbox.jwxt.JwxtFakeUpstream] 里，因为那本来就是教务那个域名的事）：
 *
 * ```
 * ① 智慧教室平台 js.xjtu.edu.cn（https，「实时状态」那一档）
 *    GET  https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjs.xjtu.edu.cn%2F
 *                                        → CAS 那半台由 LibraryFakeUpstream 扮演
 *    GET  https://js.xjtu.edu.cn/?ticket=ST-n    → 200（落地页只是个壳，不验票）
 *    POST https://js.xjtu.edu.cn/server/cas/loginCas
 *                                        → 票据换令牌（校验 ticket 与 serviceUrl，回 TOKEN-AUTH）
 *    GET  https://js.xjtu.edu.cn/server/classroomStatus/classroomStatusList?campusName=&dayTime=
 *                                        → 实时状态快照（校验 TOKEN-AUTH / X-System 两个头）
 *
 * ② 课表 CDN gh-release.xjtutoolbox.com（https，「CDN 课表」那一档）
 *    GET  https://gh-release.xjtutoolbox.com/?file=static/empty_room/{日期}.json
 *                                        → 当天各校区 → 楼 → 教室 的 11 节状态
 * ```
 *
 * ## 为什么一个夹具扮两个域名
 *
 * 这套夹具的规矩是「**一个 host 只能由一个夹具答**」（`FakeCampusProxy.dispatch` 按 host 分派，
 * 同一个域名让两个夹具抢才会出歧义）。这两个域名属于**同一条路由的两档取数**，各自都很小、
 * 又共用同一批教室样本（屏上「实时状态 + 当天课表」要按教室名对得上），所以放在一个文件里、
 * 两个入口（[handleJs] / [handleCdn]）—— **一个 host 仍然只有一个夹具在答**。
 *
 * ## 教室样本与「教室名对得上」这条
 *
 * 两档给的是同一批楼与教室（[BUILDING_A] / [BUILDING_E] 下那几间，座位数也一致）：
 * 实时状态只有「此刻」的信息（在不在上课、有几个人），CDN 那份给 11 节的占用表。
 * 屏把两者按**教室全名**对上，所以夹具也必须同名 —— 名字漂了，钉住的就不是真协议了。
 *
 * ## 日期
 *
 * 真站的 CDN 是**一天一个文件**。夹具只区分「有数据」与 [NO_DATA_DATE]（404），
 * 其余日期一律当有数据给同一份 JSON：它的用途是钉住**解析与错误分支**，不是钉住日历
 * （写死某一天会让桌面端那张连实时状态一起出的图只在当天成立）。
 */
class EmptyRoomFakeUpstream {

    companion object {
        // ── ① 智慧教室平台（https）──

        const val JS_HOST = "js.xjtu.edu.cn"
        const val JS_ORIGIN = "https://$JS_HOST"

        /** `JsLogin.SERVICE_URL`：CAS 的 service 与 loginCas 的 serviceUrl 必须一字不差。 */
        const val SERVICE_URL = "$JS_ORIGIN/"

        /** 换令牌（`JsLogin.exchangeTicket`）。 */
        const val CAS_EXCHANGE_PATH = "/server/cas/loginCas"

        /** 实时状态（`LiveRoomApi.fetchCampus`）。 */
        const val LIVE_PATH = "/server/classroomStatus/classroomStatusList"

        /** `JsLogin.TOKEN_HEADER` / `SYSTEM_HEADER` 的值 —— 与生产代码里的常量必须一致。 */
        const val TOKEN_HEADER = "TOKEN-AUTH"
        const val SYSTEM_HEADER = "X-System"
        const val SYSTEM_VALUE = "WEB"

        /** loginCas 回的那枚令牌（能看出是编的即可，业务侧只透传）。 */
        const val TOKEN = "TOKEN-AUTH-fake-1"

        /** 令牌寿命：真的也是 36000 秒（10 小时）。 */
        const val TOKEN_TIMEOUT_SECONDS = 36_000L

        /** 夹具只扮演这一个校区（`LIVE_CAMPUSES` 里的名字就是它）。 */
        const val LIVE_CAMPUS = "兴庆校区"

        // ── ② 课表 CDN（https）──

        const val CDN_HOST = "gh-release.xjtutoolbox.com"
        const val CDN_ORIGIN = "https://$CDN_HOST"

        /** CDN 的取法就是这一个 query：`?file=static/empty_room/{日期}.json`。 */
        const val CDN_FILE_PREFIX = "static/empty_room/"

        /** 断言里用的那一天（夹具对**除了** [NO_DATA_DATE] 以外的日期都回同一份）。 */
        const val DATE = "2026-10-10"

        /** 这一天的文件不在夹具里 ⇒ 404（`EmptyRoomApi` 该抛 `NoDataException`）。 */
        const val NO_DATA_DATE = "2026-10-12"

        // ── 教室样本（两档同名同座位数）──

        const val BUILDING_A = "主楼A"
        const val BUILDING_E = "东1东"

        const val ROOM_A101 = "主楼A-101"
        const val ROOM_A102 = "主楼A-102"
        const val ROOM_A103 = "主楼A-103"
        const val ROOM_E303 = "东1东-303"
        const val ROOM_E305 = "东1东-305"

        const val SEATS_A101 = 60
        const val SEATS_A102 = 48
        const val SEATS_A103 = 40
        const val SEATS_E303 = 30
        const val SEATS_E305 = 32

        /**
         * 只出现在 CDN 那份里的三个坑（键是 `"null"` 与空串、值是 null、以及缺 `status`）——
         * `EmptyRoomApi.getEmptyRoomsMulti` 的过滤与 `mapNotNull` 就靠它们钉住。
         */
        const val ROOM_NULL_KEY = "null"
        const val ROOM_BLANK_KEY = "无容量房"
        const val ROOM_BAD_JSON = "坏数据房"

        // ── 实时状态样本（平台给什么就给什么：状态码是字符串）──

        /** 上课中的那间（`status=3`），课程与教师名是编的（不许出现真人）。 */
        const val LIVE_COURSE = "示例课程A"
        const val LIVE_TEACHER = "示例丙"
        const val LIVE_IN_CLASS_PEOPLE = 41

        /** 「其它使用」的那间（`status=1`：没排课但有人）。 */
        const val LIVE_IN_USE_PEOPLE = 2
    }

    // ── 打进来的请求（断言用）──

    /** 带票回跳落地的次数（CAS 之后落在 js 入口那一跳）。 */
    val ticketLandings = AtomicInteger(0)

    /** loginCas 换令牌的次数。 */
    val tokenCalls = AtomicInteger(0)

    /** 实时状态被拉了几次。 */
    val liveCalls = AtomicInteger(0)

    /** CDN 当天数据被拉了几次。 */
    val cdnCalls = AtomicInteger(0)

    /** CDN 上请求了哪一天（`?file=` 里那一段）。 */
    @Volatile
    var lastCdnDate: String? = null
        private set

    /** 最近一次 loginCas 的表单原文 / 实时状态请求的 query，错了也好一眼看出来。 */
    @Volatile
    var lastExchangeBody: String? = null
        private set

    @Volatile
    var lastLiveQuery: String? = null
        private set

    /** CAS 回跳的落地页：真站点的 Vue 壳里没有业务信息，登录靠的是地址里的 ticket。 */
    val landingPage = "<html><body><div id=\"app\"></div></body></html>"

    /** `JsLogin.exchangeTicket` 要的形状：`data.tokenValue` + `data.tokenTimeout`。 */
    val tokenJson = """
        {"code":200,"data":{"tokenName":"$TOKEN_HEADER","tokenValue":"$TOKEN",
         "tokenTimeout":$TOKEN_TIMEOUT_SECONDS}}
    """.trimIndent()

    /**
     * `classroomStatusList` 的响应，结构照 2026-09-22 抓包（内容是编的）。
     * 四间教室把四种状态摆齐：空闲两间、上课中一间（带课程与人数）、「其它使用」一间（有人、没课）。
     */
    val liveJson = """
        {"code":0,"data":{
          "campusStatusData":{"使用中":1,"上课中":1,"空闲":2},
          "buildingData":["$BUILDING_A","$BUILDING_E"],
          "classroomStatusCount":{},
          "classroomStatusData":{
            "$BUILDING_A":[
              {"buildName":"$BUILDING_A","classroomName":"$ROOM_A101","status":"3","studentNum":$LIVE_IN_CLASS_PEOPLE,"course":"$LIVE_COURSE","teacherName":"$LIVE_TEACHER","seatNum":"$SEATS_A101"},
              {"buildName":"$BUILDING_A","classroomName":"$ROOM_A102","status":"2","studentNum":0,"course":null,"teacherName":null,"seatNum":"$SEATS_A102"}
            ],
            "$BUILDING_E":[
              {"buildName":"$BUILDING_E","classroomName":"$ROOM_E303","status":"2","studentNum":0,"course":null,"teacherName":null,"seatNum":"$SEATS_E303"},
              {"buildName":"$BUILDING_E","classroomName":"$ROOM_E305","status":"1","studentNum":$LIVE_IN_USE_PEOPLE,"course":null,"teacherName":null,"seatNum":"$SEATS_E305"}
            ]
          }
        }}
    """.trimIndent()

    /**
     * CDN 那份当天数据：校区 → 楼 → 教室 → `{status(11 节), size}`。
     *
     * 四个「坑」刻意摆在 [BUILDING_A] 里：键为 `"null"` 与空串的两间、值为 null 的一间、
     * 缺 `status` 的一间 —— 前两间与 null 那间该被滤掉，缺 status 的那间该被 `mapNotNull` 丢掉。
     */
    val dayJson = """
        {"$LIVE_CAMPUS":{
          "$BUILDING_A":{
            "$ROOM_A101":{"status":[1,1,1,1,1,0,0,0,0,0,0],"size":$SEATS_A101},
            "$ROOM_A102":{"status":[0,0,0,0,0,0,0,0,0,0,0],"size":$SEATS_A102},
            "$ROOM_A103":{"status":[0,0,1,1,1,1,1,1,1,1,1],"size":$SEATS_A103},
            "203":{"status":[0,0,0,0,0,1,1,1,1,1,1],"size":24},
            "$ROOM_NULL_KEY":{"status":[0,0,0,0,0,0,0,0,0,0,0],"size":10},
            "":{"status":[0,0,0,0,0,0,0,0,0,0,0],"size":10},
            "$ROOM_BLANK_KEY":{"status":[0,0,0,0,0,0,0,0,0,0,0]},
            "$ROOM_BAD_JSON":{"size":12},
            "空值房":null
          },
          "$BUILDING_E":{
            "$ROOM_E303":{"status":[0,1,1,0,0,0,0,0,0,0,0],"size":$SEATS_E303},
            "$ROOM_E305":{"status":[0,0,0,0,0,0,0,0,0,0,0],"size":$SEATS_E305}
          }
        },"创新港校区":{"1号巨构":{"1-2050":{"status":[0,0,0,0,0,0,0,0,0,0,0],"size":50}}}}
    """.trimIndent()

    // ── ① 智慧教室平台 ─────────────────────────────────────────────

    /**
     * `js.xjtu.edu.cn` 那一半。三条路径都是 `JsLogin` / `LiveRoomApi` 真打的，认不出的一律 404
     * （body 里带着完整 URL）；形体不对（换令牌少 `ticket`/`serviceUrl`、实时状态没带令牌头）→ 400。
     */
    fun handleJs(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        when {
            path == "/" -> {
                // CAS 回跳落在入口页：真站点只把票放进地址，落地页不验票
                if ("ticket=" !in query) {
                    badRequest(exchange, "js 入口没带 ticket，收到：$query")
                    return
                }
                ticketLandings.incrementAndGet()
                respondHtml(exchange, landingPage)
            }
            path == CAS_EXCHANGE_PATH && exchange.requestMethod == "POST" -> handleExchange(exchange)
            path == LIVE_PATH && exchange.requestMethod == "GET" -> handleLive(exchange, query)
            else -> notFound(exchange, JS_ORIGIN)
        }
    }

    /** 票据换令牌：两个字段都要对上（`serviceUrl` 少一个结尾斜杠真站点也会拒）。 */
    private fun handleExchange(exchange: HttpExchange) {
        val body = runCatching { exchange.requestBody.readBytes().decodeToString() }.getOrDefault("")
        lastExchangeBody = body
        if (!body.contains("\"serviceUrl\":\"$SERVICE_URL\"")) {
            badRequest(exchange, "loginCas 的 serviceUrl 不是 $SERVICE_URL，收到：$body")
            return
        }
        if (!body.contains("\"ticket\":\"ST-")) {
            badRequest(exchange, "loginCas 少了 CAS 签的 ticket，收到：$body")
            return
        }
        tokenCalls.incrementAndGet()
        respondJson(exchange, tokenJson)
    }

    /** 实时状态：认令牌头（`TOKEN-AUTH`）与 `X-System`，少一个就 401 —— 与真站点同一条判据。 */
    private fun handleLive(exchange: HttpExchange, query: String) {
        lastLiveQuery = query
        val campus = param(query, "campusName")
        if (campus != LIVE_CAMPUS) {
            badRequest(exchange, "夹具只扮演 $LIVE_CAMPUS，收到：$query")
            return
        }
        if (exchange.requestHeaders.getFirst(TOKEN_HEADER) != TOKEN ||
            exchange.requestHeaders.getFirst(SYSTEM_HEADER) != SYSTEM_VALUE
        ) {
            respond(
                exchange, 401, "application/json; charset=utf-8",
                "{\"code\":401,\"message\":\"token不存在或者过期\"}".toByteArray(),
            )
            return
        }
        liveCalls.incrementAndGet()
        respondJson(exchange, liveJson)
    }

    // ── ② 课表 CDN ────────────────────────────────────────────────

    /**
     * `gh-release.xjtutoolbox.com` 那一半：只认 `GET /?file=static/empty_room/{日期}.json`。
     *
     * [NO_DATA_DATE] 那天回 **404**（不是空表）—— `EmptyRoomApi.fetchDayData` 把 404 当成
     * 「这天还没生成」抛 `NoDataException`，与「请求失败」是两条不同的分支，夹具得能分开这两者。
     */
    fun handleCdn(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        if (path != "/" || exchange.requestMethod != "GET") {
            badRequest(exchange, "CDN 只认 GET /，收到：${exchange.requestMethod} $path?$query")
            return
        }
        val file = param(query, "file")
        if (file == null || !file.startsWith(CDN_FILE_PREFIX) || !file.endsWith(".json")) {
            badRequest(exchange, "CDN 的 file 参数不是 ${CDN_FILE_PREFIX}<日期>.json，收到：$query")
            return
        }
        val date = file.removePrefix(CDN_FILE_PREFIX).removeSuffix(".json")
        lastCdnDate = date
        if (date == NO_DATA_DATE) {
            respond(exchange, 404, "text/plain; charset=utf-8", "no such day: $date".toByteArray())
            return
        }
        cdnCalls.incrementAndGet()
        respondJson(exchange, dayJson)
    }

    // ── 响应小工具（与其余夹具同形）────────────────────────────────

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.close()
    }

    private fun respondJson(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun respondHtml(exchange: HttpExchange, body: String) =
        respond(exchange, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun notFound(exchange: HttpExchange, origin: String) {
        val url = exchange.requestURI.toString()
        respond(exchange, 404, "text/plain; charset=utf-8", "no such emptyroom path at $origin: $url".toByteArray())
    }

    private fun badRequest(exchange: HttpExchange, message: String) =
        respond(exchange, 400, "text/plain; charset=utf-8", message.toByteArray())

    private fun param(raw: String, name: String): String? =
        raw.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}
