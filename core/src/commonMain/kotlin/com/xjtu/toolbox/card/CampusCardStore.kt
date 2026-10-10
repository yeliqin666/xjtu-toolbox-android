package com.xjtu.toolbox.card

import com.xjtu.toolbox.account.AccountContext
import kotlinx.datetime.LocalDate

/**
 * 校园卡的**宿主存储**缝 —— 把「写到哪里」从取数实现里切出来。
 *
 * 为什么要有这条缝：`AppCampusCardSource` 原来收一个 `android.content.Context`，而那个 `Context`
 * **只**喂给 `CampusCardCache`（按账号分文件的 SharedPreferences：一份是「下次进屏要的快照」，
 * 一份是「首页卡片与桌面小组件读的那组 key」）。取数本身（okhttp 抓 ncard 的卡面与流水）一行 Context
 * 都不需要 —— 桌面端要的正是那一半，于是 `AppCampusCardSource` 从 `:app` 搬进 `:data` 时它成了
 * 构造参数。做法与黄页的 `YellowPageCache`、成绩的 `ScoreReportCache` 是同一条。
 *
 * 三个动作与 [CampusCardSource] 上的三个存储调用**逐个对应**（读 / 先写卡面 / 全量写回），
 * 一个不多一个不少：`AppCampusCardSource` 把那三处原样转给这里，所以「行为不变」是可核的
 * —— 比对两边的实现即可，不必追一遍调用链。
 *
 * `snapshot(...)` 的**裁剪**（把整份快照裁到当前查询区间）不在这里：那是端口语义，留在
 * `AppCampusCardSource` 里与搬迁前逐字相同的那个 `copy(transactions = filter { … })`。
 * 这条缝只管「宿主那份存储读出来/写回去」。
 *
 * ## 各端的实现
 *
 * - `:app` 的 `appCampusCardStore(context)` = 原来的 `CampusCardCache`（**一份文件、一组 key 都没动**：
 *   首页卡片、桌面小组件、`DiningHabit`、`AgentTool` 读的还是同一份）；
 * - Web 端（`CampusCardNetApi`）没有账号隔离也没有磁盘，压根不用这条缝；
 * - 桌面端传 `null`（不缓存，语义与「缓存里什么都没有」一致）。
 *
 * ## 线程
 *
 * 实现方是阻塞式存储（SharedPreferences 读写），由 `AppCampusCardSource` 自己包 `Dispatchers.IO`
 * —— 与它包 okhttp 那两枪同一条约定（见 [CampusCardSource] 的 KDoc）。
 *
 * ## 账号命名空间
 *
 * [accountId] 与端口那边逐字同义：调用方在发请求前定下来，结果回来时按**发起时**那个账号落盘。
 */
interface CampusCardStore {

    /**
     * 上次落盘的那份快照，**不裁**（覆盖的区间与落盘时刻见 [CampusCardSnapshot.rangeStart] /
     * `rangeEnd` / `savedAt`）；没有缓存返回 null。
     */
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
