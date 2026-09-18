package com.xjtu.toolbox.card

import android.content.Context
import com.google.gson.Gson
import com.xjtu.toolbox.account.AccountContext
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

/** 写入 [CampusCardCache.cardPrefs] 里首页卡片与校园卡小组件读取的那组 key。 */
fun android.content.SharedPreferences.Editor.putTodaySummary(s: TodaySpendSummary): android.content.SharedPreferences.Editor =
    putFloat("card_today_spend_cache", s.total.toFloat())
        .putFloat("card_today_breakfast_cache", s.breakfast.toFloat())
        .putFloat("card_today_lunch_cache", s.lunch.toFloat())
        .putFloat("card_today_dinner_cache", s.dinner.toFloat())
        // 以前还写一份最近 5 条流水的 JSON，全仓没有任何地方读，顺手清掉旧值
        .remove("card_recent_tx_cache")

data class CampusCardSnapshot(
    val cardInfo: CardInfo,
    val transactions: List<Transaction>,
    val rangeStart: String,
    val rangeEnd: String,
    val savedAt: Long
)

object CampusCardCache {
    private const val PREFS_PREFIX = "campus_card_data_cache"
    private const val KEY = "snapshot"
    private val gson = Gson()

    private fun prefsName(accountId: String? = AccountContext.activeAccountId): String =
        PREFS_PREFIX + AccountContext.suffixFor(accountId)

    fun load(context: Context): CampusCardSnapshot? {
        val raw = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching {
            gson.fromJson(raw, CampusCardSnapshot::class.java)?.let { snapshot ->
                // cardInfo 本身也可能因缺字段被 Gson 置空；整份快照没有卡信息就没意义，当无缓存处理。
                val cardInfo = (snapshot.cardInfo as CardInfo?)?.sanitized() ?: return@let null
                snapshot.copy(
                    cardInfo = cardInfo,
                    transactions = (snapshot.transactions as List<Transaction>?)
                        ?.map { it.sanitized() }
                        ?: emptyList(),
                )
            }
        }.getOrNull()
    }

    fun save(
        context: Context,
        cardInfo: CardInfo,
        transactions: List<Transaction>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate
    ) {
        val snapshot = CampusCardSnapshot(
            cardInfo = cardInfo,
            transactions = transactions,
            rangeStart = rangeStart.toString(),
            rangeEnd = rangeEnd.toString(),
            savedAt = System.currentTimeMillis()
        )
        context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, gson.toJson(snapshot))
            .apply()
    }

    /** 删除指定账号（默认当前账号）的校园卡缓存。删除账号时传被删的那个。 */
    fun clear(context: Context, accountId: String? = AccountContext.activeAccountId) {
        context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 当前账号命名空间下的校园卡余额/流水缓存 SharedPreferences。 */
    fun cardPrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences("campus_card" + AccountContext.safeSuffix(), Context.MODE_PRIVATE)
    }
}
