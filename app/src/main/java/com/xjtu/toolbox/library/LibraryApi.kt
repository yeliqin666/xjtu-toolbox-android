package com.xjtu.toolbox.library

import com.xjtu.toolbox.util.redactBody
import com.xjtu.toolbox.util.redactUrl
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
        private const val BASE_URL = LibraryPages.BASE_URL
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

        private fun isJpeg(b: ByteArray) = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        private fun isPng(b: ByteArray) = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()

        /**
         * 离开页面时把校区切回去用的作用域。页面的协程作用域那时已经取消了，
         * 切回原校区这一枪必须打完，否则用户看一眼别的校区，账号资料就一直停在那儿。
         */
        internal val restoreScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

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

    /** 区域所在的楼层码。要先 [warmCampusAreas] 或翻过那一层，否则只认得兴庆的静态表。 */
    fun floorOfArea(areaCode: String): String? = floorCodeOf(areaCode)

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
            Log.e(TAG, "qspace(floor=$floorCode) not JSON: ${body.redactBody(300)}")
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
            Log.e(TAG, "qspace did not return JSON. ContentType=$contentType, body preview: ${body.redactBody(500)}")
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
        Log.d(TAG, "qseat: code=${response.code}, url=${finalUrl.redactUrl()}, len=${body.length}")

        if (isRedirectedToLogin(body, finalUrl))
            return SeatResult.AuthError("认证已失效")
        if (body.length < 10)
            return SeatResult.Error("服务器返回异常")

        // 若服务器没返回 JSON（错误页/登录页），说明这次查询没拿到数据
        val contentType = response.header("Content-Type")?.lowercase() ?: ""
        if (!looksLikeJson(body)) {
            Log.e(TAG, "qseat did not return JSON. ContentType=$contentType, body preview: ${body.redactBody(500)}")
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

    // ── 平面图 ──

    /**
     * 区域平面图上每个座位的位置和实时状态（`/qseatuist`，学校网页版 `/seatui` 用的同一份）。
     * 和 [getSeats] 一样要先 `qspace` 把楼层存进会话。
     */
    fun getSeatLayout(areaCode: String): SeatLayout {
        loadFloorContext(areaCode)
        val referer = floorCodeOf(areaCode)?.let { "$BASE_URL/qspace?lang=zh&floor=$it" } ?: "$BASE_URL/seat/"
        val (response, body) = executeWithReAuth(
            buildRequest("$BASE_URL/qseatuist?sp=$areaCode", ajax = true, referer = referer)
        )
        response.close()
        if (!response.isSuccessful) throw RuntimeException("平面图数据加载失败: HTTP ${response.code}")
        if (!looksLikeJson(body)) {
            Log.e(TAG, "qseatuist not JSON: ${body.redactBody(300)}")
            throw RuntimeException("图书馆平面图接口返回异常（非 JSON 响应）")
        }
        return LibraryPages.parseSeatLayout(body)
    }

    /**
     * 平面图图片原始字节（`/static/images/ui10/<name>`）。不存在或不是图片返回 null。
     * 不走 [executeWithReAuth]：那条路会把响应体按字符串读掉。
     */
    fun getPlanImage(name: String): ByteArray? = try {
        val req = buildRequest("$BASE_URL/static/images/ui10/$name", referer = "$BASE_URL/seatui/")
        runBlocking { site.executeWithReAuth(req) }.use { resp ->
            // 认文件头而不是 Content-Type：经 WebVPN 转发时类型头不一定还在；
            // 登录页、404 页是 HTML，头两个字节对不上 JPEG / PNG。
            val bytes = if (resp.isSuccessful) resp.body?.bytes() else null
            bytes?.takeIf { it.size > 4 && (isJpeg(it) || isPng(it)) }
        }
    } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "plan image $name failed: ${e.message}")
        null
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
            // 严格相等：以前用 contains 双向比，A01 和 A011 会被当成同一个座位，换座没成也报成功。
            if (booked != null && LibraryPages.sameSeat(booked, seatId)) BookResult(true, "✓ 已换座到 ${booked}！", finalUrl)
            else BookResult(false, "换座未生效${booked?.let { "（当前仍为 $it）" } ?: ""}：${parseBookingFailure(html)}", finalUrl)
        } catch (e: Exception) {
            if (e is com.xjtu.toolbox.auth.AuthExpiredException)
                BookResult(false, "登录状态已失效，请退出图书馆页面后重新进入")
            else BookResult(false, "换座请求失败: ${e.message}")
        }
    }

    private fun parseBookingFailure(html: String): String =
        LibraryPages.bookingFailureReason(html)
            ?: if (isRedirectedToLogin(html, "")) "登录状态已失效" else "预约失败（未知原因）"

    // ── 我的预约 ──

    /** 当前预约；查不到（网络错、页面认不出）也返回 null。要区分这两种情况用 [fetchMyBooking]。 */
    fun getMyBooking(): MyBookingInfo? = fetchMyBooking().getOrNull()

    /**
     * 当前预约。成功且值为 null 表示页面明确说了「没有预约」；
     * 所有候选地址都没给出能认的页面时返回失败——操作后复核不能把「没查到」当成「已取消」。
     */
    fun fetchMyBooking(): Result<MyBookingInfo?> {
        // HAR 2026-06-13 shows /my/ is the canonical booking page.
        val candidateUrls = listOf("$BASE_URL/my/", "$BASE_URL/seat/my/", "$BASE_URL/seat/my")
        var lastError: Throwable? = null
        for (url in candidateUrls) {
            try {
                val (response, html) = executeWithReAuth(buildRequest(url, referer = "$BASE_URL/seat/"))
                val finalUrl = response.request.url.toString()
                response.close()
                if (isRedirectedToLogin(html, finalUrl)) continue
                when (val page = LibraryPages.parseMyPage(html, finalUrl, knownAreaNames())) {
                    is LibraryPages.MyPage.Booked -> {
                        Log.d(TAG, "getMyBooking: ${page.info.seatId} ${page.info.statusText} ${page.info.actionUrls.keys}")
                        return Result.success(page.info)
                    }
                    LibraryPages.MyPage.NoBooking -> return Result.success(null)
                    LibraryPages.MyPage.Unrecognized -> continue
                }
            } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
                return Result.failure(e)
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "getMyBooking: error trying $url", e)
            }
        }
        Log.d(TAG, "getMyBooking: no recognizable page across all candidate URLs")
        return Result.failure(lastError ?: IllegalStateException("预约页面认不出来"))
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
            Log.d(TAG, "action: url=${actionUrl.redactUrl()} finalUrl=${finalUrl.redactUrl()} len=${html.length}")

            val doc = Jsoup.parse(html)
            val bodyText = doc.body()?.text() ?: ""
            val msg = LibraryPages.extractAlertText(doc, ".alert, .msg, .message, .success, .error")

            // 取消预约：以「我的预约是否已消失」为准判定，最可靠
            if ("cancel" in actionUrl.lowercase()) {
                val after = fetchMyBooking()
                return when (LibraryPages.actionVerdict("取消预约", after.getOrNull(), fetched = after.isSuccess)) {
                    LibraryPages.ActionVerdict.DONE -> BookResult(true, msg.ifBlank { "✓ 已取消预约" }, finalUrl)
                    LibraryPages.ActionVerdict.NOT_DONE ->
                        BookResult(false, "取消未生效，当前仍为 ${after.getOrNull()?.seatId}。$msg", finalUrl)
                    LibraryPages.ActionVerdict.UNKNOWN -> BookResult(true, "已提交取消，状态以下方刷新结果为准", finalUrl)
                }
            }

            // 签到 / 离开 / 返回：以操作后「我的预约」页上还剩哪些按钮为准，不看落地页文案。
            val label = LibraryPages.labelOfActionUrl(normalizedUrl)
            val after = fetchMyBooking()
            val verdict = LibraryPages.actionVerdict(label, after.getOrNull(), fetched = after.isSuccess)
            Log.d(TAG, "action $label -> $verdict (body ${bodyText.redactBody(80)})")
            return when (verdict) {
                LibraryPages.ActionVerdict.DONE -> BookResult(true, msg.ifBlank { "✓ 已完成" }, finalUrl)
                LibraryPages.ActionVerdict.NOT_DONE -> BookResult(
                    false,
                    "未生效，当前状态：${after.getOrNull()?.statusText ?: "无预约"}" + msg.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty(),
                    finalUrl,
                )
                LibraryPages.ActionVerdict.UNKNOWN -> BookResult(true, "已提交，状态以下方刷新结果为准", finalUrl)
            }
        } catch (e: com.xjtu.toolbox.auth.AuthExpiredException) {
            return BookResult(false, "登录状态已失效，请退出图书馆页面后重新进入")
        } catch (e: Exception) {
            return BookResult(false, "操作失败: ${e.message}")
        }
    }
}
