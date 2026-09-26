package com.xjtu.toolbox.venue

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal sealed interface VenuePage {
    data object VenueList : VenuePage
    data object SlotSelection : VenuePage
}

internal sealed interface VenueEvent {
    data object AuthExpired : VenueEvent
    data class Message(val text: String) : VenueEvent
}

/** 场馆预订：列表 → 选时段 → 验证码 → 下单，外加订单分页和取消。 */
internal class VenueViewModel(site: SiteSession, private val autoSolveCaptcha: () -> Boolean) : ViewModel() {
    val api = VenueApi(site)
    private val eventChannel = Channel<VenueEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    var page by mutableStateOf<VenuePage>(VenuePage.VenueList); private set

    var venues by mutableStateOf<List<VenueApi.Venue>>(emptyList()); private set
    var venueLoading by mutableStateOf(true); private set
    var venueRefreshing by mutableStateOf(false); private set
    var venueError by mutableStateOf<String?>(null); private set

    var selectedVenue by mutableStateOf<VenueApi.Venue?>(null); private set
    var selectedDate by mutableStateOf(LocalDate.now()); private set
    var availableSlots by mutableStateOf<List<VenueApi.AreaSlot>>(emptyList()); private set
    var slotsLoading by mutableStateOf(false); private set
    var slotsRefreshing by mutableStateOf(false); private set
    var slotsError by mutableStateOf<String?>(null); private set
    var selectedSlots by mutableStateOf<Set<VenueApi.AreaSlot>>(emptySet()); private set

    var showCaptcha by mutableStateOf(false); private set
    var bookingInProgress by mutableStateOf(false); private set
    var bookingResult by mutableStateOf<VenueApi.BookingResult?>(null); private set
    var captchaData by mutableStateOf<VenueApi.CaptchaData?>(null); private set
    var captchaLoading by mutableStateOf(false); private set
    var captchaError by mutableStateOf<String?>(null); private set
    var captchaAutoSolving by mutableStateOf(false); private set
    var captchaNotice by mutableStateOf<String?>(null); private set
    private var pendingOrder: VenueApi.PendingOrder? = null
    // 每次加载 / 关闭验证码都递增，丢弃旧协程的结果，免得换图后旧识别结果误提交
    private var captchaToken = 0

    var orders by mutableStateOf<List<VenueApi.OrderInfo>>(emptyList()); private set
    var ordersLoading by mutableStateOf(false); private set
    var ordersLoadingMore by mutableStateOf(false); private set
    var ordersError by mutableStateOf<String?>(null); private set
    var ordersHasMore by mutableStateOf(false); private set
    var orderActionLoading by mutableStateOf(false); private set
    private var nextOrderPage = 1

    init { loadVenues() }

    private fun send(event: VenueEvent) { viewModelScope.launch { eventChannel.send(event) } }

    fun loadVenues(silent: Boolean = false) {
        if (silent) venueRefreshing = true else venueLoading = true
        venueError = null
        viewModelScope.launch {
            try {
                venues = withContext(Dispatchers.IO) { api.fetchVenueList() }
            } catch (_: AuthExpiredException) {
                send(VenueEvent.AuthExpired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                venueError = e.message ?: "加载场馆列表失败"
            } finally {
                venueLoading = false
                venueRefreshing = false
            }
        }
    }

    fun openVenue(venue: VenueApi.Venue) {
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

    fun toggleSlot(slot: VenueApi.AreaSlot) {
        selectedSlots = if (slot in selectedSlots) selectedSlots - slot else selectedSlots + slot
    }

    fun loadSlots(silent: Boolean = false) {
        val venueId = selectedVenue?.id ?: return
        if (silent) slotsRefreshing = true else slotsLoading = true
        slotsError = null
        if (!silent) selectedSlots = emptySet()
        val date = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        viewModelScope.launch {
            try {
                availableSlots = withContext(Dispatchers.IO) { api.fetchAvailableSlots(venueId, date) }
            } catch (_: AuthExpiredException) {
                send(VenueEvent.AuthExpired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                slotsError = e.message ?: "加载时段失败"
            } finally {
                slotsLoading = false
                slotsRefreshing = false
            }
        }
    }

    /** 下单前先占位（prepareOrder），再拉验证码。 */
    fun startBooking() {
        val venue = selectedVenue ?: return
        showCaptcha = true
        val token = beginCaptcha()
        pendingOrder = null
        viewModelScope.launch {
            try {
                val order = withContext(Dispatchers.IO) { api.prepareOrder(venue.id, selectedSlots.toList()) }
                if (token != captchaToken || !showCaptcha) return@launch
                pendingOrder = order
                val data = withContext(Dispatchers.IO) { api.generateCaptcha(venue.id) }
                processCaptcha(data, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                if (token == captchaToken) { closeCaptcha(); send(VenueEvent.AuthExpired) }
            } catch (e: Exception) {
                if (token == captchaToken) {
                    Log.e(TAG, "startBooking failed", e)
                    captchaError = e.message ?: "获取验证码失败"
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
                val data = withContext(Dispatchers.IO) { api.generateCaptcha(venue.id) }
                processCaptcha(data, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                if (token == captchaToken) { closeCaptcha(); send(VenueEvent.AuthExpired) }
            } catch (e: Exception) {
                if (token == captchaToken) captchaError = e.message ?: "获取验证码失败"
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
    private suspend fun processCaptcha(data: VenueApi.CaptchaData, token: Int) {
        if (token != captchaToken || !showCaptcha) return
        captchaData = data
        captchaNotice = null
        if (!autoSolveCaptcha()) return
        captchaAutoSolving = true
        val solved = try {
            withContext(Dispatchers.Default) { VenueCaptchaSolver.solve(data) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "automatic captcha solving failed", e)
            null
        }
        if (token != captchaToken || !showCaptcha) return
        captchaAutoSolving = false
        if (solved == null) captchaNotice = "自动识别未通过，请手动滑动滑块"
        else submitBooking(solved.sliderResult)
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
                withContext(Dispatchers.IO) {
                    api.submitBooking(serviceid = venue.id, pendingOrder = order, captchaId = captcha.id, sliderTrackJson = slider.toJson())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VenueApi.BookingResult(false, message = e.message ?: "预订失败")
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
                val result = withContext(Dispatchers.IO) { api.fetchOrders(page = page, pageSize = 20) }
                orders = if (reset) result.orders else (orders + result.orders).distinctBy { it.orderId }
                nextOrderPage = result.page + 1
                ordersHasMore = result.hasMore
            } catch (_: AuthExpiredException) {
                send(VenueEvent.AuthExpired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ordersError = e.message ?: "加载订单失败"
            } finally {
                if (reset) ordersLoading = false else ordersLoadingMore = false
            }
        }
    }

    fun loadOrdersOnce() {
        if (orders.isEmpty() && !ordersLoading) loadOrders(reset = true)
    }

    fun cancelOrder(order: VenueApi.OrderInfo) {
        if (orderActionLoading) return
        orderActionLoading = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.cancelOrder(order.orderId) }
                send(VenueEvent.Message(result.message))
                if (result.success) loadOrders(reset = true)
            } catch (_: AuthExpiredException) {
                send(VenueEvent.AuthExpired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(VenueEvent.Message(e.message ?: "取消订单失败"))
            } finally {
                orderActionLoading = false
            }
        }
    }

    private companion object {
        const val TAG = "VenueViewModel"
    }
}
