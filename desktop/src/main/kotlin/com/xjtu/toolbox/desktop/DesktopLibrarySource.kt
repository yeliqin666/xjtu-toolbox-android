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

/** 把会话内核（`:data` 的 `SiteSession`）包成 `:data` 的 [LibrarySession] 缝。 */
private class SiteBackedLibrarySession(private val site: SiteSession) : LibrarySession {

    override val client: OkHttpClient get() = site.client

    override suspend fun fetch(request: Request): Response = site.executeWithReAuth(request)

    override fun authExpired(siteName: String) = SessionExpiredException(siteName)
}

/** `:core` 的 [LibrarySource] 在桌面端的实现 —— 真取数（`:data` 的 [LibraryApi] + 真会话）。 */
class DesktopLibrarySource(site: SiteSession) : LibrarySource {

    /** 会话缝：`:data` 的 `LibraryApi` 只看得到这一层（`SiteSession` 的包装）。 */
    private val session = SiteBackedLibrarySession(site)

    private val api = LibraryApi(session)

    /** 离开页面时把校区切回去用的作用域。 */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 平面图字节的进程内缓存：键就是学校那张图的文件名（`区域码.jpg` / `区域码-book.jpg` …）。 */
    private val planImageCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray?>()

    /** 桌面端直连图书馆站点：能预约、换座、签到/退座、切校区。 */
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

    /** 二维码查单个座位：桌面端不支持。 */
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
