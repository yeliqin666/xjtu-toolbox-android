package com.xjtu.toolbox.library

/** 图书馆座位那一屏的取数 + 落盘 + 写操作端口。 */
interface LibrarySource {

    /** 本端能不能预约/换座/执行座位动作，以及切换账号校区。campus-api 的图书馆模块只读 ⇒ Web 是 false（见接口 KDoc）。 */
    val canBook: Boolean

    /** 本端有没有「座位布局 + 平面图底图」这两个端点。campus-api 没有 ⇒ Web 是 false（见接口 KDoc）。 */
    val hasSeatPlan: Boolean

    // ─── 读（两端都有）─────────────────────────────────────────

    /** 账号当前所在校区（学校页 `select#rplace` 的选中项）。认不出返回 null（原样保留 App 的语义：不猜一个校区出来）。 */
    suspend fun campus(): LibraryCampus?

    /** 切换账号校区。 */
    suspend fun switchCampus(campus: LibraryCampus): Boolean

    /** 拉一层的区域列表：区域码 → 中文名（顺序即学校给的顺序）。 */
    suspend fun areas(floorCode: String): Map<String, String>

    /** 某区域的座位实时状态。 */
    suspend fun seats(areaCode: String): SeatResult

    /** 某区域平面图上每个座位的矩形与状态（学校 `/qseatuist`）。没有布局端点的端抛异常（见 [hasSeatPlan]）。 */
    suspend fun seatLayout(areaCode: String): SeatLayout

    /** 平面图的底图字节（`区域码.jpg`；整层图就是 `楼层码.jpg`），带本端自己的缓存。 */
    suspend fun planBase(areaCode: String): ByteArray?

    /** 平面图的状态贴图字节：状态码 → 该状态的整图（`-book` / `-inside` / `-leave` / `blanket`）。 */
    suspend fun planTiles(areaCode: String): Map<Int, ByteArray>

    /** 我的预约。 */
    suspend fun myBooking(): Result<MyBookingInfo?>

    /** 查单个座位空不空（桌面二维码上那个 `/qavail/` 页），一次请求，比拉整层座位表快得多。 */
    suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus

    // ─── 本端已知的旁证（缓存读，不挂起）─────────────────────────

    /** 区域码 → 空座/总数（最近一次 [areas] / [seats] 拿到的那些）。 */
    fun areaStats(): Map<String, AreaStats>

    /** 区域码 → 中文名，认不出时原样返回区域码。 */
    fun areaNameOf(areaCode: String): String

    /** 区域码 → 所在楼层码，不知道返回 null。 */
    fun floorOfArea(areaCode: String): String?

    /** 这个区域名确定不属于当前校区吗？用来拦下跨校区换座（预约绑在账号的 `rplace` 上，换到另一个校区的座位服务端不会照办，只会把请求晾在那儿直到超时）。 */
    fun isForeignArea(areaName: String?): Boolean

    /** 把一个校区所有楼层的区域名学一遍（[isForeignArea] 与 [areaNameOf] 靠它）。 */
    suspend fun warmCampusAreas(campus: LibraryCampus)

    // 座位收藏不在这个端口里，见 [LibraryFavorites]。


    // ─── 写（[canBook] = false 时抛）──────────────────────────────

    /** 预约座位。 */
    suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult

    /** 换座（`/updateseat/`）。 */
    suspend fun swapSeat(seatId: String, areaCode: String): BookResult

    /** 执行一个座位动作：[actionUrl] 是「我的预约」页上那个按钮的真实地址（签到/中途离开/中途返回/取消都走这一个）。 */
    suspend fun action(actionUrl: String): BookResult

    /** 离开本页时把账号校区切回去（[campus] = 进页面时的那个校区）。 */
    fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit)
}
