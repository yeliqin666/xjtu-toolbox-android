package com.xjtu.toolbox.desktop

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

/** 只为离屏渲染证据存在的假数据源（`./gradlew :desktop:renderScreens`）。 */
internal class DemoLibrarySource : LibrarySource {

    override val canBook: Boolean get() = false
    override val hasSeatPlan: Boolean get() = false

    private val areas = linkedMapOf(
        "north2east" to "北楼二层外文库（东）",
        "north2west" to "北楼二层外文库（西）",
        "south2" to "南楼二层大厅",
    )

    private val stats = mapOf(
        "north2east" to AreaStats(available = 9, total = 24),
        "north2west" to AreaStats(available = 2, total = 18),
        "south2" to AreaStats(available = 31, total = 60),
    )

    override suspend fun campus(): LibraryCampus = LibraryCampus.DEFAULT

    override suspend fun switchCampus(campus: LibraryCampus): Boolean = false

    override suspend fun areas(floorCode: String): Map<String, String> = areas

    override suspend fun seats(areaCode: String): SeatResult = SeatResult.Success(
        seats = (1..24).map { i ->
            val id = "${'A'}${i.toString().padStart(2, '0')}"
            // 前 9 个空着，其余占掉 —— 与上面那份统计对得上，屏上的「空/占」颜色才有意义
            SeatInfo(seatId = id, available = i <= 9)
        },
        areaStatsMap = stats,
    )

    override suspend fun seatLayout(areaCode: String): SeatLayout = SeatLayout(emptyList())

    override suspend fun planBase(areaCode: String): ByteArray? = null

    override suspend fun planTiles(areaCode: String): Map<Int, ByteArray> = emptyMap()

    override suspend fun myBooking(): Result<MyBookingInfo?> =
        Result.success(MyBookingInfo("A03", "北楼二层外文库（东）", "已预约", emptyMap()))

    override suspend fun seatAvailability(qr: LibrarySeatQr): LibrarySeatStatus =
        LibrarySeatStatus.NotFound

    override fun areaStats(): Map<String, AreaStats> = stats

    override fun areaNameOf(areaCode: String): String = areas[areaCode] ?: areaCode

    override fun floorOfArea(areaCode: String): String? = "xingqing2floor"

    override fun isForeignArea(areaName: String?): Boolean = false

    override suspend fun warmCampusAreas(campus: LibraryCampus) = Unit

    override suspend fun bookSeat(seatId: String, areaCode: String, autoSwap: Boolean): BookResult =
        BookResult(false, "只读端不预约")

    override suspend fun swapSeat(seatId: String, areaCode: String): BookResult =
        BookResult(false, "只读端不换座")

    override suspend fun action(actionUrl: String): BookResult = BookResult(false, "只读端不执行动作")

    override fun restoreCampus(campus: LibraryCampus, onRestored: () -> Unit) = Unit
}
