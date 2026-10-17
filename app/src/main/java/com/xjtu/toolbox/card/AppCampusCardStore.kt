package com.xjtu.toolbox.card

import android.content.Context
import kotlinx.datetime.LocalDate

/** `:app` 侧的校园卡落盘装配 —— `:core` 的 [CampusCardStore] 在 Android 上的实现。 */
fun appCampusCardStore(context: Context): CampusCardStore = CampusCardCacheStore(context.applicationContext)

private class CampusCardCacheStore(private val context: Context) : CampusCardStore {

    override suspend fun load(accountId: String?): CampusCardSnapshot? =
        CampusCardCache.load(context, accountId)

    /** 写首页/小组件读的那组 key。 */
    override suspend fun persistCard(card: CardInfo, accountId: String?) {
        CampusCardCache.cardPrefs(context, accountId).edit()
            .putFloat("card_balance_cache", card.balance.toFloat())
            .putString("card_name_cache", card.name)
            .putLong("card_cache_time", System.currentTimeMillis())
            .apply()
    }

    /** 先写那组 key，再写快照。 */
    override suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ) {
        CampusCardCache.cardPrefs(context, accountId).edit()
            .putFloat("card_balance_cache", card.balance.toFloat())
            .putString("card_name_cache", card.name)
            .putLong("card_cache_time", System.currentTimeMillis())
            .putTodaySummary(todaySummaryOf(transactions))
            .putDailyRate(dailySpendRate(transactions))
            .apply()
        CampusCardCache.save(context, card, transactions, from, to, accountId)
    }
}
