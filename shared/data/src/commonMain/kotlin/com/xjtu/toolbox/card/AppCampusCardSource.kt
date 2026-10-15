package com.xjtu.toolbox.card

import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** `:core` 的 [CampusCardSource] 在 Android 与桌面两侧的实现。 */
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

    /** 落盘的那份快照，裁到 [from]..[to]。 */
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

    /** 卡面那三个 key：余额先到先显示。 */
    override suspend fun persistCard(card: CardInfo, accountId: String?) {
        val cache = store ?: return
        withContext(Dispatchers.IO) { cache.persistCard(card, accountId) }
    }

    /** 全部写回：首页/小组件那组 key（余额 + 卡名 + 时刻 + 今日三餐 + 近 30 天日均）再加快照。 */
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
