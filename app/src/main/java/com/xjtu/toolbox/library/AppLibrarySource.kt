package com.xjtu.toolbox.library

import android.content.Context
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [LibrarySource] 在 Android 侧的实现。
 *
 * ## 搬迁后它还剩什么（以及为什么只剩这些）
 *
 * 取数与解析（`LibraryApi` + `LibraryPages`，okhttp 抓 `rg.lib.xjtu.edu.cn`）已经搬进 `:data`，
 * **实现一行未改**（只把 `SiteSession`/`android.util.Log`/`org.json` 三处换成缝，见那个类的 KDoc）。
 * 本类因此只包住**真正属于 Android 宿主**的三件事：
 *
 *  1. **把每个取数调用包进 `Dispatchers.IO`**：原来的 VM 是 `withContext(Dispatchers.IO) { api.xxx() }`，
 *     现在 VM 不再替实现挑调度器（见 [LibrarySource] 的 KDoc），所以由本类自己包 —— 同一层、同一个调度器；
 *  2. **平面图的字节**：原来 VM 里那两处「先查磁盘缓存、没有再下载」（`PlanImageDiskCache` +
 *     `LibraryPages.planImageNames`）留在这里 —— 缓存写的是 `Context.cacheDir` 下的文件，
 *     共享侧只该看见「底图字节」「状态图字节」。分成 [planBase] / [planTiles] 两次取也是
 *     **照抄原来 VM 的顺序**：底图先到就能画，四张状态图随后补；
 *  3. **[restoreCampus] 那个作用域**：离开页面时把校区切回去这一枪，页面作用域那时已经取消了，
 *     所以它自带一个进程级作用域（原来是 `LibraryApi.restoreScope`，现在是本类的 `restoreScope`）
 *     —— 「不随页面取消」是宿主的编排，不是图书馆数据层的事。
 *
 * ## 收藏不在这里了
 *
 * 座位收藏以前是本类里的一对重写（`SharedPreferences.getStringSet`）。`:core` 的 `KeyValueStore`
 * 现在有集合那一档，于是它搬进了共享的 [LibraryFavorites]：**同一个文件（`library_favorites`）、
 * 同一个键（`favorite_seats`）、同一个值类型（`StringSet`）** ⇒ 老收藏不丢，而代码只剩一份。
 *
 * ## 会话失效
 *
 * [AppLibrarySession] 把 `SiteSession` 包成 `:data` 要的那个缝，并原样给回 `AuthExpiredException`
 * —— `:app` 里按那个类分支的调用点（屁岱的图书馆工具、`AppInboxSource`…）行为不变。
 */
class AppLibrarySource(
    site: SiteSession,
    private val context: Context,
) : LibrarySource {

    /** 会话缝：`:data` 的 `LibraryApi` 只看得到这一层（`:app` 侧的 `SiteSession` 包装）。 */
    private val session = AppLibrarySession(site)
    private val api = LibraryApi(session)

    /**
     * 离开页面时把校区切回去用的作用域。页面的协程作用域那时已经取消了，
     * 切回原校区这一枪必须打完，否则用户看一眼别的校区，账号资料就一直停在那儿。
     */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Android 直连图书馆站点：能预约、能换座、能签到/退座、能切校区（写路径一行未改）。 */
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
