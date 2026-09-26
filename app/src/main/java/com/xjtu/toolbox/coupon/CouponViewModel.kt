package com.xjtu.toolbox.coupon

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.nav.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 加餐券：按分类分页，领取；换分类时取消上一个分类还没回来的请求。 */
internal class CouponViewModel(context: Context, site: SiteSession) : ViewModel() {
    private val appContext = context.applicationContext
    private val api = CouponApi(site)
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
                val data = withContext(Dispatchers.IO) { api.queryCoupons(filter = filter, page = page, pageSize = 20) }
                total = data.total
                this@CouponViewModel.page = page
                records = if (append) records + data.records else data.records
                if (!append) pushHomeStat(filter, data.total)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpiredChannel.send(Unit)
            } catch (e: Exception) {
                if (append) loadMoreError = e.message ?: "加载更多失败" else errorMessage = e.message ?: "加载失败"
            } finally {
                isLoading = false
                isLoadingMore = false
                isRefreshing = false
            }
        }
    }

    /** 顺手把摘要留给首页：只记「可领取 / 可使用」，已用完、已过期的条数写上去只会误导。 */
    private fun pushHomeStat(filter: CouponFilter, count: Int) {
        if (filter == CouponFilter.AVAILABLE) pendingCount = count
        if (filter == CouponFilter.USABLE) usableCount = count
        if (pendingCount < 0 && usableCount < 0) return
        val parts = buildList {
            if (pendingCount > 0) add("$pendingCount 个待领取")
            if (usableCount > 0) add("$usableCount 个待使用")
        }
        HomeStats.push(appContext, AppRoute.Coupon, parts.firstOrNull() ?: "暂无可用", parts.drop(1).firstOrNull())
    }

    fun receive(coupon: CouponRecord) {
        val id = coupon.showCardId
        if (id.isBlank() || id in receivingIds) return
        receivingIds = receivingIds + id
        statusMessage = null
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    val fetched = runCatching { api.getCouponDetail(id) }.getOrNull()
                    api.activateCoupon(id)
                    fetched
                }
                statusMessage = detail?.title?.takeIf { it.isNotBlank() } ?: "已领取 ${coupon.voucherName}"
                load(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpiredChannel.send(Unit)
            } catch (e: Exception) {
                statusMessage = "领取失败：${e.message ?: "网络异常"}"
            } finally {
                receivingIds = receivingIds - id
            }
        }
    }
}
