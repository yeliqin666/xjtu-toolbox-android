package com.xjtu.toolbox.card

import com.xjtu.toolbox.account.AccountContext
import kotlinx.datetime.LocalDate

/** 校园卡的宿主存储缝 —— 把「写到哪里」从取数实现里切出来。 */
interface CampusCardStore {

    /** 上次落盘的那份快照，不裁（覆盖的区间与落盘时刻见 [CampusCardSnapshot.rangeStart] / `rangeEnd` / `savedAt`）；没有缓存返回 null。 */
    suspend fun load(accountId: String? = AccountContext.activeAccountId): CampusCardSnapshot?

    /** 只写「首页卡片与桌面小组件读的那组 key」（余额 / 卡名 / 落盘时刻）。 */
    suspend fun persistCard(
        card: CardInfo,
        accountId: String? = AccountContext.activeAccountId,
    )

    /** 先写那组 key（含今日三餐与近 30 天日均），再写「下次进屏要的那份快照」，覆盖 [from]..[to]。 */
    suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    )
}
