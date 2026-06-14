package com.xjtu.toolbox.library

import android.util.Log
import com.xjtu.toolbox.auth.LibraryLogin
import okhttp3.Request
import org.jsoup.Jsoup

// ══════ 数据类 ══════

data class SeatInfo(
    val seatId: String,
    val available: Boolean
)

/** 区域统计：空座/总数 */
data class AreaStats(val available: Int, val total: Int) {
    val isOpen get() = total > 0
    val label get() = "${available}/${total}"
}

/** 预约结果（含失败原因） */
data class BookResult(
    val success: Boolean,
    val message: String,
    val finalUrl: String = ""
)

/**
 * 座位推荐偏好权重 v2。
 * 所有 Int 字段表示分数加减量（0=不在意；正数=偏好；负数=刻意反向）。
 */
data class RecommendPrefs(
    // ── 核心：孤独/同桌隔离 ──────────────────────────────────────
    /** 同桌邻座空闲度权重（每个空邻座加 N 分，默认 3） */
    val emptinessWeight: Int = 3,
    /** 同桌全空时的额外奖励 */
    val allEmptyBonus: Int = 5,
    /** 同桌每多一位占座者扣 N 分；0=无所谓，3=独享型，5=极度介意 */
    val isolationLevel: Int = 2,

    // ── 噪音/干扰 ──────────────────────────────────────────────────
    /** 入口端（近 rowLength-1 侧）扣分 */
    val avoidEntrancePenalty: Int = 2,
    /** 出口端（unitIndexInRow ≤ 1 侧）扣分 */
    val avoidExitPenalty: Int = 0,
    /** 左右相邻12座单元占用率 > 50% 时扣分 */
    val avoidAdjacentBusyPenalty: Int = 3,

    // ── 空间/朝向偏好 ─────────────────────────────────────────────────
    /** 靠墙（F/K 席）加分；正=喜欢靠墙，负=喜欢靠走廊 */
    val wallBias: Int = 1,
    /** 行首/末角落单元加分 */
    val cornerBias: Int = 1,
    /** 走廊侧座位扣分：优先远离走廊，宁愿背后有人也不面对走廊 */
    val corridorSidePenalty: Int = 2,
    /** 背对走廊/面朝内侧（H/L 席）加分 */
    val facingWallBias: Int = 0,
    /** F 席对面人多时按 acrossCount 比例扣分 */
    val avoidFacingCrowdBias: Int = 0,

    // ── 时段/历史（可选） ──────────────────────────────────────────
    /** 是否启用时段自动微调（上午偏深区、下午偏靠墙） */
    val enableTimeSlotAdjust: Boolean = false,
    /** 历史偏好 gridX 范围内加分 */
    val preferredGridXRange: IntRange? = null,
    /** 历史偏好 gridY 范围内加分 */
    val preferredGridYRange: IntRange? = null,
    /** 历史区域加分量；0=禁用 */
    val historyBias: Int = 0,
)

/** "我的预约"信息 */
data class MyBookingInfo(
    val seatId: String?,
    val area: String?,
    val statusText: String?,
    val actionUrls: Map<String, String>
)

sealed class SeatResult {
    data class Success(
        val seats: List<SeatInfo>,
        val areaStatsMap: Map<String, AreaStats> = emptyMap()
    ) : SeatResult()
    data class AuthError(val message: String, val htmlPreview: String = "") : SeatResult()
    data class Error(val message: String) : SeatResult()
}

// ══════ LibraryApi ══════

class LibraryApi(private val login: LibraryLogin) {

    companion object {
        private const val BASE_URL = "http://rg.lib.xjtu.edu.cn:8086"
        private const val TAG = "LibraryApi"

        val AREA_MAP = linkedMapOf(
            "北楼二层外文库（东）" to "north2east",
            "二层连廊及流通大厅" to "north2elian",
            "北楼二层外文库（西）" to "north2west",
            "南楼二层大厅" to "south2",
            "北楼三层ILibrary-B（西）" to "west3B",
            "大屏辅学空间" to "eastnorthda",
            "南楼三层中段" to "south3middle",
            "北楼三层ILibrary-A（东）" to "east3A",
            "北楼四层西侧" to "north4west",
            "北楼四层中间" to "north4middle",
            "北楼四层东侧" to "north4east",
            "北楼四层西南侧" to "north4southwest",
            "北楼四层东南侧" to "north4southeast"
        )

        val FLOORS = linkedMapOf(
            "二楼" to listOf("北楼二层外文库（东）", "二层连廊及流通大厅", "北楼二层外文库（西）", "南楼二层大厅"),
            "三楼" to listOf("北楼三层ILibrary-B（西）", "大屏辅学空间", "南楼三层中段", "北楼三层ILibrary-A（东）"),
            "四楼" to listOf("北楼四层西侧", "北楼四层中间", "北楼四层东侧", "北楼四层西南侧", "北楼四层东南侧")
        )

        private val FLOOR_CODES = mapOf(
            "二楼" to "xingqing2floor",
            "三楼" to "xingqing3floor",
            "四楼" to "xingqing4floor"
        )

        private val AREA_FLOOR_CODES = buildMap {
            FLOORS.forEach { (floor, areas) ->
                val floorCode = FLOOR_CODES[floor] ?: return@forEach
                areas.forEach { area ->
                    AREA_MAP[area]?.let { put(it, floorCode) }
                }
            }
        }

        /** 从 scount 原始数据中只保留和 AREA_MAP value 匹配的独立区域 code */
        private val VALID_AREA_CODES = AREA_MAP.values.toSet()

        /** 反向映射：areaCode → 区域显示名 */
        val AREA_MAP_REVERSE = AREA_MAP.entries.associate { (name, code) -> code to name }

        /**
         * 座位号正则：匹配字母前缀的 (C08, Y003) 和纯数字零开头的 (002, 019)。
         * 东南侧/西南侧的座位是纯数字编号，没有字母前缀。
         */
        val SEAT_ID_REGEX = Regex("""(?:[A-Z]\d{2,4}|\b\d{3}\b)""")

        fun filterScount(raw: Map<String, AreaStats>): Map<String, AreaStats> =
            raw.filterKeys { it in VALID_AREA_CODES }

        fun guessAreaCode(seatId: String): String? {
            val prefix = seatId.firstOrNull()?.uppercaseChar() ?: return null
            return when (prefix) {
                'A', 'B' -> "north2elian"
                'D', 'E' -> "north2east"
                'C' -> "south2"
                'N' -> "north2west"
                'Y' -> "west3B"
                'P' -> "eastnorthda"
                'X' -> "east3A"
                'K', 'L', 'M' -> "north4west"
                'J' -> "north4middle"
                'H', 'F', 'G' -> "north4east"
                'Q' -> "north4southwest"
                'T' -> "north4southeast"
                else -> null
            }
        }
    }

    @Volatile
    var cachedAreaStats: Map<String, AreaStats> = emptyMap()
        private set

    /**
     * 带有界超时的派生 client：复用底层 cookieJar / interceptor（含 WebVPN），
     * 仅追加 callTimeout，避免座位请求在弱网下长时间卡转圈（默认 client 是 25-30s）。
     */
    private val timedClient: okhttp3.OkHttpClient by lazy {
        login.client.newBuilder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun buildRequest(url: String, ajax: Boolean = false, referer: String = "$BASE_URL/seat/"): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Referer", referer)
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest")
            b.header("Accept", "application/json, text/javascript, */*; q=0.01")
        }
        return b.get().build()
    }

    private fun isRedirectedToLogin(body: String, finalUrl: String): Boolean =
        body.contains("id=\"loginForm\"") || body.contains("name=\"execution\"") ||
        body.contains("cas/login") || finalUrl.contains("login.xjtu.edu.cn") ||
        com.xjtu.toolbox.auth.XJTULogin.isAuthFailureResponse(body)

    /**
     * 执行请求，如果被重定向到 CAS 登录页则自动 reAuthenticate 并重试
     */
    private fun executeWithReAuth(request: Request): Pair<okhttp3.Response, String> {
        val response = timedClient.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (isRedirectedToLogin(body, response.request.url.toString())) {
            Log.d(TAG, "executeWithReAuth: redirected to login, trying reAuthenticate...")
            response.close()
            if (login.reAuthenticate()) {
                val retryResponse = timedClient.newCall(request).execute()
                val retryBody = retryResponse.body?.string() ?: ""
                return retryResponse to retryBody
            }
            throw com.xjtu.toolbox.auth.AuthExpiredException("图书馆")
        }
        return response to body
    }

    // ── 座位查询 ──

    private fun loadFloorContext(areaCode: String): Map<String, AreaStats> {
        val floorCode = AREA_FLOOR_CODES[areaCode] ?: return emptyMap()
        val qspaceUrl = "$BASE_URL/qspace?lang=zh&floor=$floorCode"
        val (response, body) = executeWithReAuth(
            buildRequest(qspaceUrl, ajax = true, referer = "$BASE_URL/seat/")
        )
        response.close()
        if (!response.isSuccessful) {
            throw RuntimeException("楼层信息加载失败: HTTP ${response.code}")
        }
        val json = org.json.JSONObject(body)
        val stats = parseAreaStats(json.optJSONObject("scount"))
        cachedAreaStats = filterScount(stats)
        return cachedAreaStats
    }

    private fun parseAreaStats(scountObj: org.json.JSONObject?): Map<String, AreaStats> {
        if (scountObj == null) return emptyMap()
        val result = mutableMapOf<String, AreaStats>()
        val keys = scountObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.isBlank()) continue
            val arr = scountObj.optJSONArray(key) ?: continue
            if (arr.length() >= 2) {
                result[key] = AreaStats(available = arr.optInt(1), total = arr.optInt(0))
            }
        }
        return result
    }

    fun getSeats(areaCode: String): SeatResult {
        val response: okhttp3.Response
        val body: String
        try {
            // The site stores the selected floor in session state. Mirror the browser's
            // HAR sequence: qspace(floor) -> qseat(area).
            loadFloorContext(areaCode)
            val floorCode = AREA_FLOOR_CODES[areaCode]
            val referer = if (floorCode != null) "$BASE_URL/qspace?lang=zh&floor=$floorCode"
                else "$BASE_URL/seat/"
            // 走 executeWithReAuth：命中登录页会自动 reAuthenticate，
            // 仍失败则抛 AuthExpiredException（由 LibraryScreen 捕获触发静默重登）。
            val (resp, respBody) = executeWithReAuth(
                buildRequest("$BASE_URL/qseat?sp=$areaCode", ajax = true, referer = referer)
            )
            response = resp
            body = respBody
            response.close()
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            throw e   // 透传给 UI 层做静默重登，不要降级成普通错误
        } catch (e: Exception) {
            Log.e(TAG, "getSeats network error", e)
            return SeatResult.Error("网络请求失败: ${e.message}")
        }

        val finalUrl = response.request.url.toString()
        Log.d(TAG, "qseat: code=${response.code}, url=$finalUrl, len=${body.length}")

        if (isRedirectedToLogin(body, finalUrl))
            return SeatResult.AuthError("认证已失效")
        if (body.length < 10)
            return SeatResult.Error("服务器返回异常")

        try {
            val json = org.json.JSONObject(body)

            // 解析 scount（全局区域统计）
            val statsMap = parseAreaStats(json.optJSONObject("scount"))
            cachedAreaStats = filterScount(statsMap).ifEmpty { cachedAreaStats }
            Log.d(TAG, "scount: ${cachedAreaStats.size} areas open")

            // 解析 seat 对象
            val seatObj = json.optJSONObject("seat")
            if (seatObj == null || seatObj.length() == 0) {
                return SeatResult.Success(emptyList(), cachedAreaStats)
            }

            val seatList = mutableListOf<SeatInfo>()
            val seatKeys = seatObj.keys()
            while (seatKeys.hasNext()) {
                val seatId = seatKeys.next()
                val status = seatObj.optInt(seatId, -1)
                seatList.add(SeatInfo(seatId, status == 0))
            }

            seatList.sortWith(compareBy<SeatInfo>(
                { it.seatId.firstOrNull { c -> c.isLetter() } ?: ' ' },
                { it.seatId.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            ))

            Log.d(TAG, "seats: ${seatList.size} total, ${seatList.count { it.available }} avail")
            return SeatResult.Success(seatList, cachedAreaStats)
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "JSON parse error", e)
            return SeatResult.Error("座位数据解析失败: ${e.message}")
        }
    }

    // ── 预约座位（带详细原因） ──

    /**
     * 预约座位。如果已有预约，系统会返回换座确认页面 → 自动确认换座。
     * @param autoSwap 是否自动确认换座（默认 true）
     */
    fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean = true): BookResult {
        val url = "$BASE_URL/seat/?kid=$seatId&sp=$areaCode"
        val response: okhttp3.Response
        val html: String
        try {
            // 走 executeWithReAuth：命中登录页自动静默重登后重放，避免"预约失败：登录已失效"
            val (resp, respBody) = executeWithReAuth(buildRequest(url))
            response = resp
            html = respBody
            response.close()
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            return BookResult(false, "登录状态已失效，请退出图书馆页面后重新进入")
        } catch (e: Exception) {
            return BookResult(false, "网络异常: ${e.message}")
        }

        val finalUrl = response.request.url.toString()
        val success = "/my/" in finalUrl || "/seat/my/" in finalUrl

        if (success) {
            return BookResult(true, "✓ 座位 $seatId 预约成功！", finalUrl)
        }

        // 已有预约时，使用 /updateseat/ 端点直接换座（HAR 验证的真实流程）
        if (autoSwap) {
            val bodyText = Jsoup.parse(html).body()?.text() ?: ""
            if ("已有预约" in bodyText || "已预约" in bodyText || "换座" in bodyText
                || "已经预约" in bodyText || "存在预约" in bodyText) {
                Log.d(TAG, "bookSeat: existing booking detected, using /updateseat/ endpoint")
                return swapSeat(seatId, areaCode)
            }
        }

        val reason = parseBookingFailure(html)
        return BookResult(false, reason, finalUrl)
    }

    /**
     * 动作前的页面预取。HAR(2026-06-14) 实证浏览器在换座/取消前的真实流程：
     *   1) 先 GET 动作所在页面（/updateseat/ 或 /my/）——动作请求的 Referer 必须是该页面，
     *      否则服务端拒绝（这是换座/取消「无效」的根因，**直连/校外都会发生**）；
     *   2) WebVPN 模式下再 GET `wengine-vpn/cookie?...&path=<page>` 拿 path 级代理 cookie（仅校外需要）。
     */
    /** GET 一个页面，并在 WebVPN 模式下补取该 path 的 wengine cookie。 */
    private fun loadPageWithVpnCookie(path: String) {
        val resp = timedClient.newCall(buildRequest("$BASE_URL$path")).execute()
        val finalUrl = resp.request.url.toString()
        resp.close()
        if (com.xjtu.toolbox.util.WebVpnUtil.isWebVpnUrl(finalUrl)) {
            val cookieUrl = "https://webvpn.xjtu.edu.cn/wengine-vpn/cookie" +
                "?method=get&host=rg.lib.xjtu.edu.cn&scheme=http&path=$path" +
                "&vpn_timestamp=${System.currentTimeMillis()}"
            timedClient.newCall(buildRequest(cookieUrl)).execute().use { it.body?.string() }
        }
    }

    /**
     * 动作前完整复刻浏览器流程（HAR 2026-06-14 实证）：先看 /my/，再进入动作页面 [pagePath]。
     * 动作请求的 Referer 必须是该页面，否则服务端拒绝——这是换座/取消「无效」的根因，**直连/校外都会发生**。
     */
    private fun preflight(pagePath: String) {
        runCatching {
            loadPageWithVpnCookie("/my/")
            if (pagePath != "/my/") loadPageWithVpnCookie(pagePath)
            Log.d(TAG, "preflight ok for $pagePath")
        }
    }

    /**
     * 换座：复刻浏览器流程 GET /my/ → GET /updateseat/ → GET /updateseat/?kid=&sp=（Referer=/updateseat/）。
     * **以换座后的实际预约状态判定成功**，不再靠重定向/文案猜测。
     */
    fun swapSeat(seatId: String, areaCode: String): BookResult {
        val url = "$BASE_URL/updateseat/?kid=$seatId&sp=$areaCode"
        Log.d(TAG, "swapSeat: $url")
        preflight("/updateseat/")
        return try {
            val (resp, html) = executeWithReAuth(buildRequest(url, referer = "$BASE_URL/updateseat/"))
            resp.close()
            val finalUrl = resp.request.url.toString()
            // 实测：换座后查询「我的预约」，座位号变成目标即真成功
            val after = runCatching { getMyBooking() }.getOrNull()
            val booked = after?.seatId
            val ok = booked != null && (booked.equals(seatId, true) ||
                booked.contains(seatId, true) || seatId.contains(booked, true))
            if (ok) BookResult(true, "✓ 已换座到 ${booked}！", finalUrl)
            else if ("/my/" in finalUrl && booked != null)
                BookResult(true, "✓ 换座请求已提交，当前预约：$booked", finalUrl)
            else BookResult(false, "换座未生效${booked?.let { "（当前仍为 $it）" } ?: ""}：${parseBookingFailure(html)}", finalUrl)
        } catch (e: Exception) {
            if (e is com.xjtu.toolbox.auth.AuthExpiredException)
                BookResult(false, "登录状态已失效，请退出图书馆页面后重新进入")
            else BookResult(false, "换座请求失败: ${e.message}")
        }
    }

    private fun parseBookingFailure(html: String): String {
        val doc = Jsoup.parse(html)
        val alertText = doc.select(".alert, .error, .msg, .message, .warn, .notice, #msg, .tip").text()
        if (alertText.isNotBlank()) return alertText

        val bodyText = doc.body()?.text() ?: ""
        return when {
            "30分钟" in bodyText || "30 min" in bodyText -> "30 分钟内不能重复预约\n‣ 取消后 30 分钟内不能重新预约"
            "已被预约" in bodyText || "已被占" in bodyText -> "该座位已被他人预约\n‣ 已自动刷新座位列表"
            "已有预约" in bodyText || "已预约" in bodyText -> "您已有其他座位预约\n‣ 如需更换，请先取消当前预约"
            "不在预约时间" in bodyText || "未开放" in bodyText -> "当前不在预约开放时间\n‣ 预约通常在 22:00 开放次日抢座"
            "维护" in bodyText -> "系统维护中，请稍后再试"
            isRedirectedToLogin(html, "") -> "登录状态已失效"
            else -> "预约失败（未知原因）"
        }
    }

    // ── 我的预约 ──

    fun getMyBooking(): MyBookingInfo? {
        // HAR 2026-06-13 shows /my/ is the canonical booking page.
        val candidateUrls = buildList {
            add("$BASE_URL/my/")
            add("$BASE_URL/seat/my/")
            add("$BASE_URL/seat/my")
        }.distinct()

        for (url in candidateUrls) {
            try {
                val (response, html) = executeWithReAuth(
                    buildRequest(url, referer = "$BASE_URL/seat/")
                )
                val finalUrl = response.request.url.toString()
                response.close()

                if (html.length < 50 || isRedirectedToLogin(html, finalUrl)) continue

                val doc = Jsoup.parse(html, finalUrl)
                val bodyText = doc.body()?.text() ?: ""
                Log.d(TAG, "my page try $url body (500): ${bodyText.take(500)}")

                if ("Not Found" in bodyText && bodyText.length < 800) continue

                // 检查有无预约内容（座位号 + 预约状态）
                val hasSeatId = SEAT_ID_REGEX.containsMatchIn(bodyText)
                val hasStatus = "预约状态" in bodyText

                if (!hasSeatId) {
                    if (listOf("暂无", "没有预约", "无预约", "暂无预约").any { it in bodyText }) {
                        Log.d(TAG, "getMyBooking: no active booking at $url")
                        return null
                    }
                    continue
                }

                // 有座位号，解析活跃预约
                val result = parseActiveBooking(doc, bodyText, html, finalUrl)
                if (result != null) return result

                // 有座位号但无活跃预约（全部已取消）
                if (hasStatus) {
                    Log.d(TAG, "getMyBooking: all bookings cancelled at $url")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "getMyBooking: error trying $url", e)
            }
        }

        Log.d(TAG, "getMyBooking: no booking found across all candidate URLs")
        return null
    }

    /**
     * 从预约页面解析【活跃】预约。
     * 页面可能包含多条预约记录（含已取消的），只返回第一条活跃预约。
     */
    private fun parseActiveBooking(
        doc: org.jsoup.nodes.Document, bodyText: String,
        html: String, finalUrl: String
    ): MyBookingInfo? {
        val inactiveStatuses = setOf("已取消", "已完成", "已过期", "已失效", "已违约", "超时取消", "超时未入馆", "超时", "已离馆")

        // 按"预约状态"分割文本，找到活跃预约的文本块
        val statusRegex = Regex("""预约状态[:：]\s*(\S+)""")
        val statusMatches = statusRegex.findAll(bodyText).toList()

        if (statusMatches.isEmpty()) {
            // 无明确状态标记，回退：找第一个座位号
            val seatId = SEAT_ID_REGEX.find(bodyText)?.value ?: return null
            val area = AREA_MAP.keys.firstOrNull { it in bodyText }
            val actionUrls = parseActionsFromHtml(doc, html)
            Log.d(TAG, "getMyBooking (no status): seatId=$seatId, area=$area")
            return MyBookingInfo(seatId, area, null, actionUrls)
        }

        var blockStart = 0
        for (statusMatch in statusMatches) {
            val status = statusMatch.groupValues[1]
            val blockEnd = statusMatch.range.last + 1
            val blockText = bodyText.substring(blockStart, blockEnd)

            if (status in inactiveStatuses) {
                blockStart = blockEnd
                continue
            }

            // 找到活跃预约！提取该文本块内的信息
            val seatId = SEAT_ID_REGEX.findAll(blockText).lastOrNull()?.value
            if (seatId == null) {
                blockStart = blockEnd
                continue
            }

            val area = AREA_MAP.keys.firstOrNull { it in blockText }
            val actionUrls = parseActionsFromHtml(doc, html)

            Log.d(TAG, "getMyBooking: seatId=$seatId, area=$area, status=$status, actions=${actionUrls.keys}")
            return MyBookingInfo(seatId, area, status, actionUrls)
        }

        return null // 所有预约都是非活跃状态
    }

    /**
     * 从 HTML DOM 中提取操作按钮的真实 URL。
     * 处理 href="#" 的 JavaScript 按钮：检查 onclick、data-* 属性、表单、脚本。
     */
    private fun parseActionsFromHtml(doc: org.jsoup.nodes.Document, html: String): MutableMap<String, String> {
        val actionUrls = mutableMapOf<String, String>()
        val navTexts = setOf("座位预约", "我预约的座位", "我预约的图书", "跨校", "提存", "典藏", "意见反馈",
            "资料修改", "活动查询", "注销", "常见问题", "其他功能", "Toggle navigation", "首页",
            "English version", "确认操作", "确认", "取消", "×")

        // 1. 扫描所有 <a> 和 <button>
        doc.body()?.select("a[href], button[onclick], a[onclick], a[data-href], a[data-url]")?.forEach { el ->
            val text = el.text().trim()
            if (text.isBlank() || text in navTexts || text.length > 15) return@forEach
            if ("logout" in (el.attr("href") + el.attr("onclick")).lowercase()) return@forEach

            // 获取真实 URL（优先级：data 属性 > onclick > href）
            val realUrl = el.attr("data-href").ifBlank { null }
                ?: el.attr("data-url").ifBlank { null }
                ?: el.attr("data-action").ifBlank { null }
                ?: extractUrlFromOnclick(el.attr("onclick"))
                ?: el.attr("abs:href").let { href ->
                    if (href.isBlank() || href.endsWith("#") || href == "#" || "javascript:" in href) null
                    else href
                }

            val label = classifyActionLabel(text) ?: return@forEach
            if (realUrl != null) {
                actionUrls[label] = realUrl
                Log.d(TAG, "getMyBooking action found: $label -> $realUrl (from DOM)")
            }
        }

        // 2. 从 <form> 中提取
        doc.select("form[action]").forEach { form ->
            val action = form.attr("abs:action").ifBlank { return@forEach }
            val submitText = form.select("button[type=submit], input[type=submit]").firstOrNull()?.let {
                it.text().ifBlank { it.attr("value") }
            } ?: return@forEach
            val label = classifyActionLabel(submitText)
            if (label != null && action.isNotBlank() && !action.endsWith("#")) {
                actionUrls[label] = action
                Log.d(TAG, "getMyBooking action found: $label -> $action (from form)")
            }
        }

        // 3. 从 <script> 中提取操作 URL 作为后备
        if (actionUrls.isEmpty() || "取消预约" !in actionUrls) {
            extractActionsFromScripts(doc).forEach { (label, url) ->
                if (label !in actionUrls) {
                    actionUrls[label] = url
                    Log.d(TAG, "getMyBooking action found: $label -> $url (from script)")
                }
            }
        }

        return actionUrls
    }

    private fun classifyActionLabel(text: String): String? = when {
        "取消" in text && "预约" in text -> "取消预约"
        "线上签到" in text -> "入馆签到"
        "首次入馆" in text || "入馆" in text && "离" !in text && "返" !in text -> "入馆签到"
        "签到" in text && "回馆" !in text && "离" !in text && "返" !in text -> "入馆签到"
        "中途离开" in text -> "中途离开"
        "离馆" in text || "暂离" in text || "中途离" in text -> "中途离开"
        "中途返回" in text -> "中途返回"
        "回馆" in text || "返回签到" in text || "中途返" in text -> "中途返回"
        "换座" in text -> "我想换座"
        "取消" in text -> "取消预约"
        else -> null
    }

    /** 从 onclick="..." 中提取 URL */
    private fun extractUrlFromOnclick(onclick: String?): String? {
        if (onclick.isNullOrBlank()) return null
        // location.href = '/my/cancel/123'
        Regex("""(?:location\.href|location|window\.location)\s*=\s*['"]([^'"]+)['"]""")
            .find(onclick)?.groupValues?.get(1)?.let { return it }

        // showConfirmModal('msg', 'action', 'id')
        // action 映射：cancel → /my/?cancel=1&ri={id}
        //              ruguan1 → /my/?firstruguan=1&ri={id}
        //              midleave → /my/?midleave=1&ri={id}
        //              midreturn → /my/?midreturn=1&ri={id}
        Regex("""showConfirmModal\s*\(\s*['"][^'"]*['"]\s*,\s*'(\w+)'\s*,\s*'(\d+)'\s*\)""")
            .find(onclick)?.let { match ->
                val action = match.groupValues[1]
                val id = match.groupValues[2]
                return buildActionUrl(action, id)
            }

        // someFunc('/url/path')
        Regex("""['"](/[^'"]+)['"]""").find(onclick)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /**
     * 将 showConfirmModal 中的 action 类型映射为真实 URL
     */
    private fun buildActionUrl(action: String, reserveId: String): String? {
        return when (action) {
            "cancel" -> "$BASE_URL/my/?cancel=1&ri=$reserveId"
            "ruguan1" -> "$BASE_URL/my/?firstruguan=1&ri=$reserveId"
            "leave", "midleave" -> "$BASE_URL/my/?midleave=1&ri=$reserveId"
            "return", "midreturn" -> "$BASE_URL/my/?midreturn=1&ri=$reserveId"
            else -> {
                Log.w(TAG, "Unknown showConfirmModal action: $action (ri=$reserveId)")
                null
            }
        }
    }

    /** 从 <script> 标签中搜索操作 URL */
    private fun extractActionsFromScripts(doc: org.jsoup.nodes.Document): Map<String, String> {
        val found = mutableMapOf<String, String>()
        doc.select("script").forEach { script ->
            val code = script.data()
            if (code.length < 20) return@forEach

            // 从 showConfirmModal 调用中提取操作 URL
            // showConfirmModal('msg', 'cancel', '4617835')
            Regex("""showConfirmModal\s*\(\s*['"][^'"]*['"]\s*,\s*'(\w+)'\s*,\s*'(\d+)'\s*\)""")
                .findAll(code).forEach { match ->
                    val action = match.groupValues[1]
                    val id = match.groupValues[2]
                    val url = buildActionUrl(action, id)
                    if (url != null) {
                        val label = when (action) {
                            "cancel" -> "取消预约"
                            "ruguan1" -> "入馆签到"
                            "leave", "midleave" -> "中途离开"
                            "return", "midreturn" -> "中途返回"
                            else -> null
                        }
                        if (label != null && label !in found) {
                            found[label] = url
                            Log.d(TAG, "extractActionsFromScripts: $label -> $url (from showConfirmModal)")
                        }
                    }
                }

            // 从 switch/case 或 url 拼接中提取动态 URL
            // url = "/my/?cancel=1&ri=" + currentId
            Regex("""['"](/my/\?(?:cancel|firstruguan|midleave|midreturn)=1&ri=)\s*['"]?\s*\+?\s*(?:['"]?(\d+)['"]?|(\w+))""")
                .findAll(code).forEach { match ->
                    val urlPrefix = match.groupValues[1]
                    val directId = match.groupValues[2]
                    if (directId.isNotEmpty()) {
                        val fullUrl = "$BASE_URL$urlPrefix$directId"
                        val label = when {
                            "cancel" in urlPrefix -> "取消预约"
                            "firstruguan" in urlPrefix -> "入馆签到"
                            "midleave" in urlPrefix -> "中途离开"
                            "midreturn" in urlPrefix -> "中途返回"
                            else -> null
                        }
                        if (label != null && label !in found) {
                            found[label] = fullUrl
                        }
                    }
                }

            // 取消相关: "/my/cancel", "/cancel", "/reserve/cancel" (旧路径格式兼容)
            listOf(
                Regex("""['"]([^'"]*(?:cancel|quxiao|取消)[^'"]*(?:reserve|booking|seat)?[^'"]*)['"]"""),
                Regex("""url\s*[:=]\s*['"]([^'"]*cancel[^'"]+)['"]"""),
                Regex("""['"](/my/cancel[^'"]*)['"]""")
            ).forEach { pattern ->
                pattern.find(code)?.groupValues?.get(1)?.let { url ->
                    if (url.startsWith("/") && url.length > 2 && "取消预约" !in found) found["取消预约"] = if (url.startsWith("/")) "$BASE_URL$url" else url
                }
            }
            // 签到相关
            listOf(
                Regex("""['"]([^'"]*(?:checkin|signin|签到|firstruguan)[^'"]*)['"]"""),
                Regex("""['"](/my/checkin[^'"]*)['"]"""),
                Regex("""['"](/my/\?firstruguan[^'"]*)['"]""")
            ).forEach { pattern ->
                pattern.find(code)?.groupValues?.get(1)?.let { url ->
                    if (url.startsWith("/") && url.length > 2 && "入馆签到" !in found) found["入馆签到"] = if (url.startsWith("/")) "$BASE_URL$url" else url
                }
            }
        }
        return found
    }

    /** 执行操作（签到/离馆/回馆/取消） */
    fun executeAction(actionUrl: String): BookResult {
        // 兜底：相对路径补全 scheme + host
        val normalizedUrl = if (actionUrl.startsWith("/")) "$BASE_URL$actionUrl" else actionUrl
        // 取消/入馆/离馆等动作的页面与 Referer 均为 /my/（path 取 ? 之前部分）
        val pagePath = normalizedUrl.substringAfter(BASE_URL, "/my/").substringBefore("?")
        preflight(pagePath)
        try {
            val (response, html) = executeWithReAuth(buildRequest(normalizedUrl, referer = "$BASE_URL$pagePath"))
            response.close()
            val finalUrl = response.request.url.toString()
            Log.d(TAG, "action: url=$actionUrl, finalUrl=$finalUrl, len=${html.length}")

            val doc = Jsoup.parse(html)
            val bodyText = doc.body()?.text() ?: ""
            val msg = doc.select(".alert, .msg, .message, .success, .error").text()

            // 取消预约：以「我的预约是否已消失」为准判定，最可靠
            if ("cancel" in actionUrl.lowercase()) {
                val stillBooked = runCatching { getMyBooking() }.getOrNull()?.seatId != null
                return if (!stillBooked) BookResult(true, msg.ifBlank { "✓ 已取消预约" }, finalUrl)
                else BookResult(false, "取消未生效：当前仍有预约。${msg}", finalUrl)
            }

            val success = listOf("成功", "success", "已取消", "取消成功").any { it in bodyText.lowercase() }
                    || "/my/" in finalUrl
            return BookResult(success, msg.ifBlank { if (success) "操作成功" else "操作可能未生效" }, finalUrl)
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            return BookResult(false, "登录状态已失效，请退出图书馆页面后重新进入")
        } catch (e: Exception) {
            return BookResult(false, "操作失败: ${e.message}")
        }
    }

    private data class ScoreEntry(val seat: SeatInfo, val score: Int)

    /** 区域分区标识符：防止 F/H → FH、K/L → KL 跨区污染 */
    private fun seatRegion(id: String): String = when (id.firstOrNull()) {
        'F', 'H' -> "FH"; 'K', 'L' -> "KL"
        else -> id.takeWhile { it.isLetter() }
    }

    /**
     * 相邻桌组单元的占用率（0.0–1.0）。
     * 通过 region 参数防止 F/H、G、M 等不同物理区跨区匹配。
     */
    private fun adjacentUnitOccupancyRate(
        pos: SeatNeighborData.SeatPosition,
        unitDelta: Int,
        region: String,
        posCache: Map<SeatInfo, SeatNeighborData.SeatPosition>,
        seatMap: Map<String, Boolean>
    ): Double {
        if (pos.rowIndex < 0 || pos.unitIndexInRow < 0) return 0.0
        val targetUnit = pos.unitIndexInRow + unitDelta
        if (targetUnit < 0 || targetUnit >= pos.rowLength) return 0.0
        val unitSeats = posCache.filter { (s, p) ->
            p.rowIndex == pos.rowIndex && p.unitIndexInRow == targetUnit
                && seatRegion(s.seatId) == region
        }
        if (unitSeats.isEmpty()) return 0.0
        val occupied = unitSeats.count { (s, _) -> seatMap[s.seatId] == false }
        return occupied.toDouble() / unitSeats.size
    }

    /**
     * 推荐座位：基于物理桌组邻座空闲度排序。
     * 使用 [SeatNeighborData] 的平面图标注数据确定"同桌"关系，
     * 优先推荐同桌全空的座位（安静/可与朋友同坐）。
     */
    fun recommendSeats(
        seats: List<SeatInfo>,
        areaCode: String,
        topN: Int = 5,
        prefs: RecommendPrefs = RecommendPrefs()
    ): List<SeatInfo> {
        val available = seats.filter { it.available }
        if (available.size <= topN) return available

        // 没有平面图数据的区域（rowLength=0=未覆盖）—— 不做推荐，避免给出无根据的结果
        if (SeatNeighborData.getSeatPosition(available.first().seatId, areaCode).rowLength == 0)
            return emptyList()

        val seatMap = seats.associate { it.seatId to it.available }

        // 预计算各有效座位的空间位置（供相邻单元可用率计算）
        val posCache = seats.associateWith { SeatNeighborData.getSeatPosition(it.seatId, areaCode) }

        // 预计算 SW/SE 左列走廊桌标记：
        //   左列(065-136): buildLeftColumnGroups 偶数批次=内侧桌, 奇数批次=走廊桌
        //   走廊桌座位满足 (136-num)%8 in 4..7，仅限该范围
        val corridorBenchCache: Set<String> = when {
            areaCode.endsWith("southwest") || areaCode.endsWith("southeast") ->
                available.filter { s ->
                    val n = s.seatId.toIntOrNull() ?: return@filter false
                    n in 65..136 && (136 - n) % 8 >= 4   // 左列走廊桌范围
                }.map { it.seatId }.toHashSet()
            else -> emptySet()
        }
        // corridorBenchColCache: 走廊桌内 col=1（最靠走廊列）
        //   d3(b-4, b-7, b-5, b-6): b-7→rem=7, b-6→rem=6 为 col1，最靠列间走廊
        //   例: m=5(b=136): 089=(136-89)%8=7✓, 090=(136-90)%8=6✓; 091=5✗, 092=4✗
        val corridorBenchColCache: Set<String> = when {
            corridorBenchCache.isNotEmpty() ->
                available.filter { s ->
                    val n = s.seatId.toIntOrNull() ?: return@filter false
                    if (n !in 65..136) return@filter false
                    val rem = (136 - n) % 8
                    rem == 7 || rem == 6   // col1: 最靠走廊列（b-7=idx1, b-6=idx3）
                }.map { it.seatId }.toHashSet()
            else -> emptySet()
        }
        val tableRowIndexCache: Map<String, Int> = emptyMap()  // 预留

        val scores = available.map { seat ->
            // ── A. 桌组空闲度（核心评分 + 孤独惩罚 + 双向末端距离奖励）──────────────
            val neighbors = SeatNeighborData.getNeighborSeats(seat.seatId, areaCode)
            val known     = neighbors.filter { it in seatMap }
            val avail     = known.count { seatMap[it] == true }
            val occupied  = known.size - avail
            // norm: 6座组=1.0, 12座=0.5, 2座=3.0——修正大组虚高问题
            // 防御: 若可见邻座数不足理论组员的 50%，应降级成第4座组（避免分母过小导致 norm 虚高）
            val norm      = when {
                known.isEmpty() -> 1f
                neighbors.isNotEmpty() && known.size < neighbors.size / 2 -> 6f / 4  // 数据不完整降级
                else -> 6f / known.size
            }
            var score     = (avail * prefs.emptinessWeight * norm).toInt()
            if (known.isNotEmpty() && occupied == 0) score += prefs.allEmptyBonus
            if (neighbors.size <= 2) score += 1                              // 小桌基础加分

            // 孤独惩罚（归一化+下界：至少 occupied×isolationLevel，防止大组 norm 变小让大桌有一人仍高分）
            val basePenalty = if (occupied == 0) 0 else
                maxOf(occupied * prefs.isolationLevel,
                      (occupied * prefs.isolationLevel * norm).toInt())
            // 小桌（norm≥1 = 4–6 座组）过半被占时产生"夹击感"，双倍计算孤独惩罚（夹击双倍）
            // 例：4 人桌已有 2 人 → 你被邻座+对面夹住 → penalty × 2
            val squeezed = norm >= 1f && occupied > 0 && occupied * 2 >= known.size
            score -= if (squeezed) basePenalty * 2 else basePenalty  // 夹击双倍
            // 全桌最后一席：追加"孤岛"惩罚（与 allEmptyBonus 对称），避免被误推荐进前 N
            if (known.isNotEmpty() && avail == 0) score -= prefs.allEmptyBonus

            // 双向对座组（isBiEntrance=J 区）末端距离奖励（桌组空闲度的细粒度修正）：
            // 同组内有人时，优先选离占座者更远的末端席（最大 +5），
            // 例：使 J144 > J133（同侧末端优先）
            val pos = posCache[seat] ?: SeatNeighborData.getSeatPosition(seat.seatId, areaCode)
            if (occupied > 0 && pos.gridY >= 0 && pos.isBiEntrance) {
                val occupiedGridYs = known
                    .filter { seatMap[it] == false }
                    .mapNotNull { id -> seats.find { s -> s.seatId == id }?.let { s -> posCache[s]?.gridY } }
                    .filter { it >= 0 }
                if (occupiedGridYs.isNotEmpty()) {
                    val minDist = occupiedGridYs.minOf { kotlin.math.abs(it - pos.gridY) }
                    score += minDist.coerceAtMost(5)
                }
            }

            // ── B. 噪音惩罚（入口 / 出口）──────────────────────────────────────────
            if (pos.isNearEntrance)           score -= prefs.avoidEntrancePenalty
            // 出口端：单入口区域 = 低索引端；双入口区域（isBiEntrance）两头均为出口
            val isExitEnd = pos.unitIndexInRow in 0..1 ||
                (pos.isBiEntrance && pos.rowLength > 0 && pos.unitIndexInRow >= pos.rowLength - 2)
            if (isExitEnd) score -= prefs.avoidExitPenalty

            // ── C. 邻近单元拥挤度──────────────────────────────────────────────────
            // 相邻左右单元的连续比例惩罚；有人时至少 -1，体现"相背拥挤"感
            if (prefs.avoidAdjacentBusyPenalty > 0) {
                val region    = seatRegion(seat.seatId)
                val leftRate  = adjacentUnitOccupancyRate(pos, -1, region, posCache, seatMap).toFloat()
                val rightRate = adjacentUnitOccupancyRate(pos, +1, region, posCache, seatMap).toFloat()
                if (leftRate  > 0f) score -= maxOf(1, (leftRate  * prefs.avoidAdjacentBusyPenalty * 3).toInt()).coerceAtMost(8)
                if (rightRate > 0f) score -= maxOf(1, (rightRate * prefs.avoidAdjacentBusyPenalty * 3).toInt()).coerceAtMost(8)
            }

            // SW/SE 配对桌占用惩罚：两张4人桌物理拼接但算法独立分组，
            // 检测同 rowIndex+unitIndex 但不属于自身组的被占邻桌
            val neighborsSet = neighbors.toSet()  // 预先转为 Set，供 buddyTable 过滤复用
            if (prefs.avoidAdjacentBusyPenalty > 0 && pos.rowLength > 0 && pos.unitIndexInRow >= 0) {
                val region = seatRegion(seat.seatId)
                val buddySeats = posCache.filter { (s, p) ->
                    p.rowIndex == pos.rowIndex && p.unitIndexInRow == pos.unitIndexInRow
                        && seatRegion(s.seatId) == region
                        && s.seatId !in neighborsSet && s.seatId != seat.seatId
                }
                if (buddySeats.isNotEmpty()) {
                    val buddyOccupied = buddySeats.count { (s, _) -> seatMap[s.seatId] == false }
                    if (buddyOccupied > 0) {
                        val buddyRate = buddyOccupied.toFloat() / buddySeats.size
                        // avoidAdjacentBusyPenalty * 2：配对桌比左右相邻单元距离更近，惩罚倍率提升
                        score -= maxOf(1, (buddyRate * prefs.avoidAdjacentBusyPenalty * 2).toInt())
                        // 夹击惩罚：本桌与配对桌同时有人 → "前后夹击"场景，追加强化惩罚
                        if (occupied > 0) {
                            score -= (occupied + buddyOccupied) * prefs.isolationLevel * 2
                        }
                    }
                }
            }

            // ── D. 空间/朝向偏好──────────────────────────────────────────────────
            if (prefs.wallBias != 0) score += (pos.wallProximityScore * prefs.wallBias).toInt()
            if (pos.isCornerUnit)    score += prefs.cornerBias
            // 走廊侧扣分：优先远离走廊，即使背后有人也强过正面对走廊
            if (pos.isCorridorSide && prefs.corridorSidePenalty != 0)
                score -= prefs.corridorSidePenalty
            // SW/SE 左列走廊桌惩罚：走廊桌(靠列间走廊)整体额外扣分
            //   内侧桌(096/093等) > 走廊桌col0(092/091等) > 走廊桌col1(089/090等)
            //   col1=最靠走廊列（d3里 b-7, b-6 的座位）再额外扣分一次
            if (seat.seatId in corridorBenchCache && prefs.corridorSidePenalty != 0) {
                score -= prefs.corridorSidePenalty  // 走廊桌整体再惩罚
                if (seat.seatId in corridorBenchColCache)
                    score -= prefs.corridorSidePenalty  // col1最靠走廊，再扣一次
            }
            @Suppress("UNUSED_VARIABLE")
            val tableRowIdx = tableRowIndexCache[seat.seatId] ?: -1  // 预留
            if (!pos.isWallSide && pos.facingDir != SeatNeighborData.FacingDir.UNKNOWN)
                score += prefs.facingWallBias
            if (pos.acrossCount > 0 && prefs.avoidFacingCrowdBias != 0)
                // ceil 除法避免小值截断为0（如 acrossCount=2, bias=1 → ceil(0.5)=1）
                score -= Math.ceil(pos.acrossCount * prefs.avoidFacingCrowdBias / 4.0).toInt()

            // ── E. 时段/历史（可选）────────────────────────────────────────────────
            if (prefs.enableTimeSlotAdjust && pos.rowLength > 0) {
                val hour = java.util.Calendar.getInstance()
                    .get(java.util.Calendar.HOUR_OF_DAY)
                when (hour) {
                    in 8..11  -> if (!pos.isNearEntrance && pos.unitIndexInRow < pos.rowLength / 2)
                                     score += 2   // 上午偏深区角落
                    in 13..17 -> if (pos.isWallSide) score += 1              // 下午偏靠墙
                }
            }

            if (prefs.historyBias > 0 && pos.gridX >= 0 && pos.gridY >= 0) {
                val inX = prefs.preferredGridXRange?.contains(pos.gridX) == true
                val inY = prefs.preferredGridYRange?.contains(pos.gridY) == true
                if (inX && inY) score += prefs.historyBias
            }

            ScoreEntry(seat, score)
        }

        // 扩散过滤：同行邻座只取1席，避免旁边两人都被推荐；
        //   背靠背/对面座位（不同行）可独立推荐，体现其独立选择价值
        val sorted      = scores.sortedByDescending { it.score }
        val scoreFloor  = if (sorted.firstOrNull()?.score ?: 0 > 0) 0 else Int.MIN_VALUE
        val result      = mutableListOf<SeatInfo>()
        val pickedGroup = mutableSetOf<String>()   // 已选座位及其旁侧成员集合
        for ((seat, sc) in sorted) {
            if (sc < scoreFloor) break            // 停止：余下全是低分，无需继续
            if (seat.seatId in pickedGroup) continue
            result.add(seat)
            pickedGroup.add(seat.seatId)
            pickedGroup.addAll(SeatNeighborData.getSideNeighbors(seat.seatId, areaCode))
            if (result.size >= topN) break
        }
        // 回填：高聚集场景扩散后不足 topN 时，放宽旁侧约束补入次优席
        // 仍遵守 scoreFloor：负分席不补入（极端拥挤时如实返回少于 topN 的结果，
        // 比硬塞劣质座位更诚实；只有全场皆负分时 scoreFloor=MIN_VALUE 才不限制）
        if (result.size < topN) {
            for ((seat, sc) in sorted) {
                if (sc < scoreFloor) break
                if (seat.seatId !in pickedGroup) {
                    result.add(seat)
                    pickedGroup.add(seat.seatId)
                    pickedGroup.addAll(SeatNeighborData.getSideNeighbors(seat.seatId, areaCode))
                }
                if (result.size >= topN) break
            }
        }

        return result
    }
}
