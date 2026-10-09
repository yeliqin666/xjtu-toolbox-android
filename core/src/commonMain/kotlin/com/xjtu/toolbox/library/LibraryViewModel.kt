package com.xjtu.toolbox.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.platform.keyValueStore
import kotlin.concurrent.Volatile
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

/**
 * 图书馆那几项本地偏好所在的存储：Android 上就是搬迁前那个 `SharedPreferences` 文件
 *（文件名、键名逐字未动 ⇒ 老用户的收藏、上次看的校区、视图模式都还在）。
 *
 * 收藏也是这个文件里的一个键，但它现在归共享的 [LibraryFavorites] 管（以前 `KeyValueStore`
 * 没有集合那一档，所以推给了实现方；现在有了）。
 */
private const val PREF_NAME = "library_favorites"
private const val KEY_CAMPUS = "library_campus"

/** 切去别的校区时记下原校区：进程被杀走不到 onCleared，下次进页面补切回去。 */
private const val KEY_PENDING_HOME = "pending_home_campus"
private const val KEY_VIEW_MODE = "seat_view_mode"
internal const val VIEW_LIST = "列表"
internal const val VIEW_PLAN = "平面图"

/**
 * 别处（屁岱的座位卡片、首页扫桌面二维码）想让图书馆页一打开就定位到某个区域。放一次、取一次。
 * 带 [Target.seatId] 时，区域加载完会弹出这个座位的预约确认（扫码的场景）。
 *
 * 这是**进程内的一个信箱**，不是取数：`:app` 的几处宿主（首页、屁岱、扫码）往里放，
 * 共享的 ViewModel 取。Web 端没有任何人放 ⇒ 永远取不到 null 以外的东西。
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
 *
 * 从 `:app` 搬进 `:core` 时**编排逻辑一行未改**，只换了四处「住址」：
 *
 *  - 取数从 `LibraryApi(site)` 换成端口 [LibrarySource]（读那几路 + 写那三路 + 收藏，见那个接口的 KDoc）——
 *    屏不再认识 `SiteSession`，也不再自己挑 `Dispatchers.IO`（实现方包）；
 *  - `android.util.Log` 那几行本地排障日志删掉（`:core` 没有 `Log`，它不参与任何可观测行为）；
 *  - 会话失效由 `:core` 的标记接口 [SessionExpiredFailure] 认领（`:app` 的 `AuthExpiredException`
 *    实现了它）——`catch` 抓不了接口，所以先抓 `Exception` 再判，与场馆/评教/成绩同一条缝；
 *  - 离开页面时「把校区切回去」那一枪由 [LibrarySource.restoreCampus] 自己发（它要一个不随页面取消的作用域）。
 *
 * 两处 `Dispatchers.IO` 换成 `Dispatchers.Default`（[Dispatchers.IO] 三端接口里没有）：一处是
 * 解码平面图（纯 CPU，只要不在主线程即可），一处是并行拉座位；真正的阻塞 IO 全在实现方那侧
 * （`AppLibrarySource` 自己包 IO），语义没变。
 *
 * [viewMode] 的初值与「平面图」这一档都跟着 [LibrarySource.hasSeatPlan]：没有布局/底图端点的端
 * （Web）落到列表，也不会去请求那些拿不到的数据。
 */
internal class LibraryViewModel(private val source: LibrarySource) : ViewModel() {
    private val prefs = keyValueStore(PREF_NAME)
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
    /** 成功查到过「我的预约」；在此之前 [myBooking] 为 null 只代表还不知道，不能当成没有预约往外发。 */
    var myBookingKnown by mutableStateOf(false); private set

    /** 最近一次查「我的预约」失败。 */
    var myBookingFailed by mutableStateOf(false); private set

    /** 查询失败时保留上一次的结果。 */
    private suspend fun loadMyBooking() {
        runCatching { source.myBooking().getOrThrow() }
            .onSuccess { myBooking = it; myBookingKnown = true; myBookingFailed = false }
            .onFailure { myBookingFailed = true }
    }
    var isLoadingBooking by mutableStateOf(false); private set

    /**
     * 收藏。同步读一次（搬迁前就是在构造时同步读 `SharedPreferences.getStringSet` 的那一份），
     * 之后由 [toggleFavorite] 自己维护 —— 落盘也在共享层（[LibraryFavorites]），不再是实现方的事。
     */
    var favorites by mutableStateOf(LibraryFavorites.all()); private set

    /** 首屏先按上次用的校区画，进页面后以账号实际的 rplace 为准。 */
    var campus by mutableStateOf(LibraryCampus.byId(prefs.getString(KEY_CAMPUS)) ?: LibraryCampus.DEFAULT); private set
    var campusSwitching by mutableStateOf(false); private set
    /** 进页面时账号所在的校区；null 表示没读到，不做切回。 */
    var homeCampus by mutableStateOf<LibraryCampus?>(null); private set

    var viewMode by mutableStateOf(
        if (!source.hasSeatPlan) VIEW_LIST
        // 上次存的是「平面图」但这一端没有平面图 ⇒ 落回列表，不去请求拿不到的数据
        else prefs.getString(KEY_VIEW_MODE)?.takeIf { it == VIEW_LIST || it == VIEW_PLAN } ?: VIEW_PLAN
    )
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
    /** 初值 true：整层图没出结果前不知道要不要给区域标签，先不给，免得闪一下。 */
    var floorPlanLoading by mutableStateOf(true); private set
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
            // 页面作用域这时已经取消了，这一枪由实现方在页面之外的作用域里打完（见 LibrarySource.restoreCampus）
            source.restoreCampus(home) { savePendingHome(null) }
            savePreferredCampus(home)
        }
    }

    private fun savePreferredCampus(value: LibraryCampus) = prefs.putString(KEY_CAMPUS, value.id)

    private fun savePendingHome(value: LibraryCampus?) = prefs.putString(KEY_PENDING_HOME, value?.id)

    private fun applyCampus(value: LibraryCampus) {
        campus = value
        selectedFloorCode = value.floorCodes.first()
    }

    private suspend fun authExpired() = eventChannel.send(LibraryEvent.AuthExpired)

    /** 认账号当前校区 → 拉第一层的区域 → 拉预约信息。 */
    private fun bootstrap() = viewModelScope.launch {
        var actual = runCatching { source.campus() }.getOrNull()
        // 上次看别的校区时进程被杀，没来得及切回：先补上
        val pending = LibraryCampus.byId(prefs.getString(KEY_PENDING_HOME))
        if (pending != null && actual != null && actual != pending) {
            if (runCatching { source.switchCampus(pending) }.getOrDefault(false)) actual = pending
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
        loadMyBooking()
    }

    /** 从屁岱的座位卡片或扫码进来：定位到那个区域，用平面图看。 */
    private suspend fun focus(focus: LibraryFocus.Target, actual: LibraryCampus?) {
        val target = LibraryCampus.byId(focus.campusId)
        if (target != null && actual != null && target != actual) {
            if (runCatching { source.switchCampus(target) }.getOrDefault(false)) {
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
                    val status = runCatching { source.seatAvailability(qr) }.getOrNull()
                    scanSeat = scanSeat?.takeIf { it.qr == qr }?.copy(status = status, checking = false)
                }
            }
        }
        val floor = runCatching { source.warmCampusAreas(campus); source.floorOfArea(focus.areaCode) }.getOrNull()
            ?: LibraryQrArea.byCode(focus.areaCode)?.floorCode?.takeIf { it in campus.floorCodes }
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
        viewModelScope.launch(Dispatchers.Default) { runCatching { source.warmCampusAreas(target) } }
    }

    fun changeViewMode(mode: String) {
        if (mode == viewMode) return
        // 本端没有平面图这一档（见 LibrarySource.hasSeatPlan）⇒ 不接受切过去，也不去拉拿不到的数据
        if (mode == VIEW_PLAN && !source.hasSeatPlan) return
        viewMode = mode
        prefs.putString(KEY_VIEW_MODE, mode)
        if (mode == VIEW_PLAN) refreshFloorPlan()
        // 另一种视图的数据这段时间没刷新过，切过去重拉
        if (selectedAreaCode.isNotEmpty()) loadArea(selectedAreaCode, force = true)
    }

    fun selectArea(code: String) {
        if (code == selectedAreaCode) return
        selectedAreaCode = code
        if (code.isNotEmpty()) loadArea(code)
    }

    /** 平面图的 qseatuist 自带座位状态，平面图模式下不再另查 qseat。 */
    private fun loadArea(code: String, force: Boolean = false) {
        if (viewMode == VIEW_PLAN) {
            isLoading = false
            loadPlan(code, force)
        } else {
            loadSeatsFor(code, force)
        }
    }

    fun toggleFavorite(seatId: String) {
        // 落盘那份说了算：与搬迁前「内存先变、随后写回读出来的那一份」同一个次序
        favorites = LibraryFavorites.toggle(seatId)
    }

    private fun loadSeatsFor(areaCode: String, force: Boolean = false) {
        if (!force && lastLoadedAreaCode == areaCode) return
        lastLoadedAreaCode = areaCode
        val generation = ++seatGeneration
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val result = source.seats(areaCode)
                if (generation != seatGeneration) return@launch
                applySeats(result, clearOnError = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpired()
                } else if (generation == seatGeneration) {
                    seats = emptyList()
                    errorMessage = FriendlyError.of(e, "加载")
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
        // 本端没有座位布局/底图端点（见 LibrarySource.hasSeatPlan）⇒ 不去请求拿不到的数据
        if (!source.hasSeatPlan) return
        if (!force && planAreaCode == areaCode && planLayout != null && planImages != null) return
        val gen = ++planGeneration
        // 换区域时旧图留着直到新图到，不闪加载页；加载中禁止预约，免得在旧图上点到别区的座位
        planAreaCode = areaCode
        planLoading = true
        planError = null
        viewModelScope.launch {
            try {
                // 先要底图和座位位置就能画，四张状态图随后补：一起并发拉会占满 WebVPN 的连接，
                // 紧接着点「预约」要排在图片后面。
                // Default（不是 IO）：这一段里既有网络也有**解码位图**这种纯 CPU 活，
                // 只要不在主线程就行；真正的阻塞 IO 由实现方包在自己的调度器上。
                val (layout, images) = withContext(Dispatchers.Default) {
                    coroutineScope {
                        val layoutD = async { source.seatLayout(areaCode) }
                        val images = planImageCache[areaCode] ?: run {
                            val base = source.planBase(areaCode) ?: throw RuntimeException("这个区域没有平面图")
                            decodePlanImages(base, emptyMap()) ?: throw RuntimeException("平面图解码失败")
                        }
                        layoutD.await() to images
                    }
                }
                if (gen != planGeneration) return@launch
                cachePlan(areaCode, images)
                planLayout = layout
                planImages = images
                areaStatsMap = source.areaStats()
                if (layout.seats.isEmpty()) planError = "这个区域的平面图上没有座位"
                planLoading = false
                if (images.tiles.isEmpty()) {
                    val tiles = source.planTiles(areaCode)
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
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpired()
                } else if (gen == planGeneration) {
                    planLayout = null; planImages = null
                    planError = FriendlyError.of(e, "加载平面图")
                }
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
        // 同上：没有布局/底图端点的端不发这一枪
        if (!source.hasSeatPlan) return
        val code = selectedFloorCode
        if (floorPlanFor == code && floorPlan != null) return
        floorPlanJob?.cancel()
        floorPlan = null
        floorPlanFor = code
        floorPlanLoading = true
        floorPlanJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val layout = source.seatLayout(code)
                    val bytes = source.planBase(code)
                    val img = bytes?.let { decodePlanImages(it, emptyMap()) }
                    if (img == null || layout.seats.isEmpty()) null else layout to img
                }.getOrNull()
            }
            if (floorPlanFor == code) { floorPlan = result; floorPlanLoading = false }
        }
    }

    fun reload(force: Boolean = true) {
        val code = selectedAreaCode
        if (code.isEmpty() || floorAreas.isEmpty()) { loadFloor(selectedFloorCode); return }
        loadArea(code, force)
    }

    /** 拉一层的区域列表并选中第一个可用区域；换校区、换楼层都走这里。 */
    fun loadFloor(floorCode: String, preferArea: String? = null) {
        selectedFloorCode = floorCode
        isLoading = true
        refreshFloorPlan()
        viewModelScope.launch {
            try {
                // 刚切过校区时学校偶尔还按旧校区回空的，隔一下再问一次
                val result = source.areas(floorCode).ifEmpty { delay(800); source.areas(floorCode) }
                // 还是空：多半是页面上的校区和账号实际校区对不上了（qspace 只按账号校区回），跟过去
                if (result.isEmpty()) {
                    val actual = runCatching { source.campus() }.getOrNull()
                    if (actual != null && actual != campus) {
                        applyCampus(actual)
                        savePreferredCampus(actual)
                        warmCampus()
                        loadFloor(actual.floorCodes.first())
                        return@launch
                    }
                }
                floorAreas = result
                errorMessage = if (result.isEmpty()) "这一层没有可选区域" else null
                val code = preferArea?.takeIf { it in result }
                    ?: result.keys.firstOrNull { source.areaStats()[it]?.isOpen != false }
                    ?: result.keys.firstOrNull().orEmpty()
                selectedAreaCode = code
                if (code.isEmpty()) {
                    seats = emptyList()
                    isLoading = false
                } else {
                    // 换走一层再换回来时区域码相同，也要强制拉一次
                    loadArea(code, force = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredFailure) {
                    authExpired()
                } else {
                    floorAreas = emptyMap()
                    seats = emptyList()
                    errorMessage = FriendlyError.of(e, "加载楼层信息")
                }
                isLoading = false
            }
        }
    }

    /** 换校区会改账号资料里的 rplace，所以只在用户点校区标签时做。 */
    fun switchCampus(target: LibraryCampus) {
        if (target == campus || campusSwitching) return
        campusSwitching = true
        viewModelScope.launch {
            if (runCatching { source.switchCampus(target) }.getOrDefault(false)) {
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
            loadMyBooking()
            isLoadingBooking = false
        }
    }

    /** 预约 / 换座 / 取消后只刷新一轮：座位（平面图模式刷平面图）+ 我的预约并行。 */
    private suspend fun refreshAfterBooking() = coroutineScope {
        val areaCode = selectedAreaCode.takeIf { it.isNotEmpty() }
        val plan = viewMode == VIEW_PLAN
        if (plan && areaCode != null) loadPlan(areaCode, force = true)
        val seatsDeferred = areaCode?.takeIf { !plan }?.let {
            lastLoadedAreaCode = it
            async(Dispatchers.Default) { source.seats(it) }
        }
        val bookingDeferred = async { loadMyBooking() }
        seatsDeferred?.await()?.let { applySeats(it, clearOnError = false) }
        bookingDeferred.await()
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
        ?: guessAreaCode(seatId)

    fun book(seatId: String, areaOverride: String? = null) = seatAction(seatId, areaOverride, "预约") { area ->
        source.bookSeat(seatId, area, autoSwap = true)
    }

    /** 已有预约时直接换座（/updateseat/），不走 /seat/ 的检测。 */
    fun swap(seatId: String, areaOverride: String? = null) = seatAction(seatId, areaOverride, "换座") { area ->
        source.swapSeat(seatId, area)
    }

    private fun seatAction(seatId: String, areaOverride: String?, label: String, call: suspend (String) -> BookResult) {
        val area = areaFor(seatId, areaOverride) ?: run { bookingResult = BookResult(false, "无法确定区域"); return }
        isBooking = true
        bookingResult = null
        viewModelScope.launch {
            try {
                val result = call(area)
                bookingResult = result
                adoptCampusIfBooked(result)
                // 结果一出来就放开按钮，刷新放后面
                isBooking = false
                if (result.success) delay(400)
                refreshAfterBooking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bookingResult = BookResult(false, FriendlyError.of(e, label))
            }
            isBooking = false
        }
    }

    fun executeAction(label: String, url: String) {
        isBooking = true
        viewModelScope.launch {
            try {
                val result = source.action(url)
                bookingResult = BookResult(result.success, "$label: ${result.message}")
                refreshAfterBooking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bookingResult = BookResult(false, FriendlyError.of(e, label))
            }
            isBooking = false
        }
    }
}
