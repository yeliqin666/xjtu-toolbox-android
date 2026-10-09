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
import com.xjtu.toolbox.platform.keyValueStore
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
import kotlinx.serialization.json.JsonObject

/**
 * 图书馆座位的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `AppLibrarySource`）。
 *
 * ## 只读（[canBook] = false，[hasSeatPlan] = false）
 *
 * campus-api 的图书馆模块**刻意不实现任何写操作**：不选座、不换座、不退座，**也不切校区**
 *（切校区改的是账号资料里的 `rplace`，是写操作 ⇒ 它的 `/api/library/campus` 自己在 `note` 里写明
 *「要查别的校区请在网页上切换后再来」）。所以 `:core` 端口里的那几个写方法在这里**一律抛**，
 * 而屏据 [canBook] 把对应的 UI 整个不画（见 `LibrarySource` 的 KDoc：点了会失败的按钮一个都不画）。
 * 抛而不是「静默返回假成功」：真被调到就是有人画了不该画的按钮，得响。
 *
 * 平面图（座位布局 + 底图/状态图）campus-api **两个端点都没有** ⇒ [hasSeatPlan] = false，
 * 屏落到列表视图、ViewModel 也不去取那些拿不到的数据（见 `LibrarySource.hasSeatPlan` 的 KDoc）。
 * [seatLayout] / [planBase] / [planTiles] 同样抛。
 *
 * ## 逐字段映射（2026-10-09 核对 campus-api 的 `modules/library.js` 与其真机实测注释）
 *
 * 校区 `GET /api/library/campus` → `data{current{code,name}, campuses[], queryableFloors[], note, parsed}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `LibraryCampus` | `current.code` | ⚠️ 只认 `code`：`east`/`west`/`inno` 就是 `LibraryCampus.id`。`current.name`（「兴庆校区 钱学森图书馆」）**不用** —— 屏上显示的是 `LibraryCampus.displayName` 那三个中文名，两端必须是同一份 |
 * | —— | `campuses[]` | 不用（同上：校区表就是 `LibraryCampus.entries`，而且本端不能切） |
 * | —— | `queryableFloors[]` | ⚠️ **不用**：campus-api 自己写死成兴庆那三层（它的模块头说明别的校区要切校区才拿得到）。楼层表以 `LibraryCampus.floorCodes` 为准，别在这里分叉 |
 * | —— | `parsed` `note` | `parsed=false` 表示 `/modify` 里认不出 selected ⇒ `current.code` 为 null ⇒ 返回 null（与 `:app` 认不出返回 null 同义，不猜一个校区出来） |
 *
 * 区域 `GET /api/library/areas?floor=`（不带 `floor` ⇒ 全校区）→ `data{floor, areaCount, areas[], totals{}, cached}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `areas()` 的 key → value | `areas[].code` → `areas[].name` | 顺序照上游返回的顺序（就是学校给的顺序）。空白码/空白名跳过 —— 与 `:app` 的 `getFloorAreas` 同一个判据 |
 * | `AreaStats.available` / `total` | `areas[].available` / `areas[].total` | ⚠️ 上游 `scount` 里没有这个区域时 campus-api 给 `total: null` ⇒ **不进统计表**（`:app` 的 `parseAreaStats` 要求 `[total, available]` 至少两项，缺的键根本不进 map）⇒ 屏上「统计没到」的区域照常列出 |
 * | —— | `areas[].occupied` / `open` | 不用：`isOpen` 由 `total > 0` 算，与 `AreaStats.isOpen` 同一条判据 |
 * | —— | `totals{}` | ⚠️ **不直接吃**：它和上游 `scount` 一样混着区域与汇总键（`xingqing2floor`、`east` 之类）。统计表只拿 `areas[]` 里的区域码来填 |
 * | `floorOfArea` / `areaNameOf` / `isForeignArea` | `areas[].floor` / `name` | 与 `:app` 的「学到的楼层/区域名」同一份用途（见下） |
 *
 * 座位 `GET /api/library/seats?area=` → `data{area, total, available, seats[{id, available, raw}], cached}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `SeatInfo.seatId` | `seats[].id` | ← 上游座位表的键（`0` = 空闲那条语义 campus-api 已经翻过，见下） |
 * | `SeatInfo.available` | `seats[].available` | campus-api 把上游 `0`（空闲）翻成了 `true`，与 `:app` 的 `status == 0` 同一口径 |
 * | `SeatResult.Success.areaStatsMap` | 本端已学到的区域统计 | `:app` 的 `getSeats` 每次都顺带刷一遍 `scount`；这边由 `areas()` / `warmCampusAreas()` 填（`loadFloor` 刚拉过，同一时刻的新鲜度）⇒ 不再为它多打一枪 |
 * | —— | `seats[].raw` `total` `available` `cached` | 不用（`:app` 只用「可用不可用」，座位数与空闲数屏自己从 `seats` 数） |
 * | ⚠️ **排序** | —— | campus-api 给的是上游对象键的顺序；`:app` 按「首个字母 → 数字部分」排过 ⇒ 这里照 `:app` 排一遍，两端看同一批座位时顺序必须一致，否则「哪个座位在旁边」会对不上 |
 *
 * 我的预约 `GET /api/library/my` → `data{url,state,verified,statusText,seatId,seatLine,actions[],markers{},note,cached}`
 *
 * | :core 的字段 | campus-api 的键 | 为什么 |
 * |---|---|---|
 * | `Result.success(null)` | `state == "none"` | 页面明确说了「没有预约」 |
 * | `Result.success(info)` | `state == "booked"` | ⚠️ 这个分支 campus-api 自己标着 `verified: false`（它没有预约样本） |
 * | `Result.failure` | 其它（`unrecognized`） | **不装作没有预约**：与 `:app` 的 `fetchMyBooking`（所有候选地址都认不出就失败）同一条规矩 |
 * | `MyBookingInfo.seatId` | `seatId` | ← 上游从座位行里正则抠出来的，可能是 null（那就留空） |
 * | `MyBookingInfo.statusText` | `statusText` | ← `div.well` 里那个 `<h3>`（`:app` 是从页面正则抓的原文，同一件事） |
 * | `MyBookingInfo.area` | `seatLine` 反推 | **上游没有「区域名」这个字段**：campus-api 只给整行原文 `seatLine`（已把 `&nbsp;` 归一成空格，形如「北楼四层西南侧 056」）⇒ 用 `seatId` 从行尾抠掉，剩下的是区域名；抠不出来（行尾不是座位号）就**留空**，屏上不画那一段。不编一个「区域 1」出来 |
 * | `MyBookingInfo.actionUrls` | —— | ⚠️ **留空**：上游 `actions[]` 只有按钮的**文案**（「入馆签到」这种），既没有地址、也没有 `ri` 号，而 `:app` 是拿 `ri` 现拼地址的（`/my/?firstruguan=1&ri=…`）⇒ 地址拼不出来。本端也不能执行这些动作（[canBook] = false）⇒ 屏上那几个按钮一个都不画，两件事正好对齐 |
 * | —— | `url` `markers` `note` `cached` | 不用（`markers` 是 campus-api 的解析旁证，`note` 是它的只读声明/未验证说明） |
 *
 * ## Web 端的收藏
 *
 * 浏览器里存 `localStorage`（`:core` 的 `keyValueStore("library_favorites")`，文件与键名沿用 Android 那个
 * `favorite_seats`，值写成逗号分隔的字符串 —— `localStorage` 没有集合这一档，而 Android 那份是
 * `SharedPreferences.getStringSet`，两边各自记各自的收藏）。
 *
 * ## 关于 `warmCampusAreas`
 *
 * `:app` 是「每层一次 `qspace`」（2~4 个请求）把区域名学一遍；这边**一次请求**就够 ——
 * 不带 `floor` 的 `/api/library/areas` 就是全校区那一份（campus-api 自己缓存 20s），
 * 区域名、所在楼层、空座统计三样一次填齐。传进来的 [campus] 参数这里用不上：
 * campus-api 只能查账号当前校区（与 `qspace` 同一条限制）。
 *
 * @param baseUrl 留空 ⇒ 相对路径即**同源**（Web 端必须这样）。
 */
class CampusLibraryApi(
    private val client: HttpClient,
    private val baseUrl: String = "",
) : LibrarySource {

    /** campus-api 的图书馆模块只读（见类 KDoc）⇒ 预约/换座/动作/切校区都不画。 */
    override val canBook: Boolean get() = false

    /** campus-api 没有座位布局与平面图图片端点（见类 KDoc）⇒ 平面图那一档整个不画。 */
    override val hasSeatPlan: Boolean get() = false

    // ─── 读 ────────────────────────────────────────────────────

    override suspend fun campus(): LibraryCampus? {
        val data = getData("/api/library/campus")
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
            val id = row["id"].safeString().trim()
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

    /** 没有 `/qavail/` 这一档（那是 :app 直连图书馆站点的查询）；扫码那条入口只在 App 上，走不到这里。 */
    override suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus = noEndpoint("/qavail/ 座位状态")

    // ─── 本端已知的旁证（:`app` 那边是 LibraryApi 里那几张「学到的表」）──────

    override fun areaStats(): Map<String, AreaStats> = areaStats.toMap()

    override fun areaNameOf(areaCode: String): String = areaNames[areaCode] ?: areaCode

    override fun floorOfArea(areaCode: String): String? = areaFloors[areaCode]

    /**
     * 与 `:app` 的 `LibraryApi.isForeignArea` 同一个判据：**比的是区域中文名**（那边 `knownAreaNames()` 取的是
     * 学到的那张表的 values），而且**没学过任何区域就不下结论**
     *（没预热过就说"不认识"，那是在拿无知当证据）。
     */
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

    // ─── 收藏（localStorage，见类 KDoc）────────────────────────

    override fun favorites(): Set<String> =
        favoritesStore.getString(KEY_FAVORITES)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()

    override fun toggleFavorite(seatId: String): Set<String> {
        val current = favorites().toMutableSet()
        if (!current.remove(seatId)) current.add(seatId)
        favoritesStore.putString(KEY_FAVORITES, current.joinToString(","))
        return current
    }

    // ─── 写（只读端一律抛，见类 KDoc）──────────────────────────

    override suspend fun switchCampus(campus: LibraryCampus): Boolean = readOnly()

    override suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult = readOnly()

    override suspend fun swapSeat(seatId: String, areaCode: String): BookResult = readOnly()

    override suspend fun action(actionUrl: String): BookResult = readOnly()

    /** 只读端不切校区 ⇒ 没有要恢复的东西（真走到这里说明有人画了不该画的校区切换）。 */
    override fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit) = Unit

    // ─── 内部 ──────────────────────────────────────────────────

    private val favoritesStore by lazy { keyValueStore(PREF_NAME) }

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

    /**
     * `seatLine`（形如「北楼四层西南侧 056」）→ 区域名：座位号在行尾，抠掉剩下的就是区域名。
     * 行尾不是座位号就返回 null —— **不猜**（见类 KDoc）。
     */
    private fun areaFromSeatLine(seatLine: String, seatId: String?): String? {
        if (seatLine.isBlank() || seatId.isNullOrBlank()) return null
        val line = seatLine.trim()
        if (!line.endsWith(seatId)) return null
        return line.removeSuffix(seatId).trim().ifBlank { null }
    }

    /** 拆 `{code,data}` 信封；`code!=0` 按场馆/校园卡那套报法显式失败。 */
    private suspend fun getData(path: String, vararg query: Pair<String, String>): JsonObject {
        val text = client.get("$baseUrl$path") {
            query.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
        val envelope = AppJson.parseToJsonElement(text) as? JsonObject
            ?: error("campus-api 图书馆返回不是 JSON 对象")
        if (envelope["code"]?.let { !it.isNull } == true && envelope["code"].safeString() != "0") {
            // 图书馆会话掉了（campus-api 会自己重走一次 /seat/，仍失败才报错）也走这一支：
            // Web 端没有 CAS 会话可重登，照实报错，不抛 SessionExpiredFailure
            //（那会让屏幕白白退页，与场馆/校园卡/体测同口径）。
            error("campus-api 图书馆失败：${envelope["msg"].safeString().ifBlank { envelope["error"].safeString() }}")
        }
        return envelope["data"] as? JsonObject
            ?: error("campus-api 图书馆返回缺少 data：${text.take(120)}")
    }

    private companion object {
        /** 收藏的键名/存储名沿用 Android 那个（排查时好认，见类 KDoc）。 */
        const val PREF_NAME = "library_favorites"
        const val KEY_FAVORITES = "favorite_seats"

        /**
         * 座位的显示顺序：与 `:app` 的 `getSeats` 同一个比较器（先按首个字母，再按数字部分）。
         * 两端看同一批座位时顺序要一致（见类 KDoc）。
         */
        val SEAT_ORDER = compareBy<SeatInfo>(
            { it.seatId.firstOrNull { c -> c.isLetter() } ?: ' ' },
            { it.seatId.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        )
    }
}
