package com.xjtu.toolbox.card

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.util.todayInSystemZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

internal enum class TimeRange(val label: String, val months: Int?) {
    ONE_MONTH("1个月", 1),
    THREE_MONTHS("3个月", 3),
    SIX_MONTHS("半年", 6),
    ONE_YEAR("1年", 12),
    CUSTOM("自定义", null);

    fun resolve(customStart: LocalDate, customEnd: LocalDate): Pair<LocalDate, LocalDate> {
        // `java.time` 的 `LocalDate.now().minusMonths(n)` → kotlinx-datetime 的
        // `todayInSystemZone().minus(n, DateTimeUnit.MONTH)`（同一个系统默认时区、同一个日历减法）
        if (this == CUSTOM) return if (customStart > customEnd) customEnd to customStart else customStart to customEnd
        val today = todayInSystemZone()
        return today.minus(months ?: 1, DateTimeUnit.MONTH) to today
    }
}

/** 按当前流水算出的各项统计；流水一变整份重算。 */
internal data class CardStats(
    val monthly: List<MonthlyStats> = emptyList(),
    val categories: Map<String, Double> = emptyMap(),
    /** 餐饮内部的主食构成：顶层分类里餐饮常年 95%+，真正能看出差别的是这一层。 */
    val food: Map<String, Double> = emptyMap(),
    val mealTimes: Map<String, MealTimeStats> = emptyMap(),
    val activeDays: Int = 0,
    val hourly: List<Int> = emptyList(),
    /** 近 30 天在校日均，「约够几天」用。 */
    val dailyRate: Double? = null,
    val weekdayWeekend: Pair<DayTypeStats, DayTypeStats>? = null,
)

internal sealed interface CampusCardEvent {
    data object AuthExpired : CampusCardEvent
    /** 余额和今日消费已落盘，首页要重读。 */
    data object CacheUpdated : CampusCardEvent
    data class Message(val text: String, val long: Boolean = false) : CampusCardEvent
}

/**
 * 校园卡的 ViewModel。从 `:app` 搬进 `:core` 时**编排逻辑一行未改**，只换了四处"住址"：
 *
 *  - 取数从 `CampusCardApi(site)` 换成端口 [CampusCardSource]（卡面 / 流水 / 落盘快照，
 *    见那个接口的 KDoc）—— 屏不再认识 `SiteSession`，也不再自己挑 `Dispatchers.IO`；
 *  - 落盘从 `CampusCardCache`（Android 的 SharedPreferences）换成端口上的
 *    [CampusCardSource.persistCard] / [CampusCardSource.persist]；
 *  - `java.time` 换成 kotlinx-datetime（"今天"统一用 [todayInSystemZone]）；
 *  - 会话失效由 `:core` 的标记接口 [SessionExpiredFailure] 认领（`:app` 的 `AuthExpiredException`
 *    实现了它）——`catch` 抓不了接口，所以先抓 `Exception` 再判，与评教/成绩同一条缝。
 *
 * 还有两处**宿主能力**走参数而不是直接调（`:core` 里没有它们）：
 *  - [onBalanceChanged]：余额/今日消费刚写进缓存，桌面小组件要刷一下。Android 传
 *    `CampusCardWidgetUpdater.requestUpdate(context)`；Web 没有小组件，传空。
 *    为什么不写在端口里：小组件是 Android 的宿主能力（RemoteViews + AppWidgetManager），
 *    端口是「这一端怎么取数与落盘」，戳小组件是「这一端还有什么要跟着动」——两件事，分开注入。
 *  - `android.util.Log` 那两行本地排障日志删掉了（`:core` 没有 `Log`，它不参与任何可观测行为）。
 */
internal class CampusCardViewModel(
    /** 本端的取数 + 落盘实现（Android = `AppCampusCardSource`，Web = `CampusCardNetApi`）。 */
    private val source: CampusCardSource,
    private val saved: SavedStateHandle,
    /** 见类 KDoc：Android = 刷新桌面小组件；Web = 空。 */
    private val onBalanceChanged: () -> Unit = {},
) : ViewModel() {
    private val eventChannel = Channel<CampusCardEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    var isLoading by mutableStateOf(true); private set
    /** 已有内容时换范围 / 刷新：不挡整页，只在标签行下面显示细进度条。 */
    var isReloadingRange by mutableStateOf(false); private set
    var isLoadingMore by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var cardInfo by mutableStateOf<CardInfo?>(null); private set
    var transactions by mutableStateOf<List<Transaction>>(emptyList()); private set
    var stats by mutableStateOf(CardStats()); private set

    var timeRange by mutableStateOf(saved[KEY_RANGE] ?: TimeRange.ONE_MONTH); private set
    private var customStart: LocalDate = saved.get<String>(KEY_START)?.let(LocalDate::parse) ?: todayInSystemZone().minus(1, DateTimeUnit.MONTH)
    private var customEnd: LocalDate = saved.get<String>(KEY_END)?.let(LocalDate::parse) ?: todayInSystemZone()
    private var generation = 0

    val range: Pair<LocalDate, LocalDate> get() = timeRange.resolve(customStart, customEnd)

    init {
        viewModelScope.launch {
            // 缓存覆盖的区间可能比当前范围宽：先裁到当前范围，首屏与网络结果一致，不会闪一下多出来的面板
            //（裁剪由端口做：`snapshot(from, to)` 返回的就是这一段）
            val (start, end) = range
            val cached = source.snapshot(start, end)
            if (cached != null) {
                cardInfo = cached.cardInfo
                show(cached.transactions)
                isLoading = false
            }
            load(silent = transactions.isNotEmpty())
        }
    }

    fun selectRange(value: TimeRange) {
        if (value == timeRange) return
        timeRange = value
        saved[KEY_RANGE] = value
        load(silent = true)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        saved[KEY_START] = start.toString()
        saved[KEY_END] = end.toString()
        timeRange = TimeRange.CUSTOM
        saved[KEY_RANGE] = TimeRange.CUSTOM
        load(silent = true)
    }

    /**
     * 换上新流水：统计在后台算好再一次性换上。
     *
     * [cardToPersist] 非 null = 这次是联网取到的新数据，要把「首页/小组件那份摘要」与「下次进屏的快照」
     * 写下去（原来是 `persist: Boolean` + 从状态里读 cardInfo；现在由调用方把刚取到的卡面直接带进来 ——
     * 写快照要用**这一次**取到的卡面，而不是状态里可能已被别处改过的那个）。
     */
    private suspend fun show(
        all: List<Transaction>,
        accountId: String? = AccountContext.activeAccountId,
        cardToPersist: CardInfo? = null,
    ) {
        val (start, end) = range
        val computed = withContext(Dispatchers.Default) { computeStats(all, start, end) }
        transactions = all
        stats = computed
        if (cardToPersist != null) {
            // 搬迁前这里是两段：`cardPrefs.edit().putTodaySummary(...).putDailyRate(...)`（+ 戳小组件）
            // 与 `CampusCardCache.save(...)`。现在合成端口上的一次 persist：同一组 key、同一份快照。
            source.persist(cardToPersist, all, start, end, accountId)
            onBalanceChanged()
        }
    }

    private fun computeStats(all: List<Transaction>, start: LocalDate, end: LocalDate): CardStats {
        val (meals, days) = analyzeMealTimes(all)
        return CardStats(
            monthly = calculateMonthlyStats(all, start, end),
            categories = categorizeSpending(all),
            food = breakdownFood(all),
            mealTimes = meals,
            activeDays = days,
            hourly = hourlyMeals(all),
            dailyRate = dailySpendRate(all),
            weekdayWeekend = analyzeWeekdayVsWeekend(all),
        )
    }

    fun load(silent: Boolean = false) {
        val hasContent = transactions.isNotEmpty() || cardInfo != null
        if (silent || hasContent) isReloadingRange = true else isLoading = true
        errorMessage = null
        val mine = ++generation
        // 发请求前定下账号，缓存读写都落在它名下；中途切了账号，结果直接丢弃
        val accountId = AccountContext.activeAccountId
        fun switched() = AccountContext.activeAccountId != accountId
        val (start, end) = range
        viewModelScope.launch {
            try {
                // 卡信息和流水互不依赖，一起发；余额先到先显示
                val (info, all) = coroutineScope {
                    // 原来是 `async(Dispatchers.IO)`：阻塞式 okhttp 由端口实现自己包 IO（见 CampusCardSource
                    // 的 KDoc），这里只剩「把拉回来的列表和缓存合并/去重/排序」那点纯计算 —— 放到 Default，
                    // 与统计计算同一档，不占主线程。
                    val txs = async(Dispatchers.Default) { fetchRange(start, end, accountId) }
                    val info = source.card()
                    if (!switched()) {
                        cardInfo = info
                        // 余额先到先写：首页与小组件下一次读就能看到新余额（搬迁前这三行就写在这里，
                        // 早于流水；流水失败时它照样落盘）
                        source.persistCard(info, accountId)
                    }
                    info to txs.await()
                }
                if (mine != generation || switched()) return@launch
                show(all, accountId, cardToPersist = info)
                eventChannel.send(CampusCardEvent.CacheUpdated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 会话失效判 :core 的标记接口（:app 的 AuthExpiredException 实现了它）——
                // `catch` 抓不了接口，所以先抓 Exception 再判。这一支与搬迁前一样：不设 errorMessage。
                if (e is SessionExpiredFailure) {
                    eventChannel.send(CampusCardEvent.AuthExpired)
                } else {
                    errorMessage = FriendlyError.of(e, "加载校园卡")
                    if (transactions.isNotEmpty()) eventChannel.send(CampusCardEvent.Message("更新失败，当前显示上次缓存的数据", long = true))
                }
            } finally {
                if (mine == generation) {
                    isLoading = false
                    isReloadingRange = false
                }
            }
        }
    }

    /**
     * 缓存覆盖了这段范围就从最新往回拉，接上缓存为止（平时一页）；否则整段拉。
     * 以前固定补最近 7 天，隔了一周以上没打开，中间那段就漏了。
     */
    private suspend fun fetchRange(start: LocalDate, end: LocalDate, accountId: String?): List<Transaction> {
        val cached = source.snapshot(start, end, accountId)
        val cachedStart = cached?.rangeStart?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (cached == null || cachedStart == null || cachedStart > start || cached.transactions.isEmpty()) {
            return source.allTransactions(start, end, maxPages = 12, allowIncomplete = true)
        }
        val known = cached.transactions.mapTo(HashSet()) { it.uniqueKey() }
        val fresh = source.transactionsUntilKnown(start, end, maxPages = 12, pageSize = PAGE_SIZE) { it.uniqueKey() in known }
        return (fresh + cached.transactions.filter { tx -> tx.date()?.let { it in start..end } == true })
            .distinctBy { it.uniqueKey() }
            .sortedByDescending { it.time }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        val (start, end) = range
        val held = transactions
        viewModelScope.launch {
            try {
                // 服务端按 50 条一页排。已有列表可能是缓存合并出来的，条数不一定正好落在页边界：
                // 从已有条数所在的那一页接着拉，重叠的几条靠唯一键去掉，既不跳页也不重复。
                val (_, more) = source.transactions(
                    from = start,
                    to = end,
                    page = held.size / PAGE_SIZE + 1,
                    pageSize = PAGE_SIZE,
                )
                val known = held.mapTo(HashSet()) { it.uniqueKey() }
                val fresh = more.filter { known.add(it.uniqueKey()) }
                // 拉的过程中换了范围或刷新过列表：这批结果对不上了，丢掉
                if (fresh.isNotEmpty() && transactions === held) {
                    val all = held + fresh
                    stats = withContext(Dispatchers.Default) { computeStats(all, start, end) }
                    transactions = all
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    eventChannel.send(CampusCardEvent.AuthExpired)
                } else {
                    eventChannel.send(CampusCardEvent.Message("加载更多失败，请重试"))
                }
            } finally {
                isLoadingMore = false
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val KEY_RANGE = "range"
        const val KEY_START = "customStart"
        const val KEY_END = "customEnd"
    }
}

private fun Transaction.date(): LocalDate? = runCatching { LocalDate.parse(time.substringBefore(" ")) }.getOrNull()
