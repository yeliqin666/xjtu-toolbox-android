package com.xjtu.toolbox.card

import com.xjtu.toolbox.account.AccountContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/**
 * 校园卡的**取数端口**：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。
 *
 * ## 为什么端口要暴露这四件事
 *
 * 这个屏的可观测行为只有三样东西：**卡面（余额/状态）**、**一段时间的流水**、**上次落盘的快照**
 *（首屏秒开靠它）。三样都在这里；第四件 [persist] 是它们的**写回** —— 屏在每次成功取数后要把
 * 「首页/小组件要看的那份摘要」和「下次进屏要看的那份快照」写下去，而「写到哪里」（Android 是
 * 按账号分文件的 SharedPreferences、Web 没有磁盘缓存）正是各端不同的事。
 *
 * 为什么不把读写合成一个「传参就写、不传就读」的方法：读写是两种语义，调用点也完全不同
 *（读在进屏时与增量取数前、写在取数成功后），分开写才看得清谁在什么时候动缓存。
 *
 * ## 各端
 *
 * - `AppCampusCardSource`（`:data`，Android 与桌面两侧共用）= 原来的 `CampusCardApi`（okhttp 抓 ncard，
 *   取数一行未改）+ 构造参数那份宿主存储 `CampusCardStore`（`:app` 传 `appCampusCardStore(context)`
 *   = 原来的 `CampusCardCache`（SharedPreferences），桌面端传 `null`），只是被包进这个端口；
 * - `CampusCardNetApi`（`:core/core/net`，Web 端用）= campus-api 的 `/api/card/balance` 与
 *   `/api/card/transactions`，逐字段映射写在那个类的 KDoc 里。
 *
 * ## IO 调度由实现方自己负责
 *
 * `AppCampusCardSource` 是阻塞式 okhttp + 阻塞式存储，实现里自己 `withContext(Dispatchers.IO)`；
 * Web 走 ktor 的挂起接口，不需要。VM 只在自己的可取消作用域里调用，不再替实现挑调度器
 *（与 `EmptyRoomSource` 同一条约定）。
 *
 * ## 账号命名空间
 *
 * [snapshot] / [persist] 的 [accountId] 是「哪一份缓存」的键（`:app` 按它分 SharedPreferences 文件），
 * 默认是当前账号。**调用方要在发请求前就把它定下来**：结果回来时可能已经切到别的账号，
 * 落盘必须写回发起时那个账号的命名空间（`AccountContext.suffixFor` 的 KDoc 讲了这条）。Web 端没有
 * 账号隔离，实现忽略它。
 */
interface CampusCardSource {

    /** 卡面信息（余额、待入账、挂失/冻结、卡类型）。 */
    suspend fun card(): CardInfo

    /**
     * [from]..[to] 的流水**按页取**：[page] 从 1 开始，返回 (服务端总数, 当页流水)。
     *
     * 为什么是「一页」而不是「整段」：屏有三种取数节奏 —— 首屏最多 12 页（[allTransactions]）、
     * 接上缓存为止（[transactionsUntilKnown]）、下拉「加载更多」再要一页 —— 它们全都建立在
     * 「服务端按页排、并且给总数」之上。把整段一次拉全会把这三条节奏压成一条，
     * 也就改了 Android 现在的请求数与首屏耗时。
     */
    suspend fun transactions(
        from: LocalDate,
        to: LocalDate,
        page: Int = 1,
        pageSize: Int = 50,
    ): Pair<Int, List<Transaction>>

    /**
     * 上次落盘的那份快照（余额 + 今日消费 + 落盘时间），**已裁到 [from]..[to]**；没有缓存返回 null。
     *
     * 裁在端口里做：屏在进屏时与增量取数前都只关心这一段（原来是把整份拿去自己过滤），
     * 于是屏不必知道「快照可能覆盖一段更宽的区间」这件事。
     *
     * Web 端**如实降级**：浏览器里没有这份落盘缓存（`localStorage` 不放这种大块数据），
     * 只保留「本次会话已经拉过的那一份」—— 刷新页面后就没有首屏秒开了，不会造一个假的。
     */
    suspend fun snapshot(
        from: LocalDate,
        to: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    ): CampusCardSnapshot?

    /**
     * 只把**卡面**写回首页卡片与桌面小组件读的那组 key（余额 / 卡名 / 落盘时刻）。
     *
     * 为什么单独一个入口（而不是合并进 [persist]）：搬迁前这三行写在「卡信息回来了、流水还在路上」
     * 的位置（`load()` 里 `coroutineScope` 内、`txs.await()` 之前），注释写的是「余额先到先显示」；
     * 流水那一路失败时它照样落盘。合并进 [persist] 会把这次落盘推到流水回来之后，
     * 那就改了「卡面取到但流水失败」时的行为。Web 端没有首页/小组件那份缓存 ⇒ 空实现。
     */
    suspend fun persistCard(
        card: CardInfo,
        accountId: String? = AccountContext.activeAccountId,
    )

    /**
     * 把这次取到的数据写回：[card] + [transactions] 覆盖 [from]..[to]。
     *
     * 一次调用写两份东西（顺序与搬迁前一致）：先写「首页卡片与桌面小组件读的那组 key」
     *（余额 / 卡名 / 落盘时刻 / 今日三餐 / 近 30 天日均），再写「下次进屏要的那份快照」。
     * 前者是 Android 首页与小组件的输入，后者是本屏首屏秒开的输入 —— 两者都是各端的存储实现。
     */
    suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    )
}

/**
 * 按服务端总数拉全部分页。任一页失败或出现残页、重复页、总数变化时抛错，
 * 不再静默丢掉中间页还当成查询成功。
 *
 * 原来挂在 :app 的 `CampusCardApi` 上（那是纯分页编排，不碰网络细节），屏搬进 `:core` 后跟着来，
 * 逻辑一行未改；`getTransactions(...)` 换成端口的 [CampusCardSource.transactions]。
 *
 * @param allowIncomplete 首页冷启动可以先拿前几页，其余走「加载更多」。
 */
suspend fun CampusCardSource.allTransactions(
    startDate: LocalDate,
    endDate: LocalDate,
    maxPages: Int = 80,
    pageSize: Int = 50,
    allowIncomplete: Boolean = false,
): List<Transaction> {
    if (maxPages <= 0 || pageSize <= 0) {
        throw RuntimeException("查询校园卡流水的分页参数必须为正数")
    }
    val (total, firstPage) = transactions(startDate, endDate, 1, pageSize)
    if (total == 0) return emptyList()
    if (firstPage.isEmpty()) {
        if (allowIncomplete) return emptyList()
        throw RuntimeException("查询校园卡流水返回了残缺流水数据")
    }

    val records = firstPage.toMutableList()
    val seenPages = mutableSetOf(pageSignature(firstPage))
    val computedPages = maxOf(1, (total + pageSize - 1) / pageSize)
    val totalPages = minOf(computedPages, maxPages)
    if (records.size > total) {
        throw RuntimeException("查询校园卡流水返回的流水记录超过总数")
    }
    if (records.size == total || totalPages <= 1) return records

    // 其余分页最多 3 路并发，每页 45 秒超时
    //（`Dispatchers.IO.limitedParallelism` 里的 IO 是 JVM 专属，:core 用 Default —— 这几个请求本来就是
    //  挂起式的，限制的是并发路数而不是线程池。）
    val pageDispatcher = Dispatchers.Default.limitedParallelism(3)
    coroutineScope {
        val pages = (2..totalPages).map { page ->
            page to async(pageDispatcher) { withTimeout(45_000) { transactions(startDate, endDate, page, pageSize) } }
        }
        for ((page, deferred) in pages) {
            val (pageTotal, batch) = try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RuntimeException("查询校园卡流水第${page}页失败，请检查网络后重试", e)
            }
            if (pageTotal != total) {
                throw RuntimeException("查询校园卡流水返回的总数在分页过程中发生变化")
            }
            if (batch.isNotEmpty()) {
                val signature = pageSignature(batch)
                if (!seenPages.add(signature)) {
                    throw RuntimeException("查询校园卡流水返回了重复分页数据")
                }
                records += batch
            }
            if (records.size > total) {
                throw RuntimeException("查询校园卡流水返回的流水记录超过总数")
            }
        }
    }
    if (records.size == total) return records
    if (allowIncomplete) return records
    throw RuntimeException("查询校园卡流水返回了残缺流水数据")
}

/**
 * 从第 1 页往后拉，拉到某页里出现 [isKnown] 的流水为止。服务端按入账时间倒序排
 * （2026-09 实测一年 961 条无一例外），延迟上传的旧流水入账时间也是新的，同样排在前面，
 * 所以接上已有数据之后的页不会再有新东西。
 *
 * 同 [allTransactions]：从 :app 的 `CampusCardApi` 搬来，逻辑一行未改。
 */
suspend fun CampusCardSource.transactionsUntilKnown(
    startDate: LocalDate,
    endDate: LocalDate,
    maxPages: Int,
    pageSize: Int = 50,
    isKnown: (Transaction) -> Boolean,
): List<Transaction> {
    val records = mutableListOf<Transaction>()
    for (page in 1..maxPages) {
        val (total, batch) = transactions(startDate, endDate, page, pageSize)
        records += batch
        if (batch.any(isKnown) || batch.size < pageSize || records.size >= total) break
    }
    return records
}

private fun pageSignature(batch: List<Transaction>): String = batch.joinToString("\n") { it.uniqueKey() }
