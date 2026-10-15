package com.xjtu.toolbox.core.net

import com.xjtu.toolbox.library.AreaStats
import com.xjtu.toolbox.library.BookResult
import com.xjtu.toolbox.library.LibraryCampus
import com.xjtu.toolbox.library.LibrarySeatQr
import com.xjtu.toolbox.library.LibrarySeatStatus
import com.xjtu.toolbox.library.LibrarySource
import com.xjtu.toolbox.library.MyBookingInfo
import com.xjtu.toolbox.library.SeatInfo
import com.xjtu.toolbox.library.SeatLayout
import com.xjtu.toolbox.library.SeatResult
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeInt
import com.xjtu.toolbox.util.safeString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/** 图书馆座位的 campus-api 版取数：给 Web 端用（Android 端走 `:app` 的 `AppLibrarySource`）。 */
class CampusLibraryApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : LibrarySource {

    /** serve 的 `/api/library/campus` 报的两个能力开关（拉到响应后才更新）。 */
    private var serveCanBook = false
    private var serveHasSeatPlan = false

    /** 「我的预约」报出来的动作地址 → 动作文案（`actionUrls` 的反表）。 */
    private var serveUrlToLabel: Map<String, String> = emptyMap()

    /** [restoreCampus] 要用的进程级作用域。 */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 能不能在屏上做预约 / 换座 / 退座（见 [LibrarySource.canBook]）。 */
    override val canBook: Boolean get() = serveCanBook && SERVE_WRITE_ENDPOINTS_READY

    /** 有没有座位布局与平面图（见 [LibrarySource.hasSeatPlan]）。 */
    override val hasSeatPlan: Boolean get() = serveHasSeatPlan && SERVE_PLAN_ENDPOINTS_READY

    // ─── 读 ────────────────────────────────────────────────────

    override suspend fun campus(): LibraryCampus? {
        val data = getData("/api/library/campus")
        // serve（§5.3）把两个能力开关放在同一个响应里（见 canBook / hasSeatPlan）：拉到就更新。
        // campus-api 不投影这两项 ⇒ safeBoolean 给 false，与旧行为一致。
        serveCanBook = data["canBook"].safeBoolean()
        serveHasSeatPlan = data["hasSeatPlan"].safeBoolean()
        val code = (data["current"] as? JsonObject)?.get("code").safeString().trim()
        return LibraryCampus.byId(code)
    }

    override suspend fun areas(floorCode: String): Map<String, String> {
        val data = getData("/api/library/areas", "floor" to floorCode)
        val result = LinkedHashMap<String, String>()
        data.arr("areas").orEmpty().forEach { element ->
            val row = element as? JsonObject ?: return@forEach
            val code = row["code"].safeString().trim()
            val name = row["name"].safeString().trim()
            // 与 :app 的 getFloorAreas 同一个判据：空白码/空白名跳过
            if (code.isEmpty() || name.isEmpty()) return@forEach
            result[code] = name
            rememberArea(row, floorCode)
        }
        return result
    }

    override suspend fun seats(areaCode: String): SeatResult {
        val data = getData("/api/library/seats", "area" to areaCode)
        val seats = data.arr("seats").orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            // serve（§5.3）的行叫 `seatId`，campus-api 的行叫 `id`（同一件事）
            val id = row[if (mode == ApiMode.SERVE) "seatId" else "id"].safeString().trim()
            if (id.isEmpty()) return@mapNotNull null
            SeatInfo(seatId = id, available = row["available"].safeBoolean())
        }.sortedWith(SEAT_ORDER)
        // 附带的就是本端已经学到的那份区域统计（见类 KDoc：不为它多打一枪）
        return SeatResult.Success(seats, areaStats())
    }

    /** 没有布局端点（见类 KDoc [hasSeatPlan]）；屏与 ViewModel 都不会走到这里。 */
    override suspend fun seatLayout(areaCode: String): SeatLayout = noEndpoint("座位布局")

    /** 没有平面图图片端点（见类 KDoc [hasSeatPlan]）；屏与 ViewModel 都不会走到这里。 */
    override suspend fun planBase(areaCode: String): ByteArray? = noEndpoint("平面图底图")

    /** 同上。 */
    override suspend fun planTiles(areaCode: String): Map<Int, ByteArray> = noEndpoint("平面图状态图")

    override suspend fun myBooking(): Result<MyBookingInfo?> {
        if (mode == ApiMode.SERVE) return serveMyBooking()
        val data = getData("/api/library/my")
        return when (val state = data["state"].safeString().trim()) {
            "none" -> Result.success(null)
            "booked" -> {
                val seatId = blankToNull(data["seatId"].safeString())
                Result.success(
                    MyBookingInfo(
                        seatId = seatId,
                        // 见类 KDoc：区域名要从 seatLine 反推（上游没有这个字段）
                        area = areaFromSeatLine(data["seatLine"].safeString(), seatId),
                        statusText = blankToNull(data["statusText"].safeString()),
                        // 见类 KDoc：上游只给按钮文案、不给地址 ⇒ 留空；本端也不能执行
                        actionUrls = emptyMap(),
                    )
                )
            }
            // 认不出就失败：不装作「没有预约」（与 :app 的 fetchMyBooking 同一条规矩）
            else -> Result.failure(IllegalStateException("图书馆预约页面认不出来（state=$state），不敢当成「没有预约」"))
        }
    }
    /** serve 模式（契约 §5.3）：`{my:{seatId,area,statusText,actionUrls}|null}`。 */
    private suspend fun serveMyBooking(): Result<MyBookingInfo?> {
        val data = getData("/api/library/my")
        val my = data["my"] as? JsonObject ?: return Result.success(null)
        val rawActions = (my["actionUrls"] as? JsonObject)?.mapValues { (_, v) -> v.safeString() }
            ?: emptyMap()
        serveUrlToLabel = rawActions.mapNotNull { (label, url) ->
            url.trim().takeIf { it.isNotEmpty() }?.let { it to label }
        }.toMap()
        return Result.success(
            MyBookingInfo(
                seatId = blankToNull(my["seatId"].safeString()),
                area = blankToNull(my["area"].safeString()),
                statusText = blankToNull(my["statusText"].safeString()),
                actionUrls = rawActions,
            ),
        )
    }

    /** 没有 `/qavail/` 这一档（那是 :app 直连图书馆站点的查询）；扫码那条入口只在 App 上，走不到这里。 */
    override suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus = noEndpoint("/qavail/ 座位状态")

    // ─── 本端已知的旁证（:`app` 那边是 LibraryApi 里那几张「学到的表」）──────

    override fun areaStats(): Map<String, AreaStats> = areaStats.toMap()

    override fun areaNameOf(areaCode: String): String = areaNames[areaCode] ?: areaCode

    override fun floorOfArea(areaCode: String): String? = areaFloors[areaCode]

    /** 与 `:app` 的 `LibraryApi.isForeignArea` 同一个判据；没学过任何区域就不下结论。 */
    override fun isForeignArea(areaName: String?): Boolean {
        if (areaName.isNullOrBlank() || areaNames.isEmpty()) return false
        // ⚠️ 比的是**名字**那一侧：:app 的 knownAreaNames() 取的是 values，不是区域码
        return areaName !in areaNames.values
    }

    override suspend fun warmCampusAreas(campus: LibraryCampus) {
        // 见类 KDoc：一次请求就是全校区那一份（区域名 + 楼层 + 空座统计）
        val data = getData("/api/library/areas")
        data.arr("areas").orEmpty().forEach { element ->
            (element as? JsonObject)?.let { rememberArea(it, null) }
        }
    }



    // ─── 写（只读端一律抛，见类 KDoc）──────────────────────────

    override suspend fun switchCampus(campus: LibraryCampus): Boolean {
        if (mode != ApiMode.SERVE) return readOnly()
        return postAction("switch_campus", "campus" to campus.id).success
    }

    override suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult {
        if (mode != ApiMode.SERVE) return readOnly()
        return postAction("seat", "seatId" to seatId, "area" to areaCode, "autoSwap" to autoSwap.toString())
    }
    override suspend fun swapSeat(seatId: String, areaCode: String): BookResult {
        if (mode != ApiMode.SERVE) return readOnly()
        return postAction("change", "seatId" to seatId, "area" to areaCode)
    }
    override suspend fun action(actionUrl: String): BookResult {
        if (mode != ApiMode.SERVE) return readOnly()
        // 地址 → 标签 → 动作码：地址必须是「我的预约」刚报出来的那一条（反表见 [serveMyBooking]），
        // 标签在这四个里才有人画这个按钮（契约 §5.3.1 的动作表）。
        val label = serveUrlToLabel[actionUrl]
            ?: throw IllegalStateException("这条动作地址不是「我的预约」页报出来的：$actionUrl")
        return postAction(SERVE_PAGE_ACTION_CODES[label]
            ?: throw IllegalStateException("本端不认识这个动作：$label"))
    }


    /** 离开本页时把账号校区切回去（[restoreCampus] 的约定见 `LibrarySource`）。 */
    override fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit) {
        if (mode != ApiMode.SERVE) return
        restoreScope.launch {
            if (runCatching { switchCampus(campus) }.getOrDefault(false)) onRestored()
        }
    }

    // ─── 内部 ──────────────────────────────────────────────────


    /** 区域码 → 中文名（`areas` / `warmCampusAreas` 学到的；与 `:app` 的 `learnedAreaNames` 同一件事）。 */
    private val areaNames = LinkedHashMap<String, String>()

    /** 区域码 → 所在楼层码（同一批请求里学到的）。 */
    private val areaFloors = LinkedHashMap<String, String>()

    /** 区域码 → 空座/总数（`:app` 的 `cachedAreaStats`）。 */
    private val areaStats = LinkedHashMap<String, AreaStats>()

    /** 把一行 `areas[]` 记进「学到的表」。`floorCode` 是行里 `floor` 为空时的兜底（就是请求的那一层）。 */
    private fun rememberArea(row: JsonObject, floorCode: String?) {
        val code = row["code"].safeString().trim()
        if (code.isEmpty()) return
        val name = row["name"].safeString().trim()
        if (name.isNotEmpty()) areaNames[code] = name
        val floor = blankToNull(row["floor"].safeString()) ?: floorCode
        if (floor != null) areaFloors[code] = floor
        // 见类 KDoc：total/available 缺一个就当上游没有这个区域的统计，不进表
        val total = row["total"]
        val available = row["available"]
        if (total.isNull || available.isNull) return
        areaStats[code] = AreaStats(available = available.safeInt(), total = total.safeInt())
    }

    private fun readOnly(): Nothing =
        throw RuntimeException("本端只读：campus-api 不提供图书馆的预约/换座/签到/切校区")

    private fun noEndpoint(what: String): Nothing =
        throw RuntimeException("本端没有$what：campus-api 不提供这个端点（见 LibrarySource.hasSeatPlan）")

    private fun blankToNull(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

    /** 打一发 `/api/library/action`（契约 §5.3.1），请求体手拼。 */
    private suspend fun postAction(action: String, vararg extra: Pair<String, String>): BookResult {
        val body = buildString {
            append("{\"action\":")
            append(AppJson.encodeToString(JsonPrimitive(action)))
            for ((key, value) in extra) {
                append(",\"$key\":")
                append(AppJson.encodeToString(JsonPrimitive(value)))
            }
            append('}')
        }
        val outcome = client.serveAction(baseUrl, LIBRARY_ACTION_PATH, body)
        return BookResult(outcome.ok, outcome.message)
    }

    /** `seatLine`（形如「北楼四层西南侧 056」）→ 区域名：座位号在行尾，抠掉剩下的就是区域名。 */
    private fun areaFromSeatLine(seatLine: String, seatId: String?): String? {
        if (seatLine.isBlank() || seatId.isNullOrBlank()) return null
        val line = seatLine.trim()
        if (!line.endsWith(seatId)) return null
        return line.removeSuffix(seatId).trim().ifBlank { null }
    }

    /** 拆 `{code,data}` 信封；`code!=0` 按场馆/校园卡那套报法显式失败。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("图书馆", baseUrl, path, query.toList())
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 图书馆返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // 会话掉了也照实报错：Web 端没有 CAS 会话可重登，抛 SessionExpiredFailure 只会让屏白白退页。
            error("campus-api 图书馆失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 图书馆返回缺少 data：${text.take(120)}")
    }

    private companion object {
        /** serve 契约里有没有图书馆的写端点（`/api/library/action`，契约 §5.3.1）。 */
        const val SERVE_WRITE_ENDPOINTS_READY = true

        /** serve 契约里 library 写端点的路径（§5.3.1）。 */
        const val LIBRARY_ACTION_PATH = "/api/library/action"

        /** 「我的预约」页上那四个动作的文案 → 动作码（契约 §5.3.1；服务端那张「动作码 → 文案」表的反表）。 */
        val SERVE_PAGE_ACTION_CODES = mapOf(
            "入馆签到" to "signin",
            "中途离开" to "leave",
            "中途返回" to "back",
            "取消预约" to "cancel",
        )

        /** serve 契约里有没有座位布局 / 平面图端点。 */
        const val SERVE_PLAN_ENDPOINTS_READY = false


        // 收藏的存储名/键名搬去 LibraryFavorites 了（同一个名字，见类 KDoc）。

        /** 座位的显示顺序：与 `:app` 的 `getSeats` 同一个比较器（先按首个字母，再按数字部分）。 */
        val SEAT_ORDER = compareBy<SeatInfo>(
            { it.seatId.firstOrNull { c -> c.isLetter() } ?: ' ' },
            { it.seatId.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        )
    }
}
