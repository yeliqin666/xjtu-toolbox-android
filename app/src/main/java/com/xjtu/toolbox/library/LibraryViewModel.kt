package com.xjtu.toolbox.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ══════ 本地偏好 ══════

private const val PREF_NAME = "library_favorites"
private const val KEY_FAVORITES = "favorite_seats"
private const val KEY_CAMPUS = "library_campus"
/** 切去别的校区时记下原校区：进程被杀走不到 onCleared，下次进页面补切回去。 */
private const val KEY_PENDING_HOME = "pending_home_campus"
private const val KEY_VIEW_MODE = "seat_view_mode"
internal const val VIEW_LIST = "列表"
internal const val VIEW_PLAN = "平面图"

private fun Context.libraryPrefs() = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

/**
 * 别处（屁岱的座位卡片、首页扫桌面二维码）想让图书馆页一打开就定位到某个区域。放一次、取一次。
 * 带 [Target.seatId] 时，区域加载完会弹出这个座位的预约确认（扫码的场景）。
 */
object LibraryFocus {
    data class Target(val campusId: String, val areaCode: String, val seatId: String? = null)

    @Volatile private var pending: Target? = null

    fun request(target: Target) { pending = target }

    fun take(): Target? = pending.also { pending = null }
}

internal sealed interface LibraryEvent {
    data object AuthExpired : LibraryEvent
}

/**
 * 图书馆座位：校区 → 楼层 → 区域 → 座位（列表 / 平面图），预约、换座、预约操作。
 *
 * 看别的校区要改账号资料里的 rplace；离开页面（[onCleared]）时切回进页面时的校区，
 * 在别的校区约成了座位就改认那个校区。
 */
internal class LibraryViewModel(context: Context, private val site: SiteSession) : ViewModel() {
    private val context = context.applicationContext
    private val prefs = this.context.libraryPrefs()
    val api = LibraryApi(site)
    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    var seats by mutableStateOf<List<SeatInfo>>(emptyList()); private set
    var areaStatsMap by mutableStateOf<Map<String, AreaStats>>(emptyMap()); private set
    // 初值 true：进页面先要问一次账号所在校区，免得先闪一下「暂无座位」
    var isLoading by mutableStateOf(true); private set
    var errorMessage by mutableStateOf<String?>(null)
    private var seatGeneration = 0
    private var lastLoadedAreaCode: String? = null

    var bookingResult by mutableStateOf<BookResult?>(null)
    var isBooking by mutableStateOf(false); private set
    /** 扫桌面二维码进来要约的座位；校区一定下来就弹确认。 */
    var scanSeat by mutableStateOf<ScanSeatPrompt?>(null)
    var myBooking by mutableStateOf<MyBookingInfo?>(null); private set
    var isLoadingBooking by mutableStateOf(false); private set

    var favorites by mutableStateOf(prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()); private set

    /** 首屏先按上次用的校区画，进页面后以账号实际的 rplace 为准。 */
    var campus by mutableStateOf(LibraryCampus.byId(prefs.getString(KEY_CAMPUS, null)) ?: LibraryCampus.DEFAULT); private set
    var campusSwitching by mutableStateOf(false); private set
    /** 进页面时账号所在的校区；null 表示没读到，不做切回。 */
    var homeCampus by mutableStateOf<LibraryCampus?>(null); private set

    var viewMode by mutableStateOf(prefs.getString(KEY_VIEW_MODE, null)?.takeIf { it == VIEW_LIST || it == VIEW_PLAN } ?: VIEW_PLAN)
        private set
    var planLayout by mutableStateOf<SeatLayout?>(null); private set
    var planImages by mutableStateOf<PlanImages?>(null); private set
    var planLoading by mutableStateOf(false); private set
    var planError by mutableStateOf<String?>(null); private set
    /** 平面图图片按区域缓存最近两个：来回切两个区域不重新下图。 */
    private val planImageCache = LinkedHashMap<String, PlanImages>()
    private var planAreaCode: String? = null
    private var planGeneration = 0
    /** 进页面那一轮定校区做完了没有；之前别发按校区走的请求。 */
    private var bootstrapped = false
    var floorPlan by mutableStateOf<Pair<SeatLayout, PlanImages>?>(null); private set
    private var floorPlanFor: String? = null
    private var floorPlanJob: Job? = null

    var selectedFloorCode by mutableStateOf(campus.floorCodes.first()); private set
    /** 当前楼层的「区域码 → 中文名」，顺序即学校给的顺序。 */
    var floorAreas by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var selectedAreaCode by mutableStateOf(""); private set

    init { bootstrap() }

    override fun onCleared() {
        val home = homeCampus
        if (home != null && campus != home) {
            LibraryApi.restoreScope.launch {
                if (runCatching { api.switchCampus(home) }.getOrDefault(false)) savePendingHome(null)
            }
            savePreferredCampus(home)
        }
    }

    private fun savePreferredCampus(value: LibraryCampus) = prefs.edit().putString(KEY_CAMPUS, value.id).apply()
    private fun savePendingHome(value: LibraryCampus?) = prefs.edit().apply {
        if (value == null) remove(KEY_PENDING_HOME) else putString(KEY_PENDING_HOME, value.id)
    }.apply()

    private fun applyCampus(value: LibraryCampus) {
        campus = value
        selectedFloorCode = value.floorCodes.first()
    }

    private suspend fun authExpired() = eventChannel.send(LibraryEvent.AuthExpired)

    /** 认账号当前校区 → 拉第一层的区域 → 拉预约信息。 */
    private fun bootstrap() = viewModelScope.launch {
        var actual = withContext(Dispatchers.IO) { runCatching { api.getCurrentCampus() }.getOrNull() }
        // 上次看别的校区时进程被杀，没来得及切回：先补上
        val pending = LibraryCampus.byId(prefs.getString(KEY_PENDING_HOME, null))
        if (pending != null && actual != null && actual != pending) {
            if (withContext(Dispatchers.IO) { runCatching { api.switchCampus(pending) }.getOrDefault(false) }) actual = pending
        }
        if (pending != null && (actual == pending || actual == null)) savePendingHome(null)
        homeCampus = actual
        if (actual != null && actual != campus) {
            applyCampus(actual)
            savePreferredCampus(actual)
        }
        val focus = LibraryFocus.take()
        if (focus != null) focus(focus, actual) else loadFloor(campus.floorCodes.first())
        bootstrapped = true
        warmCampus()
        refreshFloorPlan()
        myBooking = withContext(Dispatchers.IO) { runCatching { api.getMyBooking() }.getOrNull() }
    }

    /** 从屁岱的座位卡片或扫码进来：定位到那个区域，用平面图看。 */
    private suspend fun focus(focus: LibraryFocus.Target, actual: LibraryCampus?) {
        val target = LibraryCampus.byId(focus.campusId)
        if (target != null && actual != null && target != actual) {
            if (withContext(Dispatchers.IO) { runCatching { api.switchCampus(target) }.getOrDefault(false) }) {
                applyCampus(target)
                savePendingHome(actual)
            }
        }
        // 扫码：预约只认账号当前校区，校区到位就能约；座位空不空另查一次，查到前按钮照样能点
        focus.seatId?.let { seatId ->
            if (target != null && target != campus) {
                bookingResult = BookResult(false, "没能切换到${target.displayName}，请在上方校区标签里手动切换后再扫码")
            } else {
                val qr = LibrarySeatQr(seatId, focus.areaCode)
                scanSeat = ScanSeatPrompt(qr)
                viewModelScope.launch {
                    val status = withContext(Dispatchers.IO) { runCatching { LibrarySeatAvailability.fetch(site.client, qr) }.getOrNull() }
                    scanSeat = scanSeat?.takeIf { it.qr == qr }?.copy(status = status, checking = false)
                }
            }
        }
        val floor = withContext(Dispatchers.IO) {
            runCatching { api.warmCampusAreas(campus); api.floorOfArea(focus.areaCode) }.getOrNull()
        } ?: LibraryQrArea.byCode(focus.areaCode)?.floorCode?.takeIf { it in campus.floorCodes }
        if (floor != null) {
            changeViewMode(VIEW_PLAN)
            loadFloor(floor, preferArea = focus.areaCode)
        } else {
            loadFloor(campus.floorCodes.first())
        }
    }

    /** 后台把本校区所有楼层的区域名学一遍，用来判断已有预约是不是在别的校区。 */
    private fun warmCampus() {
        val target = campus
        viewModelScope.launch(Dispatchers.IO) { runCatching { api.warmCampusAreas(target) } }
    }

    fun changeViewMode(mode: String) {
        if (mode == viewMode) return
        viewMode = mode
        prefs.edit().putString(KEY_VIEW_MODE, mode).apply()
        if (mode == VIEW_PLAN) {
            if (selectedAreaCode.isNotEmpty()) loadPlan(selectedAreaCode)
            refreshFloorPlan()
        }
    }

    fun selectArea(code: String) {
        if (code == selectedAreaCode) return
        selectedAreaCode = code
        if (code.isEmpty()) return
        loadSeatsFor(code)
        if (viewMode == VIEW_PLAN) loadPlan(code)
    }

    fun toggleFavorite(seatId: String) {
        favorites = if (seatId in favorites) favorites - seatId else favorites + seatId
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
    }

    private fun loadSeatsFor(areaCode: String, force: Boolean = false) {
        if (!force && lastLoadedAreaCode == areaCode) return
        lastLoadedAreaCode = areaCode
        val generation = ++seatGeneration
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.getSeats(areaCode) }
                if (generation != seatGeneration) return@launch
                applySeats(result, clearOnError = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpired()
            } catch (e: Exception) {
                if (generation == seatGeneration) {
                    seats = emptyList()
                    errorMessage = "加载失败: ${e.message}"
                }
            }
            if (generation == seatGeneration) isLoading = false
        }
    }

    private fun applySeats(result: SeatResult, clearOnError: Boolean) {
        when (result) {
            is SeatResult.Success -> { seats = result.seats; areaStatsMap = result.areaStatsMap; errorMessage = null }
            is SeatResult.AuthError -> { if (clearOnError) seats = emptyList(); errorMessage = result.message }
            is SeatResult.Error -> { if (clearOnError) seats = emptyList(); errorMessage = result.message }
        }
    }

    /** 座位位置 / 状态每次都拉（状态会变），图片按区域缓存；状态图缺了只是那几种座位改用色块画。 */
    fun loadPlan(areaCode: String, force: Boolean = false) {
        if (areaCode.isEmpty()) return
        if (!force && planAreaCode == areaCode && planLayout != null && planImages != null) return
        val gen = ++planGeneration
        if (planAreaCode != areaCode) { planLayout = null; planImages = null }
        planAreaCode = areaCode
        planLoading = true
        planError = null
        viewModelScope.launch {
            try {
                // 先要底图和座位位置就能画，四张状态图随后补：一起并发拉会占满 WebVPN 的连接，
                // 紧接着点「预约」要排在图片后面
                val names = LibraryPages.planImageNames(areaCode)
                val (layout, images) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val layoutD = async { api.getSeatLayout(areaCode) }
                        val images = planImageCache[areaCode] ?: run {
                            val base = PlanImageDiskCache.get(context, names.getValue(null)) { api.getPlanImage(it) }
                                ?: throw RuntimeException("这个区域没有平面图")
                            decodePlanImages(base, emptyMap()) ?: throw RuntimeException("平面图解码失败")
                        }
                        layoutD.await() to images
                    }
                }
                if (gen != planGeneration) return@launch
                cachePlan(areaCode, images)
                planLayout = layout
                planImages = images
                if (layout.seats.isEmpty()) planError = "这个区域的平面图上没有座位"
                planLoading = false
                if (images.tiles.isEmpty()) {
                    val tiles = withContext(Dispatchers.IO) {
                        names.entries.filter { it.key != null }.mapNotNull { (status, name) ->
                            PlanImageDiskCache.get(context, name) { api.getPlanImage(it) }?.let { status!! to it }
                        }.toMap()
                    }
                    if (tiles.isNotEmpty()) {
                        val withTiles = withContext(Dispatchers.Default) { images.withTiles(tiles) }
                        if (gen == planGeneration && planImages === images) {
                            planImages = withTiles
                            planImageCache[areaCode] = withTiles
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpired()
            } catch (e: Exception) {
                if (gen == planGeneration) planError = e.message ?: "平面图加载失败"
            }
            if (gen == planGeneration) planLoading = false
        }
    }

    private fun cachePlan(areaCode: String, images: PlanImages) {
        planImageCache.remove(areaCode)
        planImageCache[areaCode] = images
        while (planImageCache.size > 2) planImageCache.remove(planImageCache.keys.first())
    }

    /** 整层的平面图（区域矩形 + 楼层底图），平面图模式下换楼层时拉一次；拿不到就不显示。 */
    private fun refreshFloorPlan() {
        if (viewMode != VIEW_PLAN || !bootstrapped) return
        val code = selectedFloorCode
        if (floorPlanFor == code && floorPlan != null) return
        floorPlanJob?.cancel()
        floorPlan = null
        floorPlanFor = code
        floorPlanJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val layout = api.getSeatLayout(code)
                    val bytes = PlanImageDiskCache.get(context, "$code.jpg") { api.getPlanImage(it) }
                    val img = bytes?.let { decodePlanImages(it, emptyMap()) }
                    if (img == null || layout.seats.isEmpty()) null else layout to img
                }.getOrNull()
            }
            if (floorPlanFor == code) floorPlan = result
        }
    }

    fun reload(force: Boolean = true) {
        val code = selectedAreaCode
        if (code.isEmpty() || floorAreas.isEmpty()) { loadFloor(selectedFloorCode); return }
        loadSeatsFor(code, force)
        if (viewMode == VIEW_PLAN) loadPlan(code, force)
    }

    /** 拉一层的区域列表并选中第一个可用区域；换校区、换楼层都走这里。 */
    fun loadFloor(floorCode: String, preferArea: String? = null) {
        selectedFloorCode = floorCode
        isLoading = true
        refreshFloorPlan()
        viewModelScope.launch {
            try {
                // 刚切过校区时学校偶尔还按旧校区回空的，隔一下再问一次
                val result = withContext(Dispatchers.IO) {
                    api.getFloorAreas(floorCode).ifEmpty { delay(800); api.getFloorAreas(floorCode) }
                }
                floorAreas = result
                errorMessage = if (result.isEmpty()) "这一层没有可选区域" else null
                val code = preferArea?.takeIf { it in result }
                    ?: result.keys.firstOrNull { api.cachedAreaStats[it]?.isOpen != false }
                    ?: result.keys.firstOrNull().orEmpty()
                selectedAreaCode = code
                if (code.isEmpty()) {
                    seats = emptyList()
                    isLoading = false
                } else {
                    // 换走一层再换回来时区域码相同，也要强制拉一次
                    loadSeatsFor(code, force = true)
                    if (viewMode == VIEW_PLAN) loadPlan(code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AuthExpiredException) {
                authExpired()
                isLoading = false
            } catch (e: Exception) {
                floorAreas = emptyMap()
                seats = emptyList()
                errorMessage = "楼层信息加载失败: ${e.message}"
                isLoading = false
            }
        }
    }

    /** 换校区会改账号资料里的 rplace，所以只在用户点校区标签时做。 */
    fun switchCampus(target: LibraryCampus) {
        if (target == campus || campusSwitching) return
        campusSwitching = true
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { runCatching { api.switchCampus(target) }.getOrDefault(false) }) {
                applyCampus(target)
                savePreferredCampus(target)
                homeCampus?.let { home -> savePendingHome(if (target == home) null else home) }
                floorAreas = emptyMap()
                selectedAreaCode = ""
                seats = emptyList()
                areaStatsMap = emptyMap()
                lastLoadedAreaCode = null
                planLayout = null; planImages = null; planAreaCode = null
                warmCampus()
                loadFloor(target.floorCodes.first())
            } else {
                errorMessage = "切换到" + target.displayName + "失败，请稍后重试"
            }
            campusSwitching = false
        }
    }

    fun refreshMyBooking() {
        isLoadingBooking = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { api.getMyBooking() } }.onSuccess { myBooking = it }
            isLoadingBooking = false
        }
    }

    /** 预约 / 换座 / 取消后只刷新一轮：座位 + 我的预约并行。 */
    private suspend fun refreshAfterBooking() = coroutineScope {
        val areaCode = selectedAreaCode.takeIf { it.isNotEmpty() }
        val seatsDeferred = areaCode?.let {
            lastLoadedAreaCode = it
            async(Dispatchers.IO) { api.getSeats(it) }
        }
        val bookingDeferred = async(Dispatchers.IO) { runCatching { api.getMyBooking() }.getOrNull() }
        seatsDeferred?.await()?.let { applySeats(it, clearOnError = false) }
        myBooking = bookingDeferred.await()
        if (viewMode == VIEW_PLAN && areaCode != null) loadPlan(areaCode, force = true)
    }

    /** 在别的校区约成了：账号就留在这个校区，离开页面时不再切回。 */
    private fun adoptCampusIfBooked(result: BookResult) {
        if (result.success) {
            homeCampus = campus
            savePendingHome(null)
        }
    }

    private fun areaFor(seatId: String, areaOverride: String?): String? = areaOverride
        ?: selectedAreaCode.takeIf { it.isNotEmpty() }
        ?: LibraryApi.guessAreaCode(seatId)

    fun book(seatId: String, areaOverride: String? = null) = seatAction(seatId, areaOverride, "预约") { area ->
        api.bookSeat(seatId, area)
    }

    /** 已有预约时直接换座（/updateseat/），不走 /seat/ 的检测。 */
    fun swap(seatId: String, areaOverride: String? = null) = seatAction(seatId, areaOverride, "换座") { area ->
        api.swapSeat(seatId, area)
    }

    private fun seatAction(seatId: String, areaOverride: String?, label: String, call: suspend (String) -> BookResult) {
        val area = areaFor(seatId, areaOverride) ?: run { bookingResult = BookResult(false, "无法确定区域"); return }
        isBooking = true
        bookingResult = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { call(area) }
                bookingResult = result
                adoptCampusIfBooked(result)
                // 结果一出来就放开按钮，刷新放后面
                isBooking = false
                if (result.success) delay(400)
                refreshAfterBooking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bookingResult = BookResult(false, "${label}异常: ${e.message}")
            }
            isBooking = false
        }
    }

    fun executeAction(label: String, url: String) {
        isBooking = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.executeAction(url) }
                bookingResult = BookResult(result.success, "$label: ${result.message}")
                refreshAfterBooking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bookingResult = BookResult(false, "$label 失败: ${e.message}")
            }
            isBooking = false
        }
    }
}
