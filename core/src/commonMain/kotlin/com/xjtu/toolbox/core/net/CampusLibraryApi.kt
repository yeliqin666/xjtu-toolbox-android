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

/**
 * 图书馆座位的 **campus-api 版取数**：给 Web 端用（Android 端走 `:app` 的 `AppLibrarySource`）。
 *
 * ## 两种形态（[ApiMode] 的默认值就是旧行为，一字不改）
 *
 * - **campus-api**（默认）：那个模块**刻意不实现任何写操作** —— 不选座、不换座、不退座，**也不切校区**
 *   （切校区改的是账号资料里的 `rplace`，是写操作 ⇒ 它的 `/api/library/campus` 自己在 `note` 里写明
 *   「要查别的校区请在网页上切换后再来」）。所以那几个写方法在这一支**一律抛**，屏据 [canBook]=false
 *   把对应的 UI 整个不画（见 `LibrarySource` 的 KDoc：点了会失败的按钮一个都不画）。抛而不是
 *   「静默返回假成功」：真被调到就是有人画了不该画的按钮，得响。
 * - **serve**（契约 §5.3 / §5.3.1）：`/api/library/action` 是**真**写端点 —— [canBook] 随服务端如实报的
 *   `canBook` 翻 true，[bookSeat] / [swapSeat] / [action] / [switchCampus] 变成对那个端点的真请求，
 *   业务拒绝（座位被占 = 409 这类）原样带回 [BookResult]。
 *
 * 平面图两条路都没有那两张图（campus-api 没有端点；serve 的契约 §5 只有四读一写、没有布局/底图端点）
 * ⇒ [hasSeatPlan] 恒 `false`：屏落到列表视图，[seatLayout] / [planBase] / [planTiles] 一律抛。
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
 * 收藏现在是**共享实现** [com.xjtu.toolbox.library.LibraryFavorites]，不在这个类里了 ——
 * 它落在 `:core` 的 `keyValueStore("library_favorites")` / `favorite_seats`（与本类原来那两个方法
 * **同一个文件、同一个键、同一个值形态**：`localStorage` 里的逗号串）⇒ 浏览器里已有的收藏不丢。
 * 当初把 `localStorage` 写成逗号串正是因为 `KeyValueStore` 没有集合那一档；现在有了
 *（`getStringSet` / `putStringSet`，wasm 的 actual 沿用老格式），同一件事就不该有两份代码。
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
    /** 见 [ApiMode]：默认 campus-api（旧行为一字不改），serve 模式读契约 §5.3 的形状。 */
    private val mode: ApiMode = ApiMode.CAMPUS_API,
) : LibrarySource {

    /** serve 的 `/api/library/campus` 如实报的两个能力开关（拉到响应后才更新）。 */
    private var serveCanBook = false
    private var serveHasSeatPlan = false

    /**
     * 「我的预约」报出来的动作地址 → 动作文案（`actionUrls` 的反表）：`action(url)` 靠它把屏上那条
     * 按钮的地址认成动作码（见类 KDoc —— 地址只认**最近一次**报出来的）。
     */
    private var serveUrlToLabel: Map<String, String> = emptyMap()

    /**
     * [restoreCampus] 要用的进程级作用域 —— 与 `:app` / `:desktop` 的 `restoreScope` 同一条约定
     * （页面作用域在离开那一刻已经取消了，这一枪必须在它之外打完）。
     */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 能不能在屏上做预约 / 换座 / 退座（见 [LibrarySource.canBook]）。
     *
     * serve 的 `/api/library/campus` 报 `canBook:true`，且写端点 `/api/library/action` **已经落地**
     * （契约 §5.3.1，[SERVE_WRITE_ENDPOINTS_READY] = true）⇒ 两件事的**与**就是 `true`：屏上的座位格、
     * 「我的预约」按钮、校区切换全部真的能点。campus-api 那一路没有写能力（[serveCanBook] 也读不到
     * 那个字段）⇒ 仍是 `false`，与搬迁前的行为逐字一致。
     */
    override val canBook: Boolean get() = serveCanBook && SERVE_WRITE_ENDPOINTS_READY

    /**
     * 有没有座位布局与平面图（见 [LibrarySource.hasSeatPlan]）。
     *
     * serve 的 `/api/library/campus` 报 `hasSeatPlan:true`，但契约 §5 的 library 只有
     * `campus` / `areas` / `seats` / `my` 四条 —— **没有**布局/底图端点 ⇒ [seatLayout] /
     * [planBase] / [planTiles] 调不到东西。报 true 会让屏画一个永远空白的平面图，
     * 所以同样报「与」的结果。campus-api 那一路则是真的没有这些端点。
     */
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
    /**
     * serve 模式（契约 §5.3）：`{my:{seatId,area,statusText,actionUrls}|null}`。
     *
     * 比旧路**简单且更准**（三处都是契约明写的）：
     *  - 「没有预约」是 `my:null`（§4：`null` = 上游明确说没有）⇒ `Result.success(null)`，
     *    不再靠 `state == "none"` 那个字符串，也就没有「认不出的 state」可以误判成「没预约」；
     *  - `area` 由服务端直接给（旧路要从 `seatLine` 反推 —— 那是 campus-api 没投影区域名的绕路）；
     *  - `actionUrls` **投影**（label → 上游地址，写端点落地后屏上的按钮要它们），并随手记下反表
     *    供 [action] 把地址认成动作码。
     */
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

    // ─── 收藏**不在这里** ──────────────────────────────────
    //
    // `favorites()` / `toggleFavorite()` 曾经是本类里的两个重写（value 是 `localStorage` 里的
    // 逗号串）。现在它们搬进了共享的 `com.xjtu.toolbox.library.LibraryFavorites`：这是本机偏好、
    // 不是取数，三端就不该各写一遍。落盘位置、键名、值形态逐字未变（见类 KDoc）。


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


    /**
     * 离开本页时把账号校区切回去（[restoreCampus] 的约定见 `LibrarySource`）。
     *
     * campus-api 那一路根本不切校区 ⇒ 没有要恢复的东西（空实现照旧）。serve 那一路切过才需要这一枪 ——
     * 页面作用域那时已取消，用 [restoreScope] 这个进程级作用域打完。
     */
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

    /**
     * 打一发 `/api/library/action`（契约 §5.3.1）：请求体在这里拼（本服务只有两个写端点，不值得引序列化
     * 框架 —— 拼的是合法 JSON，字符串值走 [AppJson.encodeToString] 转义）。结果由 [serveAction] 拆成
     * `ok` / `message`（`data` 那一格这里不用：这个端点的结果只有一句话）：业务拒绝（409 那种）变成
     * `BookResult(false, 服务端的中文短句)`，与 `:app` 的 `LibraryApi.bookSeat` 失败时的形状一致
     * —— 屏上的结果横幅只认 BookResult。
     */
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
        // serve 契约（§4）的信封见 ApiMode / serveData：code == HTTP 状态码、失败文案在 message
        if (mode == ApiMode.SERVE) return client.serveData("图书馆", baseUrl, path, query.toList())
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
        /**
         * serve 契约里有没有图书馆的**写端点**（`/api/library/action`，契约 §5.3.1）。
         *
         * 2026 年落地时翻成 true：写端点真在（约座 / 换座 / 签到 / 中途离开 / 中途返回 / 退座 / 切校区），
         * [canBook] 才敢随服务端的 `canBook:true` 一起亮 —— 那之前报 true 就是在许诺「点了会失败」的按钮。
         */
        const val SERVE_WRITE_ENDPOINTS_READY = true

        /** serve 契约里 library 写端点的路径（§5.3.1）。 */
        const val LIBRARY_ACTION_PATH = "/api/library/action"

        /**
         * 「我的预约」页上那四个动作的文案 → 动作码（契约 §5.3.1；服务端那张「动作码 → 文案」表的反表）。
         *
         * 文案就是 `:data` 的 `LibraryPages.labelOfActionUrl` 认的那一批 —— 别再起第二套名字。
         */
        val SERVE_PAGE_ACTION_CODES = mapOf(
            "入馆签到" to "signin",
            "中途离开" to "leave",
            "中途返回" to "back",
            "取消预约" to "cancel",
        )

        /**
         * serve 契约里有没有**座位布局 / 平面图**端点。
         *
         * 契约 §5 的 library 只有 `campus`/`areas`/`seats`/`my` 四条（见 [hasSeatPlan]）。
         */
        const val SERVE_PLAN_ENDPOINTS_READY = false


        // 收藏的存储名/键名搬去 LibraryFavorites 了（同一个名字，见类 KDoc）。

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
