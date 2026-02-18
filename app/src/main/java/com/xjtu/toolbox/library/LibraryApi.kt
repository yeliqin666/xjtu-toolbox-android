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

        /** 从 scount 原始数据中只保留和 AREA_MAP value 匹配的独立区域 code */
        private val VALID_AREA_CODES = AREA_MAP.values.toSet()

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

    private fun buildRequest(url: String, ajax: Boolean = false): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Referer", "$BASE_URL/seat/")
        if (ajax) {
            b.header("X-Requested-With", "XMLHttpRequest")
            b.header("Accept", "application/json, text/javascript, */*; q=0.01")
        }
        return b.get().build()
    }

    private fun isRedirectedToLogin(body: String, finalUrl: String): Boolean =
        body.contains("id=\"loginForm\"") || body.contains("name=\"execution\"") ||
        body.contains("cas/login") || finalUrl.contains("login.xjtu.edu.cn")

    /**
     * 执行请求，如果被重定向到 CAS 登录页则自动 reAuthenticate 并重试
     */
    private fun executeWithReAuth(request: Request): Pair<okhttp3.Response, String> {
        val response = login.client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (isRedirectedToLogin(body, response.request.url.toString())) {
            Log.d(TAG, "executeWithReAuth: redirected to login, trying reAuthenticate...")
            response.close()
            if (login.reAuthenticate()) {
                val retryResponse = login.client.newCall(request).execute()
                val retryBody = retryResponse.body?.string() ?: ""
                return retryResponse to retryBody
            }
        }
        return response to body
    }

    // ── 座位查询 ──

    fun getSeats(areaCode: String): SeatResult {
        if (!login.seatSystemReady && login.diagnosticInfo.isNotEmpty()) {
            return SeatResult.AuthError(
                "座位系统认证未完成\n${login.diagnosticInfo}\n\n请确认：\n1. 已连接校园网或 VPN\n2. 图书馆系统在服务时间内"
            )
        }

        val response: okhttp3.Response
        val body: String
        try {
            response = login.client.newCall(buildRequest("$BASE_URL/qseat?sp=$areaCode", ajax = true)).execute()
            body = response.body?.use { it.string() } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "getSeats network error", e)
            return SeatResult.Error("网络请求失败: ${e.message}")
        }

        val finalUrl = response.request.url.toString()
        Log.d(TAG, "qseat: code=${response.code}, url=$finalUrl, len=${body.length}")

        if (isRedirectedToLogin(body, finalUrl))
            return SeatResult.AuthError("认证失败：请返回重试或检查网络")
        if (body.length < 10)
            return SeatResult.Error("服务器返回异常")

        try {
            val json = org.json.JSONObject(body)

            // 解析 scount（全局区域统计）
            val statsMap = mutableMapOf<String, AreaStats>()
            val scountObj = json.optJSONObject("scount")
            if (scountObj != null) {
                val keys = scountObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.isBlank()) continue
                    val arr = scountObj.optJSONArray(key)
                    if (arr != null && arr.length() >= 2) {
                        statsMap[key] = AreaStats(arr.getInt(0), arr.getInt(1))
                    }
                }
            }
            cachedAreaStats = filterScount(statsMap)
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
            response = login.client.newCall(buildRequest(url)).execute()
            html = response.body?.use { it.string() } ?: ""
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
     * 直接使用 /updateseat/ 端点换座。
     * 实际流程：GET /updateseat/?kid=<seatId>&sp=<areaCode> → 302 → /my/ (成功)
     */
    fun swapSeat(seatId: String, areaCode: String): BookResult {
        val url = "$BASE_URL/updateseat/?kid=$seatId&sp=$areaCode"
        Log.d(TAG, "swapSeat: $url")
        return try {
            val resp = login.client.newCall(buildRequest(url)).execute()
            val html = resp.body?.use { it.string() } ?: ""
            val finalUrl = resp.request.url.toString()
            val success = "/my/" in finalUrl || "成功换座" in html || "成功" in html
            if (success) BookResult(true, "✓ 已换座到 $seatId！", finalUrl)
            else BookResult(false, "换座失败: ${parseBookingFailure(html)}", finalUrl)
        } catch (e: Exception) {
            BookResult(false, "换座请求失败: ${e.message}")
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
            isRedirectedToLogin(html, "") -> "登录状态过期，请返回重新登录"
            else -> "预约失败（未知原因）"
        }
    }

    // ── 我的预约 ──

    fun getMyBooking(): MyBookingInfo? {
        // 策略：先从 /seat/ 主页提取"我预约的座位"链接，再跟踪
        val mainUrl = "$BASE_URL/seat/"
        val mainHtml: String
        val mainResponse: okhttp3.Response
        try {
            mainResponse = login.client.newCall(buildRequest(mainUrl)).execute()
            mainHtml = mainResponse.body?.use { it.string() } ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "getMyBooking: failed to load seat main page", e)
            return null
        }

        val mainFinalUrl = mainResponse.request.url.toString()
        if (mainHtml.length < 50 || isRedirectedToLogin(mainHtml, mainFinalUrl)) return null

        val mainDoc = Jsoup.parse(mainHtml, mainFinalUrl)
        val myBookingLink = mainDoc.select("a").firstOrNull { el ->
            val text = el.text().trim()
            "预约的座" in text || "我的预约" in text || "mybooking" in el.attr("href").lowercase()
        }?.attr("abs:href")?.ifBlank { null }

        val candidateUrls = buildList {
            myBookingLink?.let { add(it) }
            add("$BASE_URL/my/")
            add("$BASE_URL/seat/my/")
            add("$BASE_URL/seat/my")
        }.distinct()

        for (url in candidateUrls) {
            try {
                val response = login.client.newCall(buildRequest(url)).execute()
                val html = response.body?.use { it.string() } ?: continue
                val finalUrl = response.request.url.toString()

                if (html.length < 50 || isRedirectedToLogin(html, finalUrl)) continue

                val doc = Jsoup.parse(html, finalUrl)
                val bodyText = doc.body()?.text() ?: ""
                Log.d(TAG, "my page try $url body (500): ${bodyText.take(500)}")

                if ("Not Found" in bodyText && bodyText.length < 800) continue

                // 检查有无预约内容（座位号 + 预约状态）
                val hasSeatId = Regex("[A-Z]\\d{2,4}").containsMatchIn(bodyText)
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
        val inactiveStatuses = setOf("已取消", "已完成", "已过期", "已失效", "已违约", "超时取消")

        // 按"预约状态"分割文本，找到活跃预约的文本块
        val statusRegex = Regex("""预约状态[:：]\s*(\S+)""")
        val statusMatches = statusRegex.findAll(bodyText).toList()

        if (statusMatches.isEmpty()) {
            // 无明确状态标记，回退：找第一个座位号
            val seatId = Regex("[A-Z]\\d{2,4}").find(bodyText)?.value ?: return null
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
            val seatId = Regex("[A-Z]\\d{2,4}").findAll(blockText).lastOrNull()?.value
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
        "线上签到" in text -> "线上签到"
        "签到" in text && "回馆" !in text && "离" !in text -> "签到"
        "离馆" in text || "暂离" in text -> "临时离馆"
        "回馆" in text || "返回签到" in text -> "回馆签到"
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
            "midleave" -> "$BASE_URL/my/?midleave=1&ri=$reserveId"
            "midreturn" -> "$BASE_URL/my/?midreturn=1&ri=$reserveId"
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
                            "ruguan1" -> "线上签到"
                            "midleave" -> "临时离馆"
                            "midreturn" -> "回馆签到"
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
                        val fullUrl = "$urlPrefix$directId"
                        val label = when {
                            "cancel" in urlPrefix -> "取消预约"
                            "firstruguan" in urlPrefix -> "线上签到"
                            "midleave" in urlPrefix -> "临时离馆"
                            "midreturn" in urlPrefix -> "回馆签到"
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
                    if (url.startsWith("/") && url.length > 2 && "取消预约" !in found) found["取消预约"] = url
                }
            }
            // 签到相关
            listOf(
                Regex("""['"]([^'"]*(?:checkin|signin|签到)[^'"]*)['"]"""),
                Regex("""['"](/my/checkin[^'"]*)['"]""")
            ).forEach { pattern ->
                pattern.find(code)?.groupValues?.get(1)?.let { url ->
                    if (url.startsWith("/") && url.length > 2 && "签到" !in found) found["签到"] = url
                }
            }
        }
        return found
    }

    /** 执行操作（签到/离馆/回馆/取消） */
    fun executeAction(actionUrl: String): BookResult {
        try {
            val response = login.client.newCall(buildRequest(actionUrl)).execute()
            val html = response.body?.use { it.string() } ?: ""
            val finalUrl = response.request.url.toString()
            Log.d(TAG, "action: url=$actionUrl, finalUrl=$finalUrl, len=${html.length}")

            val doc = Jsoup.parse(html)
            val bodyText = doc.body()?.text() ?: ""
            val msg = doc.select(".alert, .msg, .message, .success, .error").text()
            val success = listOf("成功", "success", "已取消", "取消成功").any { it in bodyText.lowercase() }
                    || "/my/" in finalUrl
            return BookResult(success, msg.ifBlank { if (success) "操作成功" else "操作可能未生效" }, finalUrl)
        } catch (e: Exception) {
            return BookResult(false, "操作失败: ${e.message}")
        }
    }

    /** 推荐座位（邻座空闲度排序） */
    fun recommendSeats(seats: List<SeatInfo>, topN: Int = 5): List<SeatInfo> {
        val available = seats.filter { it.available }
        if (available.size <= topN) return available

        val grouped = seats.groupBy { it.seatId.firstOrNull() ?: ' ' }
        val scores = available.map { seat ->
            val row = grouped[seat.seatId.firstOrNull() ?: ' '] ?: return@map seat to 0
            val idx = row.indexOf(seat)
            var score = 0
            if (idx > 0 && row[idx - 1].available) score += 2
            if (idx < row.lastIndex && row[idx + 1].available) score += 2
            if (idx > 0 && idx < row.lastIndex && row[idx - 1].available && row[idx + 1].available) score += 3
            if (idx == 0 || idx == row.lastIndex) score += 1
            seat to score
        }

        return scores.sortedByDescending { it.second }.take(topN).map { it.first }
    }
}
