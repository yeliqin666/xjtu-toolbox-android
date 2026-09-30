package com.xjtu.toolbox.card

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.widget.CampusCardWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal enum class TimeRange(val label: String, val months: Int?) {
    ONE_MONTH("1个月", 1),
    THREE_MONTHS("3个月", 3),
    SIX_MONTHS("半年", 6),
    ONE_YEAR("1年", 12),
    CUSTOM("自定义", null);

    fun resolve(customStart: LocalDate, customEnd: LocalDate): Pair<LocalDate, LocalDate> {
        if (this == CUSTOM) return if (customStart.isAfter(customEnd)) customEnd to customStart else customStart to customEnd
        return LocalDate.now().minusMonths((months ?: 1).toLong()) to LocalDate.now()
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

internal class CampusCardViewModel(
    context: Context,
    site: SiteSession,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val context = context.applicationContext
    private val api = CampusCardApi(site)
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
    private var customStart: LocalDate = saved.get<String>(KEY_START)?.let(LocalDate::parse) ?: LocalDate.now().minusMonths(1)
    private var customEnd: LocalDate = saved.get<String>(KEY_END)?.let(LocalDate::parse) ?: LocalDate.now()
    private var generation = 0

    val range: Pair<LocalDate, LocalDate> get() = timeRange.resolve(customStart, customEnd)

    init {
        viewModelScope.launch {
            // 缓存覆盖的区间可能比当前范围宽：先裁到当前范围，首屏与网络结果一致，不会闪一下多出来的面板
            val cached = withContext(Dispatchers.IO) { CampusCardCache.load(context) }
            if (cached != null) {
                cardInfo = cached.cardInfo
                val (start, end) = range
                show(cached.transactions.filter { tx -> tx.date()?.let { it in start..end } == true })
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

    /** 换上新流水：统计在后台算好再一次性换上。 */
    private suspend fun show(all: List<Transaction>, accountId: String? = AccountContext.activeAccountId, persist: Boolean = false) {
        val (start, end) = range
        val computed = withContext(Dispatchers.Default) { computeStats(all, start, end) }
        transactions = all
        stats = computed
        if (persist) {
            withContext(Dispatchers.IO) {
                CampusCardCache.cardPrefs(context, accountId).edit()
                    .putTodaySummary(todaySummaryOf(all))
                    .putDailyRate(computed.dailyRate)
                    .apply()
            }
            CampusCardWidgetUpdater.requestUpdate(context)
        }
    }

    private fun computeStats(all: List<Transaction>, start: LocalDate, end: LocalDate): CardStats {
        val (meals, days) = api.analyzeMealTimes(all)
        return CardStats(
            monthly = api.calculateMonthlyStats(all, start, end),
            categories = api.categorizeSpending(all),
            food = api.breakdownFood(all),
            mealTimes = meals,
            activeDays = days,
            hourly = api.hourlyMeals(all),
            dailyRate = dailySpendRate(all),
            weekdayWeekend = api.analyzeWeekdayVsWeekend(all),
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
                    val txs = async(Dispatchers.IO) { fetchRange(start, end, accountId) }
                    val info = withContext(Dispatchers.IO) { api.getCardInfo() }
                    if (!switched()) {
                        cardInfo = info
                        withContext(Dispatchers.IO) {
                            CampusCardCache.cardPrefs(context, accountId).edit()
                                .putFloat("card_balance_cache", info.balance.toFloat())
                                .putString("card_name_cache", info.name)
                                .putLong("card_cache_time", System.currentTimeMillis())
                                .apply()
                        }
                    }
                    info to txs.await()
                }
                if (mine != generation || switched()) return@launch
                show(all, accountId, persist = true)
                withContext(Dispatchers.IO) { CampusCardCache.save(context, info, all, start, end, accountId) }
                eventChannel.send(CampusCardEvent.CacheUpdated)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                eventChannel.send(CampusCardEvent.AuthExpired)
            } catch (e: Exception) {
                errorMessage = FriendlyError.of(e, "加载校园卡")
                if (transactions.isNotEmpty()) eventChannel.send(CampusCardEvent.Message("更新失败，当前显示上次缓存的数据", long = true))
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
        val cached = CampusCardCache.load(context, accountId)
        val cachedStart = cached?.rangeStart?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (cached == null || cachedStart == null || cachedStart.isAfter(start) || cached.transactions.isEmpty()) {
            return api.getAllTransactions(start, end, maxPages = 12, allowIncomplete = true)
        }
        val known = cached.transactions.mapTo(HashSet()) { it.uniqueKey() }
        val fresh = api.getTransactionsUntilKnown(start, end, maxPages = 12, pageSize = PAGE_SIZE) { it.uniqueKey() in known }
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
                val (_, more) = withContext(Dispatchers.IO) {
                    api.getTransactions(startDate = start, endDate = end, page = held.size / PAGE_SIZE + 1, pageSize = PAGE_SIZE)
                }
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
            } catch (_: AuthExpiredException) {
                eventChannel.send(CampusCardEvent.AuthExpired)
            } catch (e: Exception) {
                Log.w(TAG, "loadMore failed: ${e.message}")
                eventChannel.send(CampusCardEvent.Message("加载更多失败，请重试"))
            } finally {
                isLoadingMore = false
            }
        }
    }

    private companion object {
        const val TAG = "CampusCardViewModel"
        const val PAGE_SIZE = 50
        const val KEY_RANGE = "range"
        const val KEY_START = "customStart"
        const val KEY_END = "customEnd"
    }
}

private fun Transaction.date(): LocalDate? = runCatching { LocalDate.parse(time.substringBefore(" ")) }.getOrNull()
