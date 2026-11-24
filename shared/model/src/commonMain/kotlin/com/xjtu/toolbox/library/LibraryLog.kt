package com.xjtu.toolbox.library

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.synchronizedBlock
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** 「我的预约」页上的一张预约卡（当前那张或历史卡）：哪天、哪个馆、哪个区域的哪个座、什么结果。 */
@Serializable
data class LibraryCard(
    /** 预约日，yyyy-MM-dd。 */
    val date: String,
    val place: String = "",
    val area: String = "",
    val seat: String = "",
    val status: String = "",
) {
    val key get() = "$date|$area|$seat"
}

/** 本机看到的一次状态变化：何时、哪个座、变成了什么（[ENDED] = 预约从页面上消失）。 */
@Serializable
data class LibraryMark(
    val at: Long,
    val area: String = "",
    val seat: String = "",
    val status: String = "",
    /** 这张预约是在座位前扫码约的。 */
    val desk: Boolean = false,
)

/**
 * 图书馆预约的本机记录，给学期总结用，不上传、按账号分开存。
 *
 * 只搭现成的便车，不额外请求：App 本来就会拉「我的预约」页（首页后台刷新、图书馆页、通知栏操作），
 * 每拉一次把页面上的预约卡合并进来、当前预约的状态变了就记一笔。学校页面只列最近的几张历史卡，
 * 所以要平时一点点攒，学期末拉一次是攒不全的。内容没变就不写盘。
 */
object LibraryLog {
    const val ENDED = "结束"
    private const val KEEP_MS = 400L * 24 * 3600 * 1000
    private const val MAX_CARDS = 800
    private const val MAX_MARKS = 4000
    private val lock = Any()

    /** 各账号上一次写进去的预约卡，内容一样就不再读写盘。 */
    private val lastCards = HashMap<String, List<LibraryCard>>()

    /** 各账号最后一条状态记录：没变就不记。 */
    private val lastMark = HashMap<String, LibraryMark?>()

    private fun store(account: String?) = keyValueStore("library_log${AccountContext.suffixFor(account)}")

    fun recordCards(account: String?, cards: List<LibraryCard>, now: Long = Clock.System.now().toEpochMilliseconds()) {
        if (cards.isEmpty()) return
        val id = account.orEmpty()
        synchronizedBlock(lock) {
            if (lastCards[id] == cards) return@synchronizedBlock
            val oldest = dateBefore(now, KEEP_MS)
            val merged = LinkedHashMap<String, LibraryCard>()
            readCards(account).filter { it.date >= oldest }.forEach { merged[it.key] = it }
            // 同一张卡后来的状态覆盖先前的（已预约 → 使用中 → 已完成）；馆名没认出来时沿用旧的
            cards.forEach { c -> merged[c.key] = c.copy(place = c.place.ifBlank { merged[c.key]?.place.orEmpty() }) }
            val list = merged.values.sortedBy { it.date }.takeLast(MAX_CARDS)
            store(account).putString(KEY_CARDS, AppJson.encodeToString(list))
            lastCards[id] = cards
        }
    }

    /** 当前预约的状态；[seat] 为空表示页面上已经没有预约（记成 [ENDED]，接在上一个座位后面）。 */
    fun recordStatus(
        account: String?,
        area: String?,
        seat: String?,
        status: String?,
        desk: Boolean = false,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        val id = account.orEmpty()
        synchronizedBlock(lock) {
            val last = if (lastMark.containsKey(id)) lastMark[id] else readMarks(account).lastOrNull().also { lastMark[id] = it }
            val mark = if (seat.isNullOrBlank()) {
                // 没有预约：只在上一条还是进行中时补一条「结束」
                if (last == null || last.status == ENDED) return@synchronizedBlock
                LibraryMark(now, last.area, last.seat, ENDED)
            } else {
                LibraryMark(now, area.orEmpty(), seat, status.orEmpty(), desk || (last?.seat == seat && last.desk && last.status != ENDED))
            }
            if (last != null && last.area == mark.area && last.seat == mark.seat && last.status == mark.status && last.desk == mark.desk) {
                return@synchronizedBlock
            }
            val list = (readMarks(account).filter { now - it.at < KEEP_MS } + mark).takeLast(MAX_MARKS)
            store(account).putString(KEY_MARKS, AppJson.encodeToString(list))
            lastMark[id] = mark
        }
    }

    fun readCards(account: String?): List<LibraryCard> =
        runCatching { AppJson.decodeFromString<List<LibraryCard>>(store(account).getString(KEY_CARDS) ?: "[]") }.getOrDefault(emptyList())

    fun readMarks(account: String?): List<LibraryMark> =
        runCatching { AppJson.decodeFromString<List<LibraryMark>>(store(account).getString(KEY_MARKS) ?: "[]") }.getOrDefault(emptyList())

    private fun dateBefore(now: Long, ms: Long): String =
        kotlin.time.Instant.fromEpochMilliseconds(now - ms).toString().take(10)

    private const val KEY_CARDS = "cards"
    private const val KEY_MARKS = "marks"
}
