package com.xjtu.toolbox.coupon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 加餐券：按分类分页，领取；换分类时取消上一个分类还没回来的请求。
 *
 * 从 `:app` 搬进 `:core`：**编排逻辑一行未改**（按分类拉页、翻页失败只提示不丢列表、
 * 领取先查详情再激活、会话失效走 [SessionExpiredFailure]），只换了三处「住址」：
 *
 *  - 取数从 `CouponApi(site)` 换成端口 [CouponSource]（Android / 桌面 = `:data` 的
 *    `AppCouponSource`）—— VM 不再认识 `SiteSession`，也不再自己挑 `Dispatchers.IO`；
 *  - 「把摘要留给首页」从 `HomeStats.push(appContext, AppRoute.Coupon, …)`（Android 的
 *    `Context` + 首页缓存）换成参数 [onSummary] —— 桌面没有那份首页摘要，传默认不写；
 *  - 会话失效由 `:core` 的标记接口 [SessionExpiredFailure] 认领（`:app` 的
 *    `AuthExpiredException` 实现了它）——`catch` 抓不了接口，所以先抓 `Exception` 再判，
 *    与评教/成绩/校园卡同一条缝。
 */
internal class CouponViewModel(
    private val source: CouponSource,
    /**
     * 首页摘要回写（值 + 说明）：Android = `HomeStats.push(appContext, AppRoute.Coupon, …)`
     *（与搬迁前那一行逐字一致）；桌面/Web 没有那份首页摘要 ⇒ 默认空实现。
     */
    private val onSummary: (summary: String, detail: String?) -> Unit = { _, _ -> },
) : ViewModel() {
    private val authExpiredChannel = Channel<Unit>(Channel.CONFLATED)
    val authExpired = authExpiredChannel.receiveAsFlow()

    var filter by mutableStateOf(CouponFilter.USABLE); private set
    var records by mutableStateOf<List<CouponRecord>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    private var page = 1
    var isLoading by mutableStateOf(true); private set
    var isLoadingMore by mutableStateOf(false); private set
    var isRefreshing by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    /** 翻页失败只提示、停止自动翻页，保住已加载的列表。 */
    var loadMoreError by mutableStateOf<String?>(null); private set
    var statusMessage by mutableStateOf<String?>(null); private set
    var receivingIds by mutableStateOf<Set<String>>(emptySet()); private set

    // 首页摘要用：-1 表示本次还没查过该分类
    private var pendingCount = -1
    private var usableCount = -1
    private var job: Job? = null

    init { load() }

    fun selectFilter(value: CouponFilter) {
        if (value == filter) return
        filter = value
        records = emptyList()
        total = 0
        load()
    }

    fun refresh() {
        isRefreshing = true
        load(silent = true)
    }

    fun loadMore() {
        if (isLoadingMore || isLoading) return
        load(page = page + 1, append = true)
    }

    fun load(page: Int = 1, append: Boolean = false, silent: Boolean = false) {
        if (!append) job?.cancel()
        when {
            append -> isLoadingMore = true
            !silent -> isLoading = true
        }
        errorMessage = null
        loadMoreError = null
        val filter = filter
        job = viewModelScope.launch {
            try {
                val data = source.queryCoupons(filter = filter, page = page, pageSize = 20)
                total = data.total
                this@CouponViewModel.page = page
                records = if (append) records + data.records else data.records
                if (!append) pushHomeStat(filter, data.total)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpiredChannel.send(Unit)
                } else if (append) {
                    loadMoreError = FriendlyError.of(e, "加载更多")
                } else {
                    errorMessage = FriendlyError.of(e, "加载加餐券")
                }
            } finally {
                isLoading = false
                isLoadingMore = false
                isRefreshing = false
            }
        }
    }

    /**
     * 顺手把摘要留给首页：只记「可领取 / 可使用」，已用完、已过期的条数写上去只会误导。
     * 文案不变（「N 个待领取 / N 个待使用」，都没有就说「暂无可用」），
     * 只是从「写 Android 的 HomeStats」变成「交给宿主 [onSummary]」。
     */
    private fun pushHomeStat(filter: CouponFilter, count: Int) {
        if (filter == CouponFilter.AVAILABLE) pendingCount = count
        if (filter == CouponFilter.USABLE) usableCount = count
        if (pendingCount < 0 && usableCount < 0) return
        val parts = buildList {
            if (pendingCount > 0) add("$pendingCount 个待领取")
            if (usableCount > 0) add("$usableCount 个待使用")
        }
        onSummary(parts.firstOrNull() ?: "暂无可用", parts.drop(1).firstOrNull())
    }

    fun receive(coupon: CouponRecord) {
        val id = coupon.showCardId
        if (id.isBlank() || id in receivingIds) return
        receivingIds = receivingIds + id
        statusMessage = null
        viewModelScope.launch {
            try {
                val detail = runCatching { source.getCouponDetail(id) }.getOrNull()
                source.activateCoupon(id)
                statusMessage = detail?.title?.takeIf { it.isNotBlank() } ?: "已领取 ${coupon.voucherName}"
                load(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpiredChannel.send(Unit)
                } else {
                    statusMessage = FriendlyError.of(e, "领取")
                }
            } finally {
                receivingIds = receivingIds - id
            }
        }
    }
}