package com.xjtu.toolbox.card

import com.xjtu.toolbox.account.AccountContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/** 校园卡的取数端口：屏与 ViewModel 都在 `:core`，两端各自填同一批字段。 */
interface CampusCardSource {

    /** 卡面信息（余额、待入账、挂失/冻结、卡类型）。 */
    suspend fun card(): CardInfo

    /** [from]..[to] 的流水按页取：[page] 从 1 开始，返回 (服务端总数, 当页流水)。 */
    suspend fun transactions(
        from: LocalDate,
        to: LocalDate,
        page: Int = 1,
        pageSize: Int = 50,
    ): Pair<Int, List<Transaction>>

    /** 上次落盘的那份快照（余额 + 今日消费 + 落盘时间），已裁到 [from]..[to]；没有缓存返回 null。 */
    suspend fun snapshot(
        from: LocalDate,
        to: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    ): CampusCardSnapshot?

    /** 只把卡面写回首页卡片与桌面小组件读的那组 key（余额 / 卡名 / 落盘时刻）。 */
    suspend fun persistCard(
        card: CardInfo,
        accountId: String? = AccountContext.activeAccountId,
    )

    /** 把这次取到的数据写回：[card] + [transactions] 覆盖 [from]..[to]。 */
    suspend fun persist(
        card: CardInfo,
        transactions: List<Transaction>,
        from: LocalDate,
        to: LocalDate,
        accountId: String? = AccountContext.activeAccountId,
    )
}

/** 按服务端总数拉全部分页。 */
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

/** 从第 1 页往后拉，拉到某页里出现 [isKnown] 的流水为止。 */
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
