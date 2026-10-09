package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.auth.SessionExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.library.AreaStats
import com.xjtu.toolbox.library.BookResult
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryCampus
import com.xjtu.toolbox.library.LibraryPages
import com.xjtu.toolbox.library.LibrarySeatQr
import com.xjtu.toolbox.library.LibrarySeatStatus
import com.xjtu.toolbox.library.LibrarySession
import com.xjtu.toolbox.library.LibrarySource
import com.xjtu.toolbox.library.MyBookingInfo
import com.xjtu.toolbox.library.SeatLayout
import com.xjtu.toolbox.library.SeatResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 把**会话内核**（`:data` 的 `SiteSession`）包成 `:data` 的 [LibrarySession] 缝。
 *
 * 只有三行转发，每一行都有理由（见 [LibrarySession] 的 KDoc）：
 * - [client] / [fetch] → `SiteSession.executeWithReAuth` 那套「命中认证失效就 invalidate +
 *   重认证 + 原样重放一次」的语义**一字未改**，只是换了个入口名；
 * - [authExpired] → 抛**共享基类** [SessionExpiredException]（`:app` 那一份抛的是它的子类
 *   `AuthExpiredException`；桌面端没有那批「按具体类分支」的调用点，所以用基类本身，
 *   文案与 `LibrarySession.fetch` 判据逐字相同）。
 *
 * 与 `:data:jvmTest` 的 `SiteBackedLibrarySession` 是同一段代码 —— 那边是测试内的私有类
 * （只能服务那一条测试），这边是交付物的一部分。刻意不搬进 `:data`：`:data` 不认识
 * *哪个*站点，认识 `SiteSession` 就够了（这正是这条缝存在的意义）。
 */
private class SiteBackedLibrarySession(private val site: SiteSession) : LibrarySession {

    override val client: OkHttpClient get() = site.client

    override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)

    override fun authExpired(siteName: String) = SessionExpiredException(siteName)
}

/**
 * `:core` 的 [LibrarySource] 在桌面端的实现 —— **真取数**（`:data` 的 [LibraryApi] + 真会话）。
 *
 * ## 与 `:app` 的 `AppLibrarySource` 的差别（只有两处，都是平台决定的）
 *
 * | | `:app` | `:desktop` |
 * |---|---|---|
 * | 平面图字节 | 先查 `Context.cacheDir` 下的磁盘缓存（`PlanImageDiskCache`） | 只在本进程内存里缓一层（没有 `Context`；落盘缓存属宿主机能族，见 §5.4） |
 * | 扫码查单座 | `LibrarySeatAvailability.fetch`（okhttp + Jsoup 解析 `/qavail/` 页面） | 不支持（见 [seatAvailability]） |
 *
 * 其余每一处都是「把 `LibraryApi` 的同名调用包一层 `Dispatchers.IO`」：阻塞式 okhttp 的调度
 * 由**实现方**负责（见 [LibrarySource] 的 KDoc —— VM 不再替实现挑调度器）。
 *
 * ## 写路径是真的
 *
 * [canBook] = true、[hasSeatPlan] = true：桌面端直连图书馆站点，能预约/换座/签到/退座，
 * 也能切账号校区（那写的是学校侧的 `rplace`，是个**写**操作 —— 屏只在用户主动点校区选择器时发）。
 * 只读端的那些「点了会失败的按钮一个都不画」在这里不适用：写操作真能成。
 */
class DesktopLibrarySource(site: SiteSession) : LibrarySource {

    /** 会话缝：`:data` 的 `LibraryApi` 只看得到这一层（`SiteSession` 的包装）。 */
    private val session = SiteBackedLibrarySession(site)

    private val api = LibraryApi(session)

    /**
     * 离开页面时把校区切回去用的作用域。页面的协程作用域那时**已经取消**了，
     * 而切回原校区这一枪必须打完（否则用户看一眼别的校区，账号资料就一直停在那儿）。
     * 与 `:app` 的 `AppLibrarySource` 同一条口径。
     */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 平面图字节的进程内缓存：键就是学校那张图的文件名（`区域码.jpg` / `区域码-book.jpg` …）。
     *
     * 为什么只做「内存一层」：`:app` 那份走 `Context.cacheDir`（进程退了也还在，因为手机切来切去、
     * 流量按 MB 算）；桌面端没有 `Context`，而正确做法是「按平台数据目录落盘」——那属于宿主机能族
     * （§5.4：文件系统、缓存目录），这一轮不新长一个。内存一层已经去掉「同一屏里来回切区域
     * 就重新下载 5 张图」这个最明显的浪费。
     */
    private val planImageCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray?>()

    /** 桌面端直连图书馆站点：能预约、能换座、能签到/退座、能切校区（`:app` 的写路径一行未改）。 */
    override val canBook: Boolean get() = true

    /** 学校 `/qseatuist` 的座位布局 + `/static/images/ui10/` 的底图/状态图都在，平面图照旧。 */
    override val hasSeatPlan: Boolean get() = true

    override suspend fun campus(): LibraryCampus? =
        withContext(Dispatchers.IO) { api.getCurrentCampus() }

    override suspend fun switchCampus(campus: LibraryCampus): Boolean =
        withContext(Dispatchers.IO) { api.switchCampus(campus) }

    override suspend fun areas(floorCode: String): Map<String, String> =
        withContext(Dispatchers.IO) { api.getFloorAreas(floorCode) }

    override suspend fun seats(areaCode: String): SeatResult =
        withContext(Dispatchers.IO) { api.getSeats(areaCode) }

    override suspend fun seatLayout(areaCode: String): SeatLayout =
        withContext(Dispatchers.IO) { api.getSeatLayout(areaCode) }

    override suspend fun planBase(areaCode: String): ByteArray? = withContext(Dispatchers.IO) {
        planImage(LibraryPages.planImageNames(areaCode).getValue(null))
    }

    override suspend fun planTiles(areaCode: String): Map<Int, ByteArray> = withContext(Dispatchers.IO) {
        // 分成两次取是**照抄**原来 VM 的顺序：底图先到就能画，四张状态图随后补。
        LibraryPages.planImageNames(areaCode).entries
            .filter { it.key != null }
            .mapNotNull { (status, name) -> planImage(name)?.let { status!! to it } }
            .toMap()
    }

    /** 取一张图（命中内存缓存就不再发请求）；拿不到返回 null（屏那边「没底图就没法画」）。 */
    private suspend fun planImage(name: String): ByteArray? {
        planImageCache[name]?.let { return it }
        val bytes = api.getPlanImage(name)
        // 只缓存**取到了**的：null 可能是临时故障（登录页、超时），缓存住会让这张图整场都不出来
        if (bytes != null) planImageCache[name] = bytes
        return bytes
    }

    override suspend fun myBooking(): Result<MyBookingInfo?> =
        withContext(Dispatchers.IO) { api.fetchMyBooking() }

    /**
     * 二维码查单个座位：**桌面端不支持**。
     *
     * 这条路的入口是「扫码」——手机上扫图书馆桌面那个二维码，把 [LibrarySeatQr] 塞进
     * `LibraryFocus` 再打开本屏。桌面没有摄像头，也没有这条入口（`LibraryViewModel.focus`
     * 只在有人塞过 focus 时才会走到这里）。实现它的那一半（`LibrarySeatAvailability`）
     * 还在 `:app`：它用 okhttp + Jsoup 解析 `/qavail/` 页面，而搬它得先把「扫码」这件事
     * 在桌面端定义出来（Stage B/C 的事）。
     *
     * 抛异常而不是返回一个假的 [LibrarySeatStatus]：调用点（`LibraryViewModel.focus`）是
     * `runCatching { source.seatAvailability(qr) }.getOrNull()`，所以「不支持」会被当成
     * 「这一枪没拿到状态」，与网上失败同一种走向 —— 不假装查到了。
     */
    override suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus =
        throw UnsupportedOperationException("桌面端不支持扫二维码查座位（${qr.areaCode}/${qr.seat}）")

    override fun areaStats(): Map<String, AreaStats> = api.cachedAreaStats

    override fun areaNameOf(areaCode: String): String = api.areaNameOf(areaCode)

    override fun floorOfArea(areaCode: String): String? = api.floorOfArea(areaCode)

    override fun isForeignArea(areaName: String?): Boolean = api.isForeignArea(areaName)

    override suspend fun warmCampusAreas(campus: LibraryCampus) =
        withContext(Dispatchers.IO) { api.warmCampusAreas(campus) }

    override suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult =
        withContext(Dispatchers.IO) { api.bookSeat(seatId, areaCode, autoSwap) }

    override suspend fun swapSeat(seatId: String, areaCode: String): BookResult =
        withContext(Dispatchers.IO) { api.swapSeat(seatId, areaCode) }

    override suspend fun action(actionUrl: String): BookResult =
        withContext(Dispatchers.IO) { api.executeAction(actionUrl) }

    override fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit) {
        // 页面作用域那时已经取消（见 LibrarySource.restoreCampus 的 KDoc）⇒ 用本类这个进程级作用域
        restoreScope.launch {
            if (runCatching { api.switchCampus(campus) }.getOrDefault(false)) onRestored()
        }
    }
}
