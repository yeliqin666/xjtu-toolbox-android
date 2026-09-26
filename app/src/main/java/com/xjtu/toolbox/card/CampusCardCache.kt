package com.xjtu.toolbox.card

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson
import java.time.LocalDate

/** 今日消费汇总：总支出与早（5–10 点）中（11–14 点）晚（17–21 点）三餐，单位元。 */
data class TodaySpendSummary(
    val total: Double,
    val breakfast: Double,
    val lunch: Double,
    val dinner: Double,
)

/**
 * 从流水里算今天的消费汇总。流水时间形如 `2026-09-18 12:03:45`，支出金额为负。
 *
 * 首页卡片刷新（refreshCampusCardCache）和校园卡页各算一遍、各写一遍同一组缓存 key，
 * 以前是两段一字不差的复制粘贴，改一处漏一处小组件就对不上，现在都走这里。
 */
fun todaySummaryOf(transactions: List<Transaction>, today: LocalDate = LocalDate.now()): TodaySpendSummary {
    val todayStr = today.toString()
    val spends = transactions.filter { it.time.startsWith(todayStr) && it.amount < 0 }
    fun sumInHours(hours: IntRange) = spends.filter { tx ->
        tx.time.substringAfter(" ").substringBefore(":").toIntOrNull()?.let { it in hours } == true
    }.sumOf { -it.amount }
    return TodaySpendSummary(
        total = spends.sumOf { -it.amount },
        breakfast = sumInHours(5..10),
        lunch = sumInHours(11..14),
        dinner = sumInHours(17..21),
    )
}

private val UTILITY_KEYS = listOf("电控", "水控", "能源", "电量", "电费", "水费")

/**
 * 近 30 天的在校日均：支出 ÷ 有消费的天数。
 *
 * 分母只数刷过卡的日子——放假整周不刷卡，那些天摊进来日均会被压低、可用天数被高估，
 * 正好在快没钱的时候给出最乐观的数。水电一次充几十上百，不是「吃法」，不算进来。
 * 有消费的天数不足 7 天时样本太少，返回 null。
 *
 * 首页、概览、分析三处的「约够几天」都从这里来，数字才对得上。
 */
fun dailySpendRate(transactions: List<Transaction>, today: LocalDate = LocalDate.now()): Double? {
    val since = today.minusDays(29).toString()
    val until = today.toString()
    val spends = transactions.filter { tx ->
        val date = tx.time.take(10)
        tx.amount < 0 && date >= since && date <= until &&
            UTILITY_KEYS.none { tx.merchant.contains(it) || tx.description.contains(it) }
    }
    val days = spends.map { it.time.take(10) }.distinct().size
    if (days < 7) return null
    return spends.sumOf { -it.amount } / days
}

/** 余额按日均 [rate] 还能撑几天；算不出来返回 null。 */
fun runwayDays(balance: Double, rate: Double?): Int? =
    if (rate == null || rate <= 0 || balance < 0) null else (balance / rate).toInt()

/** 「照现在的吃法约够 N 天」。三天以内改口催充值。 */
fun runwayText(days: Int): String =
    if (days <= 2) "照现在的吃法撑不过 3 天" else "照现在的吃法约够 $days 天"

/** 写进 [CampusCardCache.cardPrefs]，首页读；算不出来时保留上一次的值。 */
fun android.content.SharedPreferences.Editor.putDailyRate(rate: Double?): android.content.SharedPreferences.Editor =
    if (rate == null) this else putFloat("card_daily_rate_cache", rate.toFloat())

/** 写入 [CampusCardCache.cardPrefs] 里首页卡片与校园卡小组件读取的那组 key。 */
fun android.content.SharedPreferences.Editor.putTodaySummary(s: TodaySpendSummary): android.content.SharedPreferences.Editor =
    putFloat("card_today_spend_cache", s.total.toFloat())
        .putFloat("card_today_breakfast_cache", s.breakfast.toFloat())
        .putFloat("card_today_lunch_cache", s.lunch.toFloat())
        .putFloat("card_today_dinner_cache", s.dinner.toFloat())
        // 以前还写一份最近 5 条流水的 JSON，全仓没有任何地方读，顺手清掉旧值
        .remove("card_recent_tx_cache")

/** 没有卡信息的快照没意义：cardInfo 不给默认值，缺了整份读失败，当无缓存处理。 */
@kotlinx.serialization.Serializable
data class CampusCardSnapshot(
    val cardInfo: CardInfo,
    val transactions: List<Transaction> = emptyList(),
    val rangeStart: String = "",
    val rangeEnd: String = "",
    val savedAt: Long = 0L,
)

object CampusCardCache {
    private const val PREFS_PREFIX = "campus_card_data_cache"
    private const val KEY = "snapshot"

    private fun prefsName(accountId: String? = AccountContext.activeAccountId): String =
        PREFS_PREFIX + AccountContext.suffixFor(accountId)

    fun load(context: Context, accountId: String? = AccountContext.activeAccountId): CampusCardSnapshot? {
        val raw = context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching { AppJson.decodeFromString<CampusCardSnapshot>(raw) }.getOrNull()
    }

    fun save(
        context: Context,
        cardInfo: CardInfo,
        transactions: List<Transaction>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    ) {
        val snapshot = CampusCardSnapshot(
            cardInfo = cardInfo,
            transactions = transactions,
            rangeStart = rangeStart.toString(),
            rangeEnd = rangeEnd.toString(),
            savedAt = System.currentTimeMillis()
        )
        context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, AppJson.encodeToString(snapshot))
            .apply()
    }

    /** 删除指定账号（默认当前账号）的校园卡缓存。删除账号时传被删的那个。 */
    fun clear(context: Context, accountId: String? = AccountContext.activeAccountId) {
        context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 指定账号（默认当前账号）命名空间下的校园卡余额/流水缓存 SharedPreferences。
     * 联网刷新要在发起请求时定下账号传进来，理由见 [AccountContext.suffixFor]。
     */
    fun cardPrefs(
        context: Context,
        accountId: String? = AccountContext.activeAccountId,
    ): android.content.SharedPreferences =
        context.getSharedPreferences("campus_card" + AccountContext.suffixFor(accountId), Context.MODE_PRIVATE)
}
