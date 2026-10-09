package com.xjtu.toolbox.card

import android.content.Context
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * `:core` 的 [CampusCardSource] 在 Android 侧的实现 —— 包住原来的 `CampusCardApi`（okhttp 抓 ncard）
 * 与 `CampusCardCache`（SharedPreferences）。
 *
 * **那两份实现一行未改**（`CampusCardApi` 只搬走了模型与纯统计、`CampusCardCache` 只搬走了模型与纯计算），
 * 本类只做两件原本写在 ViewModel 里的事：
 *  1. 把「读缓存 / 写缓存」摆到端口的四个方法上（原来散在 VM 的 init、fetchRange、show、load 里，
 *     写的是同一组 key、同一份快照、同一个账号命名空间）；
 *  2. 阻塞式 okhttp 与 SharedPreferences 调用放进 `Dispatchers.IO`。原调用点在 VM 里包着
 *     `withContext(Dispatchers.IO)`，现在 VM 不再替实现挑调度器（见 [CampusCardSource] 的 KDoc），
 *     所以由本类自己包 —— **同一层、同一个调度器，行为不变**。
 *
 * 桌面小组件**不在这里**：它是宿主能力（`RemoteViews` + `AppWidgetManager`），由屏的
 * `onBalanceChanged` 回调传进去（Android 在 `AppNavHost` 里传 `CampusCardWidgetUpdater.requestUpdate`），
 * 见 [CampusCardViewModel] 的 KDoc。
 */
class AppCampusCardSource(
    site: SiteSession,
    context: Context,
) : CampusCardSource {

    private val appContext = context.applicationContext
    private val api = CampusCardApi(site)

    override suspend fun card(): CardInfo = withContext(Dispatchers.IO) { api.getCardInfo() }

    override suspend fun transactions(
        from: LocalDate,
        to: LocalDate,
        page: Int,
        pageSize: Int,
    ): Pair<Int, List<Transaction>> = withContext(Dispatchers.IO) {
        api.getTransactions(startDate = from, endDate = to, page = page, pageSize = pageSize)
    }

    /**
     * 落盘的那份快照，裁到 [from]..[to]。
     *
     * 与搬迁前同一件事：VM 的 init 拿整份快照自己过滤，`fetchRange` 则要 `rangeStart` 判断
     * 「缓存覆盖没覆盖住这次要的范围」。这里把过滤收进来，`rangeStart`/`rangeEnd`/`savedAt` 仍是
     * 那一份的（**不**改成这次请求的区间：它们是「这份缓存覆盖到哪儿」的元信息）。
     */
    override suspend fun snapshot(
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ): CampusCardSnapshot? = withContext(Dispatchers.IO) {
        CampusCardCache.load(appContext, accountId)?.let { held ->
            held.copy(
                transactions = held.transactions.filter { tx ->
                    val date = tx.time.take(10)
                    date >= from.toString() && date <= to.toString()
                },
            )
        }
    }

    /**
     * 卡面那三个 key —— 与搬迁前逐字一致（原来写在 VM 的 `load()` 里，卡信息回来、流水还在路上那一刻，
     * 注释是「余额先到先显示」）。
     */
    override suspend fun persistCard(card: CardInfo, accountId: String?) = withContext(Dispatchers.IO) {
        CampusCardCache.cardPrefs(appContext, accountId).edit()
            .putFloat("card_balance_cache", card.balance.toFloat())
            .putString("card_name_cache", card.name)
            .putLong("card_cache_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * 全部写回：首页/小组件那组 key（余额 + 卡名 + 时刻 + 今日三餐 + 近 30 天日均）再加快照。
     *
     * 搬迁前这是两段（VM 的 `show(persist = true)` 与紧接着的 `CampusCardCache.save(...)`），
     * 写的 key、用的值、落到的账号命名空间都一模一样；`todaySummaryOf` / `dailySpendRate` 这两个
     * 从 VM 挪进 :core 的纯计算也在这里调用（同一个输入、同一个输出）。
     */
    override suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ) = withContext(Dispatchers.IO) {
        CampusCardCache.cardPrefs(appContext, accountId).edit()
            .putFloat("card_balance_cache", card.balance.toFloat())
            .putString("card_name_cache", card.name)
            .putLong("card_cache_time", System.currentTimeMillis())
            .putTodaySummary(todaySummaryOf(transactions))
            .putDailyRate(dailySpendRate(transactions))
            .apply()
        CampusCardCache.save(appContext, card, transactions, from, to, accountId)
    }
}
