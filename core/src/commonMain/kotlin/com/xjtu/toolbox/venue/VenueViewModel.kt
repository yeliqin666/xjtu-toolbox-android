package com.xjtu.toolbox.venue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.util.todayInSystemZone
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

internal sealed interface VenuePage {
    data object VenueList : VenuePage
    data object SlotSelection : VenuePage
}

internal sealed interface VenueEvent {
    data object AuthExpired : VenueEvent
    data class Message(val text: String) : VenueEvent
}

/**
 * 场馆预订：列表 → 选时段 → 验证码 → 下单，外加订单分页和取消。
 *
 * 从 `:app` 搬进 `:core` 时**编排逻辑一行未改**，只换了五处「住址」：
 *
 *  - 取数从 `VenueApi(site)` 换成端口 [VenueSource]（读那三路 + 写那四路 + 收藏，见那个接口的 KDoc）——
 *    屏不再认识 `SiteSession`，也不再自己挑 `Dispatchers.IO`（实现方包）；
 *  - `java.time` 换成 kotlinx-datetime（「今天」用 [todayInSystemZone]，日期串用
 *    `LocalDate.toString()`，就是 `ISO_LOCAL_DATE` 那个形状）；
 *  - `android.util.Log` 那几行本地排障日志删掉（`:core` 没有 `Log`，它不参与任何可观测行为）；
 *  - `System.currentTimeMillis()` 换成 kotlinx 的 `Clock`（`:core` 的公共代码里没有 `java.lang.System`）；
 *  - 会话失效由 `:core` 的标记接口 [SessionExpiredFailure] 认领（`:app` 的 `AuthExpiredException`
 *    实现了它）——`catch` 抓不了接口，所以先抓 `Exception` 再判，与评教/成绩/校园卡同一条缝。
 *
 * ## 两个只在写路径上用的参数
 *
 * [autoSolveCaptcha] 与 [solveCaptcha] 是「自动识别验证码」这一段的两个宿主输入：
 * 前者是设置项（Android 读 `CredentialStore.venueAutoSolveCaptchaEnabled`，是个 lambda 而不是
 * 布尔值 —— 搬之前 VM 就是这么**每次现读**的，用户在设置里关掉后下一次预订立刻生效），
 * 后者是识别器本身（`:app` = `VenueCaptchaSolver`，Web = null）。识别器为 null 时这一整段直接跳过，
 * 停在「请手动滑动」那一档 —— 与只读端（[VenueSource.canBook] = false）的入口封条互相印证：
 * 没有验证码这条路，也不会有验证码弹窗。
 */
internal class VenueViewModel(
    /** 本端的取数 + 收藏实现（Android = `AppVenueSource`，Web = `CampusVenueApi`）。 */
    private val source: VenueSource,
    /** 「验证码自动识别」这个设置项，**每次现读**（见类 KDoc）。 */
    private val autoSolveCaptcha: () -> Boolean = { false },
    /** 自动识别槽位：给验证码数据与「它是什么时候出现在屏幕上的」，返回盖章后的轨迹；null = 本端没有。 */
    private val solveCaptcha: (suspend (data: CaptchaData, shownAtMillis: Long) -> SolvedCaptcha?)? = null,
) : ViewModel() {
    private val eventChannel = Channel<VenueEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    var page by mutableStateOf<VenuePage>(VenuePage.VenueList); private set

    var venues by mutableStateOf<List<Venue>>(emptyList()); private set
    var venueLoading by mutableStateOf(true); private set
    var venueRefreshing by mutableStateOf(false); private set
    var venueError by mutableStateOf<String?>(null); private set
    var favoriteIds by mutableStateOf<Set<Int>>(emptySet()); private set

    var selectedVenue by mutableStateOf<Venue?>(null); private set
    var selectedDate by mutableStateOf(todayInSystemZone()); private set
    var availableSlots by mutableStateOf<List<AreaSlot>>(emptyList()); private set
    var slotsLoading by mutableStateOf(false); private set
    var slotsRefreshing by mutableStateOf(false); private set
    var slotsError by mutableStateOf<String?>(null); private set
    var selectedSlots by mutableStateOf<Set<AreaSlot>>(emptySet()); private set

    var showCaptcha by mutableStateOf(false); private set
    var bookingInProgress by mutableStateOf(false); private set
    var bookingResult by mutableStateOf<BookingResult?>(null); private set
    var captchaData by mutableStateOf<CaptchaData?>(null); private set
    var captchaLoading by mutableStateOf(false); private set
    var captchaError by mutableStateOf<String?>(null); private set
    var captchaAutoSolving by mutableStateOf(false); private set
    var captchaNotice by mutableStateOf<String?>(null); private set
    private var pendingOrder: PendingOrder? = null
    // 每次加载 / 关闭验证码都递增，丢弃旧协程的结果，免得换图后旧识别结果误提交
    private var captchaToken = 0

    var orders by mutableStateOf<List<OrderInfo>>(emptyList()); private set
    var ordersLoading by mutableStateOf(false); private set
    var ordersLoadingMore by mutableStateOf(false); private set
    var ordersError by mutableStateOf<String?>(null); private set
    var ordersHasMore by mutableStateOf(false); private set
    var orderActionLoading by mutableStateOf(false); private set
    private var nextOrderPage = 1

    init {
        loadVenues()
        loadFavorites()
    }

    private fun send(event: VenueEvent) { viewModelScope.launch { eventChannel.send(event) } }

    fun loadVenues(silent: Boolean = false) {
        if (silent) venueRefreshing = true else venueLoading = true
        venueError = null
        viewModelScope.launch {
            try {
                venues = source.venues()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) send(VenueEvent.AuthExpired)
                else venueError = FriendlyError.of(e, "加载场馆列表")
            } finally {
                venueLoading = false
                venueRefreshing = false
            }
        }
    }

    /**
     * 收藏读一次。搬迁前是 `VenueFavorites(context)` 在构造时同步读 `SharedPreferences`；
     * 现在读这件事在端口上（各端各自落盘，见 [VenueSource.favorites]），所以是挂起的一次读。
     * 读失败只当没有收藏，不让整屏起不来。
     */
    private fun loadFavorites() {
        viewModelScope.launch {
            favoriteIds = try {
                source.favorites()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
        }
    }

    /**
     * 切换收藏。
     *
     * 次序与搬迁前一致：点击那一刻**内存里的那份先变**（原来是 `VenueFavorites` 的 StateFlow
     * 同步变、心形动画立刻起来），落盘随后；落盘方返回的状态才是最终值，所以再用它对账一次。
     */
    fun toggleFavorite(venue: Venue) {
        val nowFavorite = venue.id !in favoriteIds
        favoriteIds = if (nowFavorite) favoriteIds + venue.id else favoriteIds - venue.id
        send(VenueEvent.Message(if (nowFavorite) "已收藏 ${venue.name}" else "已取消收藏 ${venue.name}"))
        viewModelScope.launch {
            try {
                val isFavorite = source.toggleFavorite(venue.id)
                favoriteIds = if (isFavorite) favoriteIds + venue.id else favoriteIds - venue.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(VenueEvent.Message(FriendlyError.of(e, "收藏")))
            }
        }
    }

    fun openVenue(venue: Venue) {
        selectedVenue = venue
        page = VenuePage.SlotSelection
        loadSlots()
    }

    fun closeVenue() {
        page = VenuePage.VenueList
        selectedSlots = emptySet()
    }

    fun selectDate(date: LocalDate) {
        if (date == selectedDate) return
        selectedDate = date
        if (selectedVenue != null && page == VenuePage.SlotSelection) loadSlots(silent = availableSlots.isNotEmpty())
    }

    /** 选中/取消一个时段。只读端（[VenueSource.canBook] = false）屏上根本不给勾，这里再兜一层。 */
    fun toggleSlot(slot: AreaSlot) {
        if (!source.canBook) return
        selectedSlots = if (slot in selectedSlots) selectedSlots - slot else selectedSlots + slot
    }

    fun loadSlots(silent: Boolean = false) {
        val venueId = selectedVenue?.id ?: return
        if (silent) slotsRefreshing = true else slotsLoading = true
        slotsError = null
        if (!silent) selectedSlots = emptySet()
        val date = selectedDate.toString()
        viewModelScope.launch {
            try {
                availableSlots = source.slots(venueId, date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) send(VenueEvent.AuthExpired)
                else slotsError = FriendlyError.of(e, "加载时段")
            } finally {
                slotsLoading = false
                slotsRefreshing = false
            }
        }
    }

    /** 下单前先占位（prepareOrder），再拉验证码。 */
    fun startBooking() {
        val venue = selectedVenue ?: return
        if (!source.canBook) return
        showCaptcha = true
        val token = beginCaptcha()
        pendingOrder = null
        viewModelScope.launch {
            try {
                val order = source.prepareOrder(venue.id, selectedSlots.toList())
                if (token != captchaToken || !showCaptcha) return@launch
                pendingOrder = order
                val data = source.captcha(venue.id)
                processCaptcha(data, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != captchaToken) return@launch
                if (e is SessionExpiredFailure) {
                    closeCaptcha(); send(VenueEvent.AuthExpired)
                } else {
                    captchaError = FriendlyError.of(e, "获取验证码")
                }
            } finally {
                if (token == captchaToken) { captchaLoading = false; captchaAutoSolving = false }
            }
        }
    }

    fun reloadCaptcha() {
        val venue = selectedVenue ?: return
        val token = beginCaptcha()
        viewModelScope.launch {
            try {
                val data = source.captcha(venue.id)
                processCaptcha(data, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != captchaToken) return@launch
                if (e is SessionExpiredFailure) {
                    closeCaptcha(); send(VenueEvent.AuthExpired)
                } else {
                    captchaError = FriendlyError.of(e, "获取验证码")
                }
            } finally {
                if (token == captchaToken) { captchaLoading = false; captchaAutoSolving = false }
            }
        }
    }

    private fun beginCaptcha(): Int {
        captchaLoading = true
        captchaAutoSolving = false
        captchaError = null
        captchaNotice = null
        captchaData = null
        return ++captchaToken
    }

    fun closeCaptcha() {
        showCaptcha = false
        captchaToken++
        captchaLoading = false
        captchaAutoSolving = false
    }

    /** 自动识别是设置项，默认开；识别失败只提示，保留当前验证码让用户手滑。 */
    private suspend fun processCaptcha(data: CaptchaData, token: Int) {
        if (token != captchaToken || !showCaptcha) return
        captchaData = data
        captchaNotice = null
        val solve = solveCaptcha ?: return
        if (!autoSolveCaptcha()) return
        val shownAt = Clock.System.now().toEpochMilliseconds()
        captchaAutoSolving = true
        val solved = try {
            withContext(Dispatchers.Default) { solve(data, shownAt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (token != captchaToken || !showCaptcha) return
        if (solved == null) {
            captchaAutoSolving = false
            captchaNotice = "自动识别未通过，请手动滑动滑块"
            return
        }
        // 按轨迹的节奏等到「松手」那一刻再提交，时间戳才对得上（轨迹里的两个时刻由实现方按
        // shownAt 盖好，见 SolvedCaptcha 的 KDoc）
        delay((shownAt + solved.releaseAfterMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L))
        if (token != captchaToken || !showCaptcha) return
        captchaAutoSolving = false
        submitBooking(solved.sliderResult)
    }

    fun submitBooking(slider: SliderResult) {
        val order = pendingOrder ?: return
        val captcha = captchaData ?: return
        val venue = selectedVenue ?: return
        if (bookingInProgress) return
        captchaToken++
        captchaLoading = false
        captchaAutoSolving = false
        bookingInProgress = true
        viewModelScope.launch {
            bookingResult = try {
                source.submitBooking(
                    serviceId = venue.id,
                    pendingOrder = order,
                    captchaId = captcha.id,
                    sliderTrackJson = slider.toJson(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BookingResult(false, message = FriendlyError.of(e, "预订"))
            } finally {
                bookingInProgress = false
            }
            showCaptcha = false
        }
    }

    /** 关掉结果弹窗；[refresh] 为 true 时清掉已选时段并重拉（预订成功后时段已被占）。 */
    fun dismissResult(refresh: Boolean) {
        val success = bookingResult?.success == true
        bookingResult = null
        if (refresh && success) {
            selectedSlots = emptySet()
            loadSlots()
        }
    }

    /** 订单：[reset] 为 true 拉第一页，保留旧列表让刷新时页面仍可操作。 */
    fun loadOrders(reset: Boolean = true) {
        if (ordersLoading || ordersLoadingMore) return
        if (!reset && !ordersHasMore) return
        val page = if (reset) 1 else nextOrderPage
        if (reset) { ordersLoading = true; nextOrderPage = 1; ordersHasMore = false } else ordersLoadingMore = true
        ordersError = null
        viewModelScope.launch {
            try {
                val result = source.orders(page = page, pageSize = 20)
                orders = if (reset) result.orders else (orders + result.orders).distinctBy { it.orderId }
                nextOrderPage = result.page + 1
                ordersHasMore = result.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) send(VenueEvent.AuthExpired)
                else ordersError = FriendlyError.of(e, "加载订单")
            } finally {
                if (reset) ordersLoading = false else ordersLoadingMore = false
            }
        }
    }

    fun loadOrdersOnce() {
        if (orders.isEmpty() && !ordersLoading) loadOrders(reset = true)
    }

    fun cancelOrder(order: OrderInfo) {
        // 只读端屏上不画「取消订单」（[VenueSource.canCancel] = false），这里再兜一层
        if (!source.canCancel) return
        if (orderActionLoading) return
        orderActionLoading = true
        viewModelScope.launch {
            try {
                val result = source.cancelOrder(order.orderId)
                send(VenueEvent.Message(result.message))
                if (result.success) loadOrders(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) send(VenueEvent.AuthExpired)
                else send(VenueEvent.Message(FriendlyError.of(e, "取消订单")))
            } finally {
                orderActionLoading = false
            }
        }
    }
}
