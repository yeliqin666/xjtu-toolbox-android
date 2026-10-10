package com.xjtu.toolbox.card

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * `:core` 的 [CampusCardSource] 在 Android 与桌面两侧的实现 —— 包住原来的 `CampusCardApi`（okhttp 抓
 * ncard）与本端注入的宿主存储 [CampusCardStore]。
 *
 * 它从 `:app` 搬进 `:data`（桌面端第 9 条真数据路由：桌面要自己登 ncard 取卡面与流水），两大类各动一半：
 *
 * 1. **取数**（`CampusCardApi`）一行未改，只是从 `:app/card/` 挪到同包的 `:data/card/`；
 * 2. **落盘**原来直接调 `CampusCardCache`（SharedPreferences，所以要一个 `Context`），现在改成
 *    构造参数 [store] —— 收的就是原来那三个动作（读快照 / 先写卡面 / 全量写回），
 *    `:app` 传 `appCampusCardStore(context)`（实现仍是那份 `CampusCardCache`，文件与 key 一个没动），
 *    桌面端传 `null`（不缓存 —— 语义与「缓存里什么都没有」一致，屏照旧自己取数）。
 *    `Context` 因此不再跟着取数走，`:data` 里一行 `android.content` 都没有。
 *
 * 两件原本写在 ViewModel 里、由本类接手的事（与搬进 `:core` 时逐字相同）：
 *  1. 把「读缓存 / 写缓存」摆到端口的四个方法上（原来散在 VM 的 init、fetchRange、show、load 里，
 *     写的是同一组 key、同一份快照、同一个账号命名空间）—— 现在它只负责转给 [store]，
 *     以及 [snapshot] 上那次「裁到本次查询区间」；
 *  2. 阻塞式 okhttp 与阻塞式存储调用放进 `Dispatchers.IO`。原调用点在 VM 里包着
 *     `withContext(Dispatchers.IO)`，现在 VM 不再替实现挑调度器（见 [CampusCardSource] 的 KDoc），
 *     所以由本类自己包 —— **同一层、同一个调度器，行为不变**。
 *
 * 桌面小组件**不在这里**：它是宿主能力（`RemoteViews` + `AppWidgetManager`），由屏的
 * `onBalanceChanged` 回调传进去（Android 在 `AppNavHost` 里传 `CampusCardWidgetUpdater.requestUpdate`），
 * 见 [CampusCardViewModel] 的 KDoc。
 */
class AppCampusCardSource(
    site: SiteSession,
    /** 本端的宿主存储；`null` = 不缓存（桌面端）。见 [CampusCardStore]。 */
    private val store: CampusCardStore? = null,
) : CampusCardSource {

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
     *
     * 读盘那一步（[CampusCardStore.load]）与这段裁剪是两件事：存储只回答「盘上那份是什么」，
     * 而「屏这次要哪一段」是端口语义 —— 所以裁剪留在这里，与搬进 `:core` 时逐字相同。
     */
    override suspend fun snapshot(
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ): CampusCardSnapshot? {
        val cache = store ?: return null
        val held = withContext(Dispatchers.IO) { cache.load(accountId) } ?: return null
        return held.copy(
            transactions = held.transactions.filter { tx ->
                val date = tx.time.take(10)
                date >= from.toString() && date <= to.toString()
            },
        )
    }

    /**
     * 卡面那三个 key —— 与搬迁前逐字一致（原来写在 VM 的 `load()` 里，卡信息回来、流水还在路上那一刻，
     * 注释是「余额先到先显示」）。
     */
    override suspend fun persistCard(card: CardInfo, accountId: String?) {
        val cache = store ?: return
        withContext(Dispatchers.IO) { cache.persistCard(card, accountId) }
    }

    /**
     * 全部写回：首页/小组件那组 key（余额 + 卡名 + 时刻 + 今日三餐 + 近 30 天日均）再加快照。
     *
     * 搬迁前这是两段（VM 的 `show(persist = true)` 与紧接着的 `CampusCardCache.save(...)`），
     * 写的 key、用的值、落到的账号命名空间都一模一样（两次都在 [CampusCardStore.persist] 里，
     * `:app` 那份适配器与搬迁前的 `CampusCardCache` 逐字相同）。
     */
    override suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String?,
    ) {
        val cache = store ?: return
        withContext(Dispatchers.IO) { cache.persist(card, transactions, from, to, accountId) }
    }
}
