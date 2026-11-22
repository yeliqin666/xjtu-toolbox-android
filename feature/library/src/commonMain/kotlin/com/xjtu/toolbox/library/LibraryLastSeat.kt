package com.xjtu.toolbox.library

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import kotlin.math.abs

/** 上次约成的座位（#461）。 */
data class LastSeat(val campusId: String, val areaCode: String, val seatId: String)

/** 推荐时用到的座位状态；暂离按使用中算，免得让人以为是自己暂离。 */
enum class SeatOccupancy(val label: String) {
    FREE("可用"), IN_USE("使用中"), BOOKED("已被预约"), TAKEN("不可用");

    companion object {
        fun of(seat: PlanSeat): SeatOccupancy = when (seat.status) {
            PlanSeat.FREE -> FREE
            PlanSeat.INSIDE, PlanSeat.LEAVE -> IN_USE
            PlanSeat.BOOKED -> BOOKED
            else -> TAKEN
        }

        fun of(seat: SeatInfo): SeatOccupancy = if (seat.available) FREE else TAKEN
    }
}

/** 「还没有预约」下面那条推荐。 */
sealed interface SeatSuggestion {
    val seat: LastSeat

    /** 在别的校区：只报座位，不查也不推荐，切不切校区让用户自己定。 */
    data class OtherCampus(override val seat: LastSeat, val campus: LibraryCampus) : SeatSuggestion
    /** 状态没查到。 */
    data class Unknown(override val seat: LastSeat) : SeatSuggestion
    data class Free(override val seat: LastSeat) : SeatSuggestion
    /** 被占了；[nearby] 是同区域按座位号最近的空座，可能为空。 */
    data class Taken(override val seat: LastSeat, val occupancy: SeatOccupancy, val nearby: List<String>) : SeatSuggestion
}

/** 推荐那句话拆成几段，页面和首页各自排版：[links] 是「文字 → 座位号」。 */
data class SeatHintText(
    val summary: String,
    val lead: String = "",
    val links: List<Pair<String, String>> = emptyList(),
    val tail: String = "",
)

object LibraryLastSeat {
    // 和图书馆页的偏好同一个存储；KEY_CAMPUS 与 LibraryViewModel 里的同名键是同一项
    private const val PREF_NAME = "library_favorites"
    private const val KEY_LAST_SEAT = "seat_last_booked"
    private const val KEY_CAMPUS = "library_campus"
    const val LINK_PREFIX = "library-seat:"

    private val store by lazy { keyValueStore(PREF_NAME) }

    fun save(seat: LastSeat, account: String = AccountContext.safeSuffix()) =
        store.putString(KEY_LAST_SEAT + account, encode(seat))

    fun load(account: String = AccountContext.safeSuffix()): LastSeat? = decode(store.getString(KEY_LAST_SEAT + account))

    /** 图书馆页最近认到的账号校区，不联网。 */
    fun accountCampus(account: String = AccountContext.safeSuffix()): LibraryCampus? =
        LibraryCampus.byId(store.getString(KEY_CAMPUS + account))

    internal fun encode(seat: LastSeat): String = listOf(seat.campusId, seat.areaCode, seat.seatId).joinToString("|")

    internal fun decode(value: String?): LastSeat? {
        val parts = value?.split('|')?.takeIf { it.size == 3 && it.none(String::isBlank) } ?: return null
        return LastSeat(parts[0], parts[1], parts[2])
    }

    /** 首页那几行的链接：点了进图书馆页选中这个座位（不预约）。 */
    fun link(last: LastSeat, seatId: String): String = LINK_PREFIX + encode(last.copy(seatId = seatId))

    fun parseLink(link: String): LibraryFocus.Target? =
        decode(link.removePrefix(LINK_PREFIX).takeIf { link.startsWith(LINK_PREFIX) })
            ?.let { LibraryFocus.Target(it.campusId, it.areaCode, it.seatId, pick = true) }
}

private val SEAT_NUMBER = Regex("""^(.*?)(\d+)$""")

/** 同区域里和 [seatId] 前缀相同、号码最接近的空座，从近到远最多 [limit] 个。 */
internal fun nearbyFreeSeats(seatId: String, states: Map<String, SeatOccupancy>, limit: Int = 3): List<String> {
    val match = SEAT_NUMBER.matchEntire(seatId) ?: return emptyList()
    val prefix = match.groupValues[1]
    val base = match.groupValues[2].toIntOrNull() ?: return emptyList()
    return states.mapNotNull { (id, state) ->
        if (state != SeatOccupancy.FREE || id == seatId) return@mapNotNull null
        val m = SEAT_NUMBER.matchEntire(id)?.takeIf { it.groupValues[1] == prefix } ?: return@mapNotNull null
        m.groupValues[2].toIntOrNull()?.let { id to it }
    }.sortedWith(compareBy({ abs(it.second - base) }, { it.second })).take(limit).map { it.first }
}

/** [campus] 是账号当前校区；[states] 是那个区域的座位状态，没查到给 null。 */
fun suggestSeat(last: LastSeat, campus: LibraryCampus?, states: Map<String, SeatOccupancy>?): SeatSuggestion {
    val lastCampus = LibraryCampus.byId(last.campusId)
    if (campus != null && lastCampus != null && lastCampus != campus) return SeatSuggestion.OtherCampus(last, lastCampus)
    if (campus == null || states.isNullOrEmpty()) return SeatSuggestion.Unknown(last)
    val state = states[last.seatId] ?: SeatOccupancy.TAKEN
    return if (state == SeatOccupancy.FREE) SeatSuggestion.Free(last)
    else SeatSuggestion.Taken(last, state, nearbyFreeSeats(last.seatId, states))
}

fun SeatSuggestion.hintText(): SeatHintText {
    val id = seat.seatId
    return when (this) {
        is SeatSuggestion.OtherCampus -> SeatHintText("上次预约 $id（在${campus.displayName}校区）")
        is SeatSuggestion.Unknown -> SeatHintText("上次预约 $id")
        is SeatSuggestion.Free -> SeatHintText("上次预约 $id，目前可用", links = listOf("点我跳转到 $id" to id))
        is SeatSuggestion.Taken ->
            if (nearby.isEmpty()) SeatHintText("上次预约 $id，目前它附近的座位都不可用", tail = "可以试试换个区域坐？")
            else SeatHintText("上次预约 $id，目前${occupancy.label}", lead = "推荐预约相邻座位 ", links = nearby.map { it to it })
    }
}
