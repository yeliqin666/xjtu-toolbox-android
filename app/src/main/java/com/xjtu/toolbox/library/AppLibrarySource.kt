package com.xjtu.toolbox.library

import android.content.Context
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `:core` 的 [LibrarySource] 在 Android 侧的实现。 */
class AppLibrarySource(
    site: SiteSession,
    private val context: Context,
) : LibrarySource {

    /** 会话缝：`:data` 的 `LibraryApi` 只看得到这一层（`:app` 侧的 `SiteSession` 包装）。 */
    private val session = AppLibrarySession(site)
    private val api = LibraryApi(session)

    /** 离开页面时把校区切回去用的作用域。 */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Android 直连图书馆站点：能预约、换座、签到/退座、切校区。 */
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
        PlanImageDiskCache.get(context, LibraryPages.planImageNames(areaCode).getValue(null)) { api.getPlanImage(it) }
    }

    override suspend fun planTiles(areaCode: String): Map<Int, ByteArray> = withContext(Dispatchers.IO) {
        LibraryPages.planImageNames(areaCode).entries.filter { it.key != null }.mapNotNull { (status, name) ->
            PlanImageDiskCache.get(context, name) { api.getPlanImage(it) }?.let { status!! to it }
        }.toMap()
    }

    override suspend fun myBooking(): Result<MyBookingInfo?> =
        withContext(Dispatchers.IO) { api.fetchMyBooking() }

    override suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus =
        withContext(Dispatchers.IO) { LibrarySeatAvailability.fetch(session.client, qr) }

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
