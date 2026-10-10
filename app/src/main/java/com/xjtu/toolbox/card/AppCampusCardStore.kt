package com.xjtu.toolbox.card

import android.content.Context
import kotlinx.datetime.LocalDate

/**
 * `:app` 侧的校园卡**落盘**装配 —— `:core` 的 [CampusCardStore] 在 Android 上的实现。
 *
 * 取数那一半（`CampusCardApi` + `AppCampusCardSource`）已经搬进 `:data`（桌面端第 9 条真数据路由：
 * 桌面自己登 ncard 取卡面与流水），这里只剩**唯一离不开 `Context` 的那一档**：落盘。
 *
 * 实现仍是原来的 `CampusCardCache`（按账号分文件的 SharedPreferences）：两份文件
 *（`campus_card_data_cache*` 存快照、`campus_card*` 存首页/小组件读的那组 key）与**每一个 key**
 * 都逐字未动 —— 首页卡片、桌面小组件、`DiningHabit`、`AgentTool` 读的还是同一份。
 * 搬运前这三段代码就在 `AppCampusCardSource` 里，搬家只是把它们挪进这个适配器，
 * 调用它的那一层（`AppCampusCardSource`）一行没变。
 *
 * 三个方法与 [CampusCardStore] 上的同名方法一一对应（那边讲了为什么缝是这三个），
 * 线程（阻塞式存储由 `AppCampusCardSource` 包 `Dispatchers.IO`）也在那边讲过了。
 */
fun appCampusCardStore(context: Context): CampusCardStore = CampusCardCacheStore(context.applicationContext)

private class CampusCardCacheStore(private val context: Context) : CampusCardStore {

    override suspend fun load(accountId: String?): CampusCardSnapshot? =
        CampusCardCache.load(context, accountId)

    /** 与搬迁前逐字一致（原来写在 `AppCampusCardSource.persistCard` 里）。 */
    override suspend fun persistCard(card: CardInfo, accountId: String?) {
        CampusCardCache.cardPrefs(context, accountId).edit()
            .putFloat("card_balance_cache", card.balance.toFloat())
            .putString("card_name_cache", card.name)
            .putLong("card_cache_time", System.currentTimeMillis())
            .apply()
    }

    /** 与搬迁前逐字一致（原来写在 `AppCampusCardSource.persist` 里）：先那组 key，再快照。 */
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
