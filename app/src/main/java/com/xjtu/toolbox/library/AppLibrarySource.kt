package com.xjtu.toolbox.library

import android.content.Context
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `:core` 的 [LibrarySource] 在 Android 侧的实现 —— 包住原来的 `LibraryApi`（okhttp 抓
 * `rg.lib.xjtu.edu.cn`）与那份 `SharedPreferences`（收藏）。
 *
 * **`LibraryApi` 的实现一行未改**（只搬走了模型、两个判据集合与 `guessAreaCode`，见那个文件里的注释）；
 * 本类只做两件搬迁前写在 ViewModel 里的事，两件都原来那个样子：
 *
 *  1. **把每个取数调用包进 `Dispatchers.IO`**：原来的 VM 是 `withContext(Dispatchers.IO) { api.xxx() }`，
 *     现在 VM 不再替实现挑调度器（见 [LibrarySource] 的 KDoc），所以由本类自己包 —— 同一层、同一个调度器；
 *  2. **平面图的字节**：原来 VM 里那两处「先查磁盘缓存、没有再下载」（`PlanImageDiskCache` +
 *     `LibraryPages.planImageNames`）挪到这里 —— 共享侧只该看见「底图字节」「状态图字节」。
 *     分成 [planBase] / [planTiles] 两次取也是**照抄原来 VM 的顺序**：底图先到就能画，四张状态图随后补。
 *
 * 三个例外，都不是「换个调度器跑同一段代码」：
 *  - [areaStats] / [areaNameOf] / [floorOfArea] / [isForeignArea] / [favorites] 读的是
 *    `LibraryApi` 或 `SharedPreferences` 里已经缓存好的那份，非挂起方法直接委派（原来 VM 也是这么读的）；
 *  - [restoreCampus] 用原来的 `LibraryApi.restoreScope`（页面作用域那时已经取消了）；
 *  - [toggleFavorite] 就是原来 VM 里那两行：读 `getStringSet`、翻转、写回同一个键
 *    （`:core` 的 `KeyValueStore` 没有集合这一档，所以这一件留在实现方，见 [LibrarySource] 的 KDoc）。
 */
class AppLibrarySource(
    private val site: SiteSession,
    private val context: Context,
) : LibrarySource {

    private val api = LibraryApi(site)
    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

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
        withContext(Dispatchers.IO) { LibrarySeatAvailability.fetch(site.client, qr) }

    override fun areaStats(): Map<String, AreaStats> = api.cachedAreaStats

    override fun areaNameOf(areaCode: String): String = api.areaNameOf(areaCode)

    override fun floorOfArea(areaCode: String): String? = api.floorOfArea(areaCode)

    override fun isForeignArea(areaName: String?): Boolean = api.isForeignArea(areaName)

    override suspend fun warmCampusAreas(campus: LibraryCampus) =
        withContext(Dispatchers.IO) { api.warmCampusAreas(campus) }

    override fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    override fun toggleFavorite(seatId: String): Set<String> {
        val next = if (seatId in favorites()) favorites() - seatId else favorites() + seatId
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
        return next
    }

    override suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult =
        withContext(Dispatchers.IO) { api.bookSeat(seatId, areaCode, autoSwap) }

    override suspend fun swapSeat(seatId: String, areaCode: String): BookResult =
        withContext(Dispatchers.IO) { api.swapSeat(seatId, areaCode) }

    override suspend fun action(actionUrl: String): BookResult =
        withContext(Dispatchers.IO) { api.executeAction(actionUrl) }

    override fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit) {
        // 页面作用域那时已经取消（见 LibrarySource.restoreCampus 的 KDoc）⇒ 用原来那个页面外的作用域
        LibraryApi.restoreScope.launch {
            if (runCatching { api.switchCampus(campus) }.getOrDefault(false)) onRestored()
        }
    }

    private companion object {
        /** 与搬迁前 VM 里那个 `PREF_NAME` 逐字一致（同一个 SharedPreferences 文件）。 */
        const val PREF_NAME = "library_favorites"

        /** 与搬迁前 VM 里那个 `KEY_FAVORITES` 逐字一致（同一个键、同一个 StringSet 形态 ⇒ 老收藏不丢）。 */
        const val KEY_FAVORITES = "favorite_seats"
    }
}
