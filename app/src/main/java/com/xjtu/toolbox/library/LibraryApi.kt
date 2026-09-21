package com.xjtu.toolbox.library

import android.util.Log
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.runBlocking
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

class LibraryApi(private val site: SiteSession) {

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

        /**
         * 兴庆的区域码。
         *
         * 以前它叫 VALID_AREA_CODES，是 `filterScount` 的白名单——于是雁塔、创新港的
         * 区域码一律被当成"无效"滤掉，两个校区的区域列表永远是空的（issue #42）。
         * 现在白名单由 [knownAreaCodes] 在运行时从 `/qspace` 学，这份只当兴庆的兜底。
         */
        private val XINGQING_AREA_CODES = AREA_MAP.values.toSet()

        /** 反向映射：areaCode → 区域显示名 */
        val AREA_MAP_REVERSE = AREA_MAP.entries.associate { (name, code) -> code to name }

        /**
         * 判断响应体是不是 JSON，只看首个非空白字符。
         *
         * 不能用 Content-Type 做判据：rg.lib.xjtu.edu.cn 对合法 JSON 也会打
         * `text/html; charset=utf-8` 的头，照着响应头判会把正常数据当成错误页拒掉。
         * 反过来登录跳转页/错误页首字符是 `<`，这里一样能兜住。
         */
        private fun looksLikeJson(body: String): Boolean =
            body.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true

        /**
         * 座位号正则：匹配字母前缀的 (C08, Y003) 和纯数字零开头的 (002, 019)。
         * 东南侧/西南侧的座位是纯数字编号，没有字母前缀。
         */
        /** `showConfirmModal('文案', 'ruguan1', '4953117')` 的动作名与 reserve id。 */
        val CONFIRM_MODAL_REGEX =
            Regex("""showConfirmModal\s*\(\s*['"][^'"]*['"]\s*,\s*['"](\w+)['"]\s*,\s*['"](\d+)['"]\s*\)""")

        val RESERVE_ID_IN_URL = Regex("""[?&]ri=(\d+)""")

        val SEAT_ID_REGEX = Regex("""(?:[A-Z]\d{2,4}|\b\d{3}\b)""")

        /**
         * 「这条预约已经没用了」的状态文本。
         *
         * 状态是从预约页面 `预约状态：X` 里正则抓的**原文**，不是枚举，所以不可能列全
         * "有效"的那一侧；能穷举的只有失效这一侧。判定一律用"不在这个集合里就是活的"。
         *
         * 原先这份集合在 [parseActiveBooking] 和 LibraryScreen 里各硬编码了一模一样的一份，
         * 改一处漏一处。收到这里做唯一来源。
         */
        val INACTIVE_STATUSES = setOf(
            "已取消", "已完成", "已过期", "已失效", "已违约",
            "超时取消", "超时未入馆", "超时", "已离馆",
        )

        /**
         * 需要用户立刻动手、不做就会丢座位的操作。
         *
         * 判据取 [classifyActionLabel] 归一化后的 label 而不是状态原文：label 只有五个固定值，
         * 稳定；状态文本随学校页面措辞变化。「中途离开」「取消预约」「我想换座」是常驻按钮，
         * 不构成催办。
         */
        val URGENT_ACTIONS = setOf("入馆签到", "中途返回")

        /**
         * scount 里混着区域码和一些非区域的键（楼层汇总之类），要挑出真正的区域。
         *
         * @param known 本次已从 `/qspace` 学到的区域码。为空（还没拉到楼层信息）时
         *              退回兴庆那张静态表——否则首屏会连兴庆都滤没。
         */
        fun filterScount(
            raw: Map<String, AreaStats>,
            known: Set<String> = emptySet(),
        ): Map<String, AreaStats> {
            val allow = if (known.isEmpty()) XINGQING_AREA_CODES else known + XINGQING_AREA_CODES
            return raw.filterKeys { it in allow }
        }

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
     * 运行时学到的「区域码 → 中文名」，来源是 `/qspace?floor=…` 的 `sp` 字段。
     *
     * 兴庆那张 [AREA_MAP] 是手抄的，抄不到雁塔和创新港；这份跟着用户翻到哪层就学到哪层，
     * 三个校区一视同仁，学校改名也不用发版。
     */
    private val learnedAreaNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 区域码 → 所在楼层码。`qseat` 之前要先 `qspace` 定位楼层，这张表就是给它用的。 */
    private val learnedAreaFloors = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 已经把哪个校区的所有楼层都学过了。
     *
     * [knownAreaNames] 平时只含用户翻过的那几层，拿它判断"这个区域是不是本校区的"
     * 会把没翻过的楼层误判成外校区。预热过之后才敢下这个结论。
     */
    @Volatile
    private var warmedCampus: LibraryCampus? = null

    /**
     * 把一个校区所有楼层的区域名一次学完。
     *
     * 代价是每校区 2~4 个 JSON 请求，只在切校区后跑一次；顺带让翻楼层时区域标签
     * 立刻就有，不用等那一层的请求回来。
     */
    fun warmCampusAreas(campus: LibraryCampus) {
        if (warmedCampus == campus) return
        campus.floorCodes.forEach { runCatching { getFloorAreas(it) } }
        warmedCampus = campus
        Log.d(TAG, "warmCampusAreas(${campus.displayName}): ${learnedAreaNames.size} areas")
    }

    /**
     * 这个区域名**确定**不属于当前校区吗？
     *
     * 只有预热完成后才会给出 true——没预热就说"不认识"，那是在拿无知当证据。
     * 用于拦下跨校区换座：预约绑在账号的 rplace 上，换到另一个校区的座位服务端
     * 不会照办，只会把请求晾在那儿直到超时。
     */
    fun isForeignArea(areaName: String?): Boolean {
        if (areaName.isNullOrBlank() || warmedCampus == null) return false
        return areaName !in knownAreaNames()
    }

    /** 已知区域码：学到的 + 兴庆静态表。 */
    fun knownAreaCodes(): Set<String> = learnedAreaNames.keys + AREA_MAP.values

    /** 已知区域中文名，用于从预约页面文本里认出「我预约在哪个区」。 */
    fun knownAreaNames(): Set<String> = learnedAreaNames.values.toSet() + AREA_MAP.keys

    fun areaNameOf(areaCode: String): String =
        learnedAreaNames[areaCode] ?: AREA_MAP_REVERSE[areaCode] ?: areaCode

    private fun floorCodeOf(areaCode: String): String? =
        learnedAreaFloors[areaCode] ?: AREA_FLOOR_CODES[areaCode]

    /**
     * 拉一层的区域列表（码 → 中文名），顺带把 scount 更新掉。
     *
     * `qspace` **跟着账号当前校区走**：查别的校区之前必须先 [switchCampus]，
     * 否则拿回来的还是当前校区那几层。
     */
    fun getFloorAreas(floorCode: String): Map<String, String> {
        val (response, body) = executeWithReAuth(
            buildRequest("$BASE_URL/qspace?lang=zh&floor=$floorCode", ajax = true, referer = "$BASE_URL/seat/")
        )
        response.close()
        if (!response.isSuccessful) throw RuntimeException("楼层信息加载失败: HTTP ${response.code}")
        if (!looksLikeJson(body)) {
            Log.e(TAG, "qspace(floor=$floorCode) not JSON: ${body.take(300)}")
            throw RuntimeException("图书馆楼层信息接口返回异常（非 JSON 响应）")
        }
        val json = org.json.JSONObject(body)
        val result = linkedMapOf<String, String>()
        json.optJSONObject("sp")?.let { sp ->
            val keys = sp.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                if (code.isBlank()) continue
                val name = sp.optString(code).takeIf { it.isNotBlank() } ?: continue
                result[code] = name
                learnedAreaNames[code] = name
                learnedAreaFloors[code] = floorCode
            }
        }
        parseAreaStats(json.optJSONObject("scount")).let { stats ->
            cachedAreaStats = filterScount(stats, knownAreaCodes()).ifEmpty { cachedAreaStats }
        }
        Log.d(TAG, "getFloorAreas($floorCode): ${result.size} areas")
        return result
    }

    /**
     * 账号当前所在校区。解析 `/modify` 页 `select#rplace` 的选中项，认不出返回 null。
     */
    fun getCurrentCampus(): LibraryCampus? = try {
        val (response, body) = executeWithReAuth(buildRequest("$BASE_URL/modify"))
        response.close()
        val selected = Jsoup.parse(body).select("select#rplace option[selected]").firstOrNull()?.attr("value")
        LibraryCampus.byId(selected)
    } catch (e: Exception) {
        Log.w(TAG, "getCurrentCampus failed", e)
        null
    }

    /**
     * 切换账号校区。
     *
     * **这会改用户在图书馆系统里的个人资料**（`rplace` 是账号级字段，不是一次查询的参数），
     * 所以只能由用户在校区选择器里主动触发，不要在后台自动切。表单要把邮箱、电话原样回填
     * 再提交，少一个字段服务端会把它清空——切个校区顺手抹掉联系方式，用户是不会想到的。
     */
    fun switchCampus(campus: LibraryCampus): Boolean = try {
        val (formResp, formHtml) = executeWithReAuth(buildRequest("$BASE_URL/modify"))
        formResp.close()
        val doc = Jsoup.parse(formHtml)
        val csrf = doc.select("input[name=csrf_token]").firstOrNull()?.attr("value").orEmpty()
        val email = doc.select("#email").firstOrNull()?.attr("value").orEmpty()
        val tel = doc.select("#tel").firstOrNull()?.attr("value").orEmpty()
        if (csrf.isBlank()) {
            Log.w(TAG, "switchCampus: no csrf_token in /modify")
            false
        } else {
            val form = okhttp3.FormBody.Builder()
                .add("csrf_token", csrf)
                .add("email", email)
                .add("tel", tel)
                .add("rplace", campus.id)
                .add("subit", "确认")
                .build()
            val req = Request.Builder().url("$BASE_URL/modify")
                .header("Referer", "$BASE_URL/modify")
                .post(form)
                .build()
            val resp = runBlocking { site.executeWithReAuth(req) }
            val ok = resp.isSuccessful
            resp.close()
            // 提交完清掉学到的区域：换校区后区域码整套都变了，留着会把上个校区的
            // 区域混进列表。
            if (ok) {
                learnedAreaNames.clear()
                learnedAreaFloors.clear()
                cachedAreaStats = emptyMap()
                warmedCampus = null
            }
            ok
        }
    } catch (e: Exception) {
        Log.w(TAG, "switchCampus failed", e)
        false
    }

    /**
     * 座位接口的通用请求头。
     *
     * 注意这里**没有**设任何超时，走的是 site.client 的默认值（25-30s）。
     * 所以别在一条用户操作里串太多请求——换座那条链路曾经串到 7 个，
     * 撞上服务端不响应时，用户看到的就是等了好几分钟然后"超时"。
     */
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
        val response = runBlocking { site.executeWithReAuth(request) }
        val body = response.body?.string() ?: ""
        if (isRedirectedToLogin(body, response.request.url.toString())) {
            response.close()
            throw com.xjtu.toolbox.auth.AuthExpiredException("图书馆")
        }
        return response to body
    }

    // ── 座位查询 ──

    private fun loadFloorContext(areaCode: String): Map<String, AreaStats> {
        val floorCode = floorCodeOf(areaCode) ?: return emptyMap()
        val qspaceUrl = "$BASE_URL/qspace?lang=zh&floor=$floorCode"
        val (response, body) = executeWithReAuth(
            buildRequest(qspaceUrl, ajax = true, referer = "$BASE_URL/seat/")
        )
        val contentType = response.header("Content-Type")?.lowercase() ?: ""
        response.close()
        if (!response.isSuccessful) {
            throw RuntimeException("楼层信息加载失败: HTTP ${response.code}")
        }
        // 检查是否返回了 HTML 而非 JSON
        if (!looksLikeJson(body)) {
            Log.e(TAG, "qspace did not return JSON. ContentType=$contentType, body preview: ${body.take(500)}")
            throw RuntimeException("图书馆楼层信息接口返回异常（非 JSON 响应）")
        }
        val json = org.json.JSONObject(body)
        val stats = parseAreaStats(json.optJSONObject("scount"))
        cachedAreaStats = filterScount(stats, knownAreaCodes())
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
            val floorCode = floorCodeOf(areaCode)
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
        } catch (e: java.io.IOException) {
            Log.e(TAG, "getSeats network error", e)
            return SeatResult.Error("网络请求失败: ${e.message}")
        } catch (e: Exception) {
            // 解析/响应异常（如 loadFloorContext 抛出的非 JSON），别伪装成网络问题
            Log.e(TAG, "getSeats failed", e)
            return SeatResult.Error(e.message ?: "座位信息加载失败")
        }

        val finalUrl = response.request.url.toString()
        Log.d(TAG, "qseat: code=${response.code}, url=$finalUrl, len=${body.length}")

        if (isRedirectedToLogin(body, finalUrl))
            return SeatResult.AuthError("认证已失效")
        if (body.length < 10)
            return SeatResult.Error("服务器返回异常")

        // 若服务器没返回 JSON（错误页/登录页），说明这次查询没拿到数据
        val contentType = response.header("Content-Type")?.lowercase() ?: ""
        if (!looksLikeJson(body)) {
            Log.e(TAG, "qseat did not return JSON. ContentType=$contentType, body preview: ${body.take(500)}")
            return SeatResult.Error("图书馆服务器返回异常（非 JSON 响应），请稍后重试")
        }

        try {
            val json = org.json.JSONObject(body)

            // 解析 scount（全局区域统计）
            val statsMap = parseAreaStats(json.optJSONObject("scount"))
            cachedAreaStats = filterScount(statsMap, knownAreaCodes()).ifEmpty { cachedAreaStats }
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
        val resp = runBlocking { site.executeWithReAuth(buildRequest("$BASE_URL$path")) }
        val finalUrl = resp.request.url.toString()
        resp.close()
        if (com.xjtu.toolbox.util.WebVpnUtil.isWebVpnUrl(finalUrl)) {
            val cookieUrl = "https://webvpn.xjtu.edu.cn/wengine-vpn/cookie" +
                "?method=get&host=rg.lib.xjtu.edu.cn&scheme=http&path=$path" +
                "&vpn_timestamp=${System.currentTimeMillis()}"
            site.client.newCall(buildRequest(cookieUrl)).execute().use { it.body?.string() }
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
            runCatching {
                executeWithReAuth(
                    buildRequest("$BASE_URL/qseat?sp=$areaCode", ajax = true, referer = "$BASE_URL/updateseat/")
                ).first.close()
            }
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
        val alertText = extractAlertText(doc, ".alert, .error, .msg, .message, .warn, .notice, #msg, .tip")
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

    /**
     * 学校页面的提示框是 Bootstrap 风格：`<div class="alert"><button class="close">×</button>正文</div>`。
     * 直接对整个容器 `.text()` 会把关闭按钮上的 "×" 也拼进来，签到成功也会显示成
     * "×入馆签到成功！"——先把关闭按钮摘掉再取文字。
     */
    private fun extractAlertText(doc: org.jsoup.nodes.Document, selector: String): String {
        val elements = doc.select(selector)
        elements.select(".close, [data-dismiss], button").remove()
        return elements.text().trim().trimStart('×', '✕', '✗').trim()
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

                // ① 结构化解析优先。页面模板认得出来（有 well / notwell 卡）时，它的结论
                //    就是最终结论——包括"没有预约"，不再往下试别的 URL，也不再猜文本。
                if (doc.selectFirst("div.well, div.notwell") != null) {
                    val structured = parseBookingCard(doc, html, finalUrl)
                    Log.d(TAG, "getMyBooking: structured -> ${structured?.seatId ?: "无预约"}")
                    return structured
                }

                // ② 模板不认识，退回文本启发式。
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
     * 按 `/my/` 页的 DOM 结构解析当前预约。
     *
     * 之前这里是「全文正则找座位号」，而 [SEAT_ID_REGEX] 是照兴庆的编号写的（`A101` / `002`）。
     * 创新港、雁塔的编号对不上，于是：认不出座位号 → 页面里又没有"暂无预约"字样 →
     * 三个候选 URL 全部落空 → 永远显示"暂无预约"。换座后拿它复核，自然也永远判成
     * "换座未生效"，哪怕座位其实已经换成功了——红叉和"暂无预约"是同一个根因。
     *
     * 改成认模板而不是认数据：模板全校一套，数据每个校区都不同。
     * - 当前预约是 `div.well`，历史记录是 `div.notwell`；没有 well 卡就是真的没有预约；
     * - 座位行是卡内第一个 `<hr>` 的**尾随文本**，固定为 `区域名&nbsp;座位号`；
     * - 状态是 `.cta-button` 里第一个无 class 的 `<h3>`。
     *
     * 结构判据取自 yan-xiaoo/XJTUToolBox 对该页面的实测（`library/seats.py`）。
     */
    private fun parseBookingCard(
        doc: org.jsoup.nodes.Document,
        html: String,
        finalUrl: String,
    ): MyBookingInfo? {
        val card = doc.selectFirst("div.well") ?: return null

        // Jsoup 没有 lxml 的 .tail，尾随文本就是 <hr> 的下一个兄弟文本节点。
        // 必须用 wholeText：text() 会把 &nbsp; 规范化掉，而我们正是靠它切分区域名和座位号。
        val hr = card.selectFirst("hr") ?: return null
        val tail = (hr.nextSibling() as? org.jsoup.nodes.TextNode)?.wholeText?.trim().orEmpty()
        if (tail.isBlank()) return null

        // NBSP 不算 Kotlin 认的空白字符，上面的 trim() 不会把它吃掉。
        val nbsp = '\u00a0'
        val area: String?
        val seatId: String
        if (nbsp in tail) {
            area = tail.substringBeforeLast(nbsp).trim().ifBlank { null }
            seatId = tail.substringAfterLast(nbsp).trim()
        } else {
            // 结构对了但分隔符不是 NBSP（模板微调过）。别把整行当座位号，
            // 退一步按空白切最后一段。
            area = tail.substringBeforeLast(' ').trim().ifBlank { null }
            seatId = tail.substringAfterLast(' ').trim()
        }
        if (seatId.isBlank()) return null

        val status = card.selectFirst("div.cta-button h3:not([class])")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }

        val actions = parseActionsFromHtml(doc, html)
            .ifEmpty { actionsFromConfirmModal(html, finalUrl) }
        Log.d(TAG, "parseBookingCard: seat=$seatId area=$area status=$status actions=${actions.keys}")
        return MyBookingInfo(seatId, area, status, actions)
    }

    /**
     * 从整页 HTML 里直接扫 `showConfirmModal('确认您已到馆?', 'ruguan1', '4953117')`。
     *
     * [parseActionsFromHtml] 按控件文案认按钮，文案一改就抓瞎；这里认的是 JS 调用本身，
     * 只依赖 action 名和 reserve id 两个稳定量。页面按当前状态只渲染可执行的按钮，
     * 所以出现了哪个就给哪个，不要替它补全。
     */
    private fun actionsFromConfirmModal(html: String, finalUrl: String): MutableMap<String, String> {
        val normalized = org.jsoup.parser.Parser.unescapeEntities(html, false)
        val out = mutableMapOf<String, String>()
        CONFIRM_MODAL_REGEX.findAll(normalized).forEach { m ->
            val action = m.groupValues[1]
            val reserveId = m.groupValues[2]
            val label = when (action) {
                "cancel" -> "取消预约"
                "ruguan1" -> "入馆签到"
                "leave", "midleave" -> "中途离开"
                "return", "midreturn" -> "中途返回"
                else -> null
            } ?: return@forEach
            buildActionUrl(action, reserveId)?.let { out[label] = it }
        }
        if (out.isEmpty()) {
            // 页面没内联 JS（有的模板把 ri 放在地址上），退一步从 URL 里取。
            RESERVE_ID_IN_URL.find(finalUrl)?.groupValues?.get(1)?.let { ri ->
                buildActionUrl("cancel", ri)?.let { out["取消预约"] = it }
            }
        }
        return out
    }

    /**
     * 从预约页面解析【活跃】预约。
     * 页面可能包含多条预约记录（含已取消的），只返回第一条活跃预约。
     */
    private fun parseActiveBooking(
        doc: org.jsoup.nodes.Document, bodyText: String,
        html: String, finalUrl: String
    ): MyBookingInfo? {
        // 按"预约状态"分割文本，找到活跃预约的文本块
        val statusRegex = Regex("""预约状态[:：]\s*(\S+)""")
        val statusMatches = statusRegex.findAll(bodyText).toList()

        if (statusMatches.isEmpty()) {
            // 无明确状态标记，回退：找第一个座位号
            val seatId = SEAT_ID_REGEX.find(bodyText)?.value ?: return null
            val area = knownAreaNames().firstOrNull { it in bodyText }
            val actionUrls = parseActionsFromHtml(doc, html)
            Log.d(TAG, "getMyBooking (no status): seatId=$seatId, area=$area")
            return MyBookingInfo(seatId, area, null, actionUrls)
        }

        var blockStart = 0
        for (statusMatch in statusMatches) {
            val status = statusMatch.groupValues[1]
            val blockEnd = statusMatch.range.last + 1
            val blockText = bodyText.substring(blockStart, blockEnd)

            if (status in INACTIVE_STATUSES) {
                blockStart = blockEnd
                continue
            }

            // 找到活跃预约！提取该文本块内的信息
            val seatId = SEAT_ID_REGEX.findAll(blockText).lastOrNull()?.value
            if (seatId == null) {
                blockStart = blockEnd
                continue
            }

            val area = knownAreaNames().firstOrNull { it in blockText }
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
            Log.d(TAG, "action: url=$actionUrl finalUrl=$finalUrl len=${html.length}")

            val doc = Jsoup.parse(html)
            val bodyText = doc.body()?.text() ?: ""
            val msg = extractAlertText(doc, ".alert, .msg, .message, .success, .error")

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
}
