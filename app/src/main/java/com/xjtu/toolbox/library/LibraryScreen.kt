package com.xjtu.toolbox.library

import com.xjtu.toolbox.ui.components.pressScale
import com.xjtu.toolbox.ui.components.enterOnce
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.*
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.layout.FlowRow
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

// ══════ 收藏座位 ══════

private const val PREF_NAME = "library_favorites"
private const val KEY_FAVORITES = "favorite_seats"

private fun loadFavorites(ctx: Context): Set<String> =
    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

private fun saveFavorites(ctx: Context, favs: Set<String>) =
    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putStringSet(KEY_FAVORITES, favs).apply()

// ══════ 校区偏好 ══════

private const val KEY_CAMPUS = "library_campus"

/**
 * 上次用的校区。仅作首屏的乐观展示——真正以账号里的 `rplace` 为准，
 * 进页面后会用 [LibraryApi.getCurrentCampus] 校一次。
 */
private fun loadPreferredCampus(ctx: Context): LibraryCampus =
    LibraryCampus.byId(
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_CAMPUS, null)
    ) ?: LibraryCampus.DEFAULT

private fun savePreferredCampus(ctx: Context, campus: LibraryCampus) {
    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_CAMPUS, campus.id).apply()
}

/**
 * 「离开页面要切回哪个校区」的落盘记录。
 *
 * 切回原校区原本只挂在页面销毁时做，可进程被杀（系统回收、覆盖安装、划掉后台）时根本走不到那一步，
 * 账号就停在了别的校区。所以切走时先记下来，下次进页面发现没切回就补上。
 */
private const val KEY_PENDING_HOME = "pending_home_campus"

private fun loadPendingHome(ctx: Context): LibraryCampus? =
    LibraryCampus.byId(ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PENDING_HOME, null))

private fun savePendingHome(ctx: Context, campus: LibraryCampus?) {
    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().apply {
        if (campus == null) remove(KEY_PENDING_HOME) else putString(KEY_PENDING_HOME, campus.id)
    }.apply()
}

/**
 * 别处（屁岱的座位卡片、首页扫桌面二维码）想让图书馆页一打开就定位到某个区域。放一次、取一次。
 * 路由不带参数，用一个进程内的单槽传过去；进程被杀就算了，本来也只是个便利。
 * 带 [Target.seatId] 时，区域加载完会弹出这个座位的预约确认（扫码的场景）。
 */
object LibraryFocus {
    data class Target(val campusId: String, val areaCode: String, val seatId: String? = null)

    @Volatile private var pending: Target? = null

    fun request(target: Target) { pending = target }

    fun take(): Target? = pending.also { pending = null }
}

// ══════ 列表 / 平面图 ══════

private const val KEY_VIEW_MODE = "seat_view_mode"
internal const val VIEW_LIST = "列表"
internal const val VIEW_PLAN = "平面图"

private fun loadViewMode(ctx: Context): String =
    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_VIEW_MODE, null)
        ?.takeIf { it == VIEW_LIST || it == VIEW_PLAN } ?: VIEW_PLAN

private fun saveViewMode(ctx: Context, mode: String) {
    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_VIEW_MODE, mode).apply()
}

// ══════ LibraryScreen ══════

@Composable
fun LibraryScreen(site: SiteSession, onBack: () -> Unit) {
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val api = remember(site) { LibraryApi(site) }
    val context = LocalContext.current

    // ── 首次使用提示 ──
    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("library_hint_shown", false)) }

    // 座位数据
    var seats by remember { mutableStateOf<List<SeatInfo>>(emptyList()) }
    var areaStatsMap by remember { mutableStateOf<Map<String, AreaStats>>(emptyMap()) }
    // 初值 true：进页面先要问一次账号所在校区（/modify），这段时间以前是 false，
    // 于是先闪一下「该区域暂无座位数据」再转圈。
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val seatLoadGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    // 预约
    var bookingResult by remember { mutableStateOf<BookResult?>(null) }
    var isBooking by remember { mutableStateOf(false) }
    var lastLoadedAreaCode by remember { mutableStateOf<String?>(null) }

    // 预约结果自动消失
    LaunchedEffect(bookingResult) {
        if (bookingResult != null) {
            val delayMs = if (bookingResult?.success == true) 4000L else 6000L
            kotlinx.coroutines.delay(delayMs)
            bookingResult = null
        }
    }

    // 确认对话框
    var confirmDialog by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    /** 扫桌面二维码进来要约的座位。校区一定下来就弹确认，不等这一层座位表。 */
    var scanSeat by remember { mutableStateOf<ScanSeatPrompt?>(null) }

    // 我的预约
    var myBooking by remember { mutableStateOf<MyBookingInfo?>(null) }

    // 预约状态一变就重排后台提醒。挂在这里而不是挂在预约/换座/中途离开各自的回调上：
    // 那几处最后都会刷新 myBooking，盯着结果比盯着动作少漏一条路径。
    LaunchedEffect(myBooking?.actionUrls?.keys, myBooking?.seatId) {
        com.xjtu.toolbox.notification.LibraryReminderScheduler.sync(context, myBooking)
    }

    // 收藏
    var favorites by remember { mutableStateOf(loadFavorites(context)) }

    // ── 校区/楼层/区域选择 ──
    //
    // 三级都是运行时定的：校区来自账号设置，楼层来自 [LibraryCampus]，
    // 区域来自 `/qspace` 现拉。原来这里挂的是兴庆那张写死的表，
    // 雁塔和创新港的区域码一个都对不上，两个校区的列表永远空着（issue #42）。
    var campus by remember { mutableStateOf(loadPreferredCampus(context)) }
    var campusSwitching by remember { mutableStateOf(false) }

    /**
     * 账号「本来」的校区：进页面时从 /modify 读到的那个。
     *
     * 看别的校区要改账号资料里的 rplace，离开页面时切回它——只是看一眼，不该把账号留在那边。
     * 在别的校区约成了座位就改认那个校区：预约绑在 rplace 上，切回去反而添乱。
     * 为 null 表示没读到，这种情况下不做切回。
     */
    var homeCampus by remember { mutableStateOf<LibraryCampus?>(null) }
    val latestCampus by rememberUpdatedState(campus)
    val latestHome by rememberUpdatedState(homeCampus)
    DisposableEffect(api) {
        onDispose {
            val home = latestHome
            if (home != null && latestCampus != home) {
                LibraryApi.restoreScope.launch {
                    if (runCatching { api.switchCampus(home) }.getOrDefault(false)) savePendingHome(context, null)
                }
                savePreferredCampus(context, home)
            }
        }
    }

    // ── 列表 / 平面图 ──
    var viewMode by rememberSaveable { mutableStateOf(loadViewMode(context)) }
    var planLayout by remember { mutableStateOf<SeatLayout?>(null) }
    var planImages by remember { mutableStateOf<PlanImages?>(null) }
    var planLoading by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf<String?>(null) }
    /** 平面图图片按区域缓存最近两个：来回切两个区域不重新下图，也不无限占内存。 */
    val planImageCache = remember { LinkedHashMap<String, PlanImages>() }
    var planAreaCode by remember { mutableStateOf<String?>(null) }
    /** 进页面那一轮定校区（读 rplace、必要时切回原校区）做完了没有。之前别发按校区走的请求。 */
    var bootstrapped by remember { mutableStateOf(false) }
    var floorPlan by remember { mutableStateOf<Pair<SeatLayout, PlanImages>?>(null) }
    var floorPlanFor by remember { mutableStateOf<String?>(null) }
    val planGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var selectedFloorCode by remember(campus) { mutableStateOf(campus.floorCodes.first()) }

    /** 当前楼层的「区域码 → 中文名」，顺序即学校给的顺序。 */
    var floorAreas by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedAreaCode by remember { mutableStateOf("") }
    val selectedArea = floorAreas[selectedAreaCode] ?: api.areaNameOf(selectedAreaCode)

    val floors = remember(campus) { campus.floorCodes.map { campus.floorLabel(it) } }

    /**
     * 可选区域。
     *
     * 只把**明确关闭**（scount 里有这个区域且 total=0）的滤掉。原来的写法是反过来的：
     * 只留明确开放的，于是统计还没到、或者学校压根没给这个区域统计时，列表是空的——
     * 那正是另外两个校区看到的样子。
     */
    val areaCodes = remember(floorAreas, areaStatsMap) {
        floorAreas.keys.filter { code -> areaStatsMap[code]?.isOpen != false }
    }

    // ── 加载座位（统一入口） ──
    fun loadSeatsFor(areaCode: String, force: Boolean = false) {
        if (!force && lastLoadedAreaCode == areaCode) return
        lastLoadedAreaCode = areaCode
        val generation = seatLoadGeneration.incrementAndGet()
        isLoading = true; errorMessage = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.getSeats(areaCode) }
                if (generation != seatLoadGeneration.get()) return@launch
                when (result) {
                    is SeatResult.Success -> { seats = result.seats; areaStatsMap = result.areaStatsMap; errorMessage = null }
                    is SeatResult.AuthError -> { seats = emptyList(); errorMessage = result.message }
                    is SeatResult.Error -> { seats = emptyList(); errorMessage = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LIBRARY, Routes.LIBRARY, onBack)
            }
            catch (e: Exception) {
                if (generation == seatLoadGeneration.get()) {
                    seats = emptyList()
                    errorMessage = "加载失败: ${e.message}"
                }
            }
            if (generation == seatLoadGeneration.get()) isLoading = false
        }
    }

    /**
     * 拉平面图：座位位置 / 状态每次都拉（状态会变），图片按区域缓存。
     * 底图拿不到就报错，状态图缺了只是那几种座位改用色块画。
     */
    fun loadPlan(areaCode: String, force: Boolean = false) {
        if (areaCode.isEmpty()) return
        if (!force && planAreaCode == areaCode && planLayout != null && planImages != null) return
        val gen = planGeneration.incrementAndGet()
        if (planAreaCode != areaCode) { planLayout = null; planImages = null }
        planAreaCode = areaCode
        planLoading = true
        planError = null
        scope.launch {
            try {
                // 先要底图和座位位置就能画；四张状态图随后一张张补，补齐前有人的座位用色块画。
                // 以前五张图和坐标一起并发拉，经 WebVPN 时把同一站点的连接占满，
                // 紧接着点「预约」要排在图片后面，预约就显得慢。图片还落盘缓存，同一区域只下一次。
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
                if (gen != planGeneration.get()) return@launch
                planImageCache.remove(areaCode)
                planImageCache[areaCode] = images
                while (planImageCache.size > 2) planImageCache.remove(planImageCache.keys.first())
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
                        if (gen == planGeneration.get() && planImages === images) {
                            planImages = withTiles
                            planImageCache[areaCode] = withTiles
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LIBRARY, Routes.LIBRARY, onBack)
            } catch (e: Exception) {
                if (gen == planGeneration.get()) planError = e.message ?: "平面图加载失败"
            }
            if (gen == planGeneration.get()) planLoading = false
        }
    }

    fun loadSeats(force: Boolean = false) {
        selectedAreaCode.takeIf { it.isNotEmpty() }?.let {
            loadSeatsFor(it, force)
            if (viewMode == VIEW_PLAN) loadPlan(it, force)
        }
    }

    /** 拉一层的区域列表并选中第一个可用区域。换校区、换楼层都走这里。 */
    fun loadFloor(floorCode: String, preferArea: String? = null) {
        selectedFloorCode = floorCode
        isLoading = true
        scope.launch {
            try {
                // 刚切过校区时学校偶尔还按旧校区回，给个空的；隔一下再问一次
                val result = withContext(Dispatchers.IO) {
                    api.getFloorAreas(floorCode).ifEmpty {
                        kotlinx.coroutines.delay(800)
                        api.getFloorAreas(floorCode)
                    }
                }
                floorAreas = result
                errorMessage = if (result.isEmpty()) "这一层没有可选区域" else null
                val code = preferArea?.takeIf { it in result }
                    ?: result.keys.firstOrNull { api.cachedAreaStats[it]?.isOpen != false }
                    ?: result.keys.firstOrNull().orEmpty()
                selectedAreaCode = code
                if (code.isEmpty()) {
                    // 没有区域可选，这一枪到此为止——否则转圈永远不停。
                    seats = emptyList()
                    isLoading = false
                } else {
                    // 直接拉，不等 LaunchedEffect(selectedAreaCode)：换走一层再换回来时
                    // code 和上次相同，那个 effect 根本不会重跑，isLoading 就吊在 true 上。
                    // 这里 force 拉一次并占住 lastLoadedAreaCode，effect 真跑起来也会被去重挡掉。
                    loadSeatsFor(code, force = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LIBRARY, Routes.LIBRARY, onBack)
                isLoading = false
            } catch (e: Exception) {
                floorAreas = emptyMap()
                seats = emptyList()
                errorMessage = "楼层信息加载失败: ${e.message}"
                isLoading = false
            }
        }
    }

    // 区域变化 → 自动加载（与首次 bootstrap 去重，同一区域不打第二枪）
    LaunchedEffect(selectedAreaCode) {
        if (selectedAreaCode.isNotEmpty()) loadSeatsFor(selectedAreaCode)
    }
    // 平面图模式下换区域 / 切进平面图：拉这个区域的图
    LaunchedEffect(selectedAreaCode, viewMode) {
        if (viewMode == VIEW_PLAN && selectedAreaCode.isNotEmpty()) loadPlan(selectedAreaCode)
    }

    // 整层的平面图（区域矩形 + 楼层底图），平面图模式下换楼层时拉一次。拿不到就不显示，区域标签照常能用。
    LaunchedEffect(selectedFloorCode, viewMode, campus, bootstrapped) {
        if (viewMode != VIEW_PLAN || !bootstrapped) return@LaunchedEffect
        val code = selectedFloorCode
        if (floorPlanFor == code && floorPlan != null) return@LaunchedEffect
        floorPlan = null
        floorPlanFor = code
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val layout = api.getSeatLayout(code)
                val bytes = PlanImageDiskCache.get(context, "$code.jpg") { api.getPlanImage(it) }
                val img = bytes?.let { decodePlanImages(it, emptyMap()) }
                if (img == null || layout.seats.isEmpty()) null else layout to img
            }.getOrNull()
        }
        if (floorPlanFor == code) floorPlan = r
    }

    // 后台把本校区所有楼层的区域名学一遍。用来判断「已有预约是不是在别的校区」——
    // 只学用户翻过的那几层的话，没翻过的楼层会被误判成外校区。
    // 顺带让切楼层时区域标签立刻就有。
    // 要等进页面那一轮把校区定下来（可能要先切回原校区）再预热：以前按本地记的旧校区一进来就开拉，
    // 和切校区的请求撞在一起，学校按混了的校区回，新校区的楼层拿回来是空的。
    LaunchedEffect(campus, bootstrapped) {
        if (!bootstrapped) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { api.warmCampusAreas(campus) } }
    }

    // 首次 bootstrap：认账号当前校区 → 拉第一层的区域 → 拉预约信息
    LaunchedEffect(Unit) {
        // 以账号在图书馆系统里实际选的校区为准。本地记的那个可能是上次装机时的，
        // 也可能用户在网页端改过；不问一声就按本地的画，会画出一个空列表。
        var actual = withContext(Dispatchers.IO) { runCatching { api.getCurrentCampus() }.getOrNull() }
        // 上次看别的校区时进程被杀，没来得及切回：先补上
        val pending = loadPendingHome(context)
        if (pending != null && actual != null && actual != pending) {
            val ok = withContext(Dispatchers.IO) { runCatching { api.switchCampus(pending) }.getOrDefault(false) }
            if (ok) actual = pending
        }
        if (pending != null && (actual == pending || actual == null)) savePendingHome(context, null)
        homeCampus = actual
        if (actual != null && actual != campus) {
            campus = actual
            savePreferredCampus(context, actual)
        }
        // 从屁岱的座位卡片点进来：直接定位到那个区域，用平面图看
        val focus = LibraryFocus.take()
        if (focus != null) {
            val target = LibraryCampus.byId(focus.campusId)
            if (target != null && actual != null && target != actual) {
                val ok = withContext(Dispatchers.IO) { runCatching { api.switchCampus(target) }.getOrDefault(false) }
                if (ok) {
                    campus = target
                    savePendingHome(context, actual)
                }
            }
            // 扫码进来：预约只认账号当前校区，校区到位就能约，不用等楼层和座位表。
            // 座位空不空另查一次 /qavail/（一个请求），查到前按钮照样能点。
            focus.seatId?.let { seatId ->
                if (target != null && target != campus) {
                    bookingResult = BookResult(false, "没能切换到${target.displayName}，请在上方校区标签里手动切换后再扫码")
                } else {
                    val qr = LibrarySeatQr(seatId, focus.areaCode)
                    scanSeat = ScanSeatPrompt(qr)
                    scope.launch {
                        val status = withContext(Dispatchers.IO) {
                            runCatching { LibrarySeatAvailability.fetch(site.client, qr) }.getOrNull()
                        }
                        scanSeat = scanSeat?.takeIf { it.qr == qr }?.copy(status = status, checking = false)
                    }
                }
            }
            val floor = withContext(Dispatchers.IO) {
                runCatching { api.warmCampusAreas(campus); api.floorOfArea(focus.areaCode) }.getOrNull()
            } ?: LibraryQrArea.byCode(focus.areaCode)?.floorCode?.takeIf { it in campus.floorCodes }
            if (floor != null) {
                viewMode = VIEW_PLAN
                loadFloor(floor, preferArea = focus.areaCode)
            } else {
                loadFloor(campus.floorCodes.first())
            }
        } else {
            loadFloor((actual ?: campus).floorCodes.first())
        }
        bootstrapped = true
        try { myBooking = withContext(Dispatchers.IO) { api.getMyBooking() } } catch (_: Exception) {}
    }

    /**
     * 换校区。
     *
     * 这一步会改用户在图书馆系统里的个人资料（`rplace`），所以只在用户点校区标签时做，
     * 不在后台自动切——见 [LibraryApi.switchCampus]。
     */
    fun switchCampus(target: LibraryCampus) {
        if (target == campus || campusSwitching) return
        campusSwitching = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { api.switchCampus(target) }.getOrDefault(false)
            }
            if (ok) {
                campus = target
                savePreferredCampus(context, target)
                homeCampus?.let { home -> savePendingHome(context, if (target == home) null else home) }
                floorAreas = emptyMap()
                selectedAreaCode = ""
                seats = emptyList()
                areaStatsMap = emptyMap()
                lastLoadedAreaCode = null
                planLayout = null; planImages = null; planAreaCode = null
                loadFloor(target.floorCodes.first())
            } else {
                errorMessage = "切换到" + target.displayName + "失败，请稍后重试"
            }
            campusSwitching = false
        }
    }

    // 预约/换座/取消后只刷新一轮：座位 + 我的预约并行，不再各走一遍
    suspend fun refreshAfterBooking() = coroutineScope {
        val areaCode = selectedAreaCode.takeIf { it.isNotEmpty() }
        val seatsDeferred = if (areaCode != null) {
            lastLoadedAreaCode = areaCode
            async(Dispatchers.IO) { api.getSeats(areaCode) }
        } else null
        val bookingDeferred = async(Dispatchers.IO) {
            runCatching { api.getMyBooking() }.getOrNull()
        }
        if (seatsDeferred != null) {
            when (val result = seatsDeferred.await()) {
                is SeatResult.Success -> { seats = result.seats; areaStatsMap = result.areaStatsMap; errorMessage = null }
                is SeatResult.AuthError -> errorMessage = result.message
                is SeatResult.Error -> errorMessage = result.message
            }
        }
        myBooking = bookingDeferred.await()
        if (viewMode == VIEW_PLAN && areaCode != null) loadPlan(areaCode, force = true)
    }

    /** 在别的校区约成了：账号就留在这个校区，离开页面时不再切回。 */
    fun adoptCampusIfBooked(result: BookResult) {
        if (result.success) {
            homeCampus = campus
            savePendingHome(context, null)
        }
    }

    // ── 预约 ──
    fun doBookSeat(seatId: String, areaOverride: String? = null) {
        val areaCode = areaOverride
            ?: selectedAreaCode.takeIf { it.isNotEmpty() }
            ?: LibraryApi.guessAreaCode(seatId)
            ?: run { bookingResult = BookResult(false, "无法确定区域"); return }
        isBooking = true; bookingResult = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.bookSeat(seatId, areaCode) }
                bookingResult = result
                adoptCampusIfBooked(result)
                // 结果一出来就放开按钮，刷新座位和「我的预约」在后面做
                isBooking = false
                if (result.success) kotlinx.coroutines.delay(400)
                refreshAfterBooking()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { bookingResult = BookResult(false, "预约异常: ${e.message}") }
            isBooking = false
        }
    }

    // 直接换座（已知有现有预约时使用）
    fun doSwapSeat(seatId: String, areaOverride: String? = null) {
        val areaCode = areaOverride
            ?: selectedAreaCode.takeIf { it.isNotEmpty() }
            ?: LibraryApi.guessAreaCode(seatId)
            ?: run { bookingResult = BookResult(false, "无法确定区域"); return }
        isBooking = true; bookingResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.swapSeat(seatId, areaCode) }
                bookingResult = result
                adoptCampusIfBooked(result)
                isBooking = false
                refreshAfterBooking()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { bookingResult = BookResult(false, "换座异常: ${e.message}") }
            isBooking = false
        }
    }

    // 执行操作
    fun executeBookingAction(label: String, url: String) {
        isBooking = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.executeAction(url) }
                bookingResult = BookResult(result.success, "$label: ${result.message}")
                refreshAfterBooking()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { bookingResult = BookResult(false, "$label 失败: ${e.message}") }
            isBooking = false
        }
    }

    // 预约前检查：如有现有预约则弹窗确认换座
    // areaOverride：扫码时二维码上的区域码。页面选中的区域可能还没加载到那个区，
    // 而 guessAreaCode 对纯数字座位号猜不出区域，所以扫码必须显式带上。
    fun bookSeat(seatId: String, areaOverride: String? = null) {
        val existing = myBooking?.seatId
        val isExpired = myBooking?.statusText?.let { "超时" in it || "过期" in it || "失效" in it } == true
        if (existing != null && !isExpired) {
            val bookedArea = myBooking?.area
            val area = bookedArea?.let { " ($it)" } ?: ""
            // 跨校区换座服务端不会照办，只会把请求晾到超时——用户看到的是转很久然后失败。
            // 预约绑在账号的 rplace 上，所以只能先取消再到本校区约。
            if (api.isForeignArea(bookedArea)) {
                confirmDialog = (
                    "你在「$bookedArea」有预约（$existing），不在${campus.displayName}。\n" +
                        "跨校区不能直接换座，需要先取消原预约。\n是否现在取消？"
                ) to {
                    val cancelUrl = myBooking?.actionUrls?.get("取消预约")
                    if (cancelUrl != null) executeBookingAction("取消预约", cancelUrl)
                    else bookingResult = BookResult(false, "没找到取消入口，请到「我的预约」里手动取消")
                }
                return
            }
            confirmDialog = "你已预约座位 $existing$area\n是否换座到 $seatId？" to {
                // 直接调用 /updateseat/ 端点，不走 /seat/ 的检测逻辑
                doSwapSeat(seatId, areaOverride)
            }
        } else {
            doBookSeat(seatId, areaOverride)
        }
    }

    // 收藏切换
    fun toggleFavorite(seatId: String) {
        favorites = if (seatId in favorites) favorites - seatId else favorites + seatId
        saveFavorites(context, favorites)
    }

    // 手动刷新预约信息
    var isLoadingBooking by remember { mutableStateOf(false) }
    fun refreshMyBooking() {
        isLoadingBooking = true
        scope.launch {
            try {
                myBooking = withContext(Dispatchers.IO) { api.getMyBooking() }
            } catch (_: Exception) {}
            isLoadingBooking = false
        }
    }

    var seatScope by rememberSaveable { mutableStateOf("可用") }

    val availableCount = seats.count { it.available }
    val totalCount = seats.size
    val visibleSeats = remember(seats, seatScope, favorites) {
        when (seatScope) {
            "收藏" -> seats.filter { it.seatId in favorites }
            "全部" -> seats
            else -> seats.filter { it.available }
        }
    }

    // ══════ UI ══════
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "图书馆座位",
                largeTitle = "图书馆座位",
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->

        // ── 首次使用提示 ──
        //
        // 必须放在 Scaffold 的 content 里：miuix 0.9.3 起 Overlay* 注册进 LocalDialogStates，
        // 而该 CompositionLocal 只有 Scaffold 提供。写在 Scaffold 外面会注册进一个没有宿主的
        // 空列表，无宿主渲染，不报错也不崩溃，就是不显示。
        if (showHint.value) {
            BackHandler { showHint.value = false; prefs.edit().putBoolean("library_hint_shown", true).apply() }
            OverlayDialog(
                show = showHint.value,
                title = "图书馆座位预约",
                onDismissRequest = {
                    showHint.value = false
                    prefs.edit().putBoolean("library_hint_shown", true).apply()
                }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    val tips = listOf(
                        "⭐" to "长按座位可以收藏，收藏的座位会排在最前面，下次进来一眼就能找到。",
                        "⏰" to "预约成功后，请在 30 分钟内入馆签到，否则当日将被禁止线上预约。",
                        "📋" to "座位状态说明：「使用中」= 已签到入座；「已预约」 = 已预约未签到；「暂离」= 短暂离开保留中。",
                        "🚫" to "本版本已移除定时抢座功能。频繁自动化请求可能触发学校系统风控，导致账号被限制使用图书馆服务，望理解。"
                    )
                    tips.forEach { (emoji, text) ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(emoji, style = MiuixTheme.textStyles.body1)
                            Spacer(Modifier.width(8.dp))
                            Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = "知道了",
                        onClick = {
                            showHint.value = false
                            prefs.edit().putBoolean("library_hint_shown", true).apply()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        // Overlay* 必须放在 Scaffold content 内：miuix 弹窗靠 Scaffold 提供的
        // MiuixPopupHost(LocalPopupStates) 渲染；放在 Scaffold 外（且 App 根无 popup host）会永不显示，
        // 正是"换座/取消点了没反应、请求从未发出"的真因。
        // 扫桌面二维码进来的预约确认。校区定下来就弹，状态查到前「预约」也能点：
        // 真被占了服务端会拒，失败原因照常显示在页面上。
        val ss = scanSeat
        BackHandler(enabled = ss != null) { scanSeat = null }
        OverlayDialog(
            show = ss != null,
            title = "预约座位",
            summary = ss?.let { "${it.qr.areaName} · ${it.qr.seat} 号" },
            renderInRootScaffold = false,
            onDismissRequest = { scanSeat = null },
        ) {
            val status = ss?.status
            val blocked = status is LibrarySeatStatus.NotFound ||
                (status is LibrarySeatStatus.Known && !status.isFree)
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = when {
                        ss == null -> ""
                        ss.checking -> "正在查看座位状态…"
                        status is LibrarySeatStatus.Known && status.isFree -> status.statusText
                        status is LibrarySeatStatus.Known -> "${status.statusText}，可以关掉后在平面图里另选"
                        status is LibrarySeatStatus.NotFound -> "图书馆系统里查不到这个座位，二维码可能已失效"
                        else -> "暂时查不到座位状态，可以直接预约"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = when {
                        blocked -> MiuixTheme.colorScheme.error
                        status is LibrarySeatStatus.Known -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { scanSeat = null },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "预约",
                        enabled = !blocked && !isBooking,
                        onClick = {
                            val qr = ss?.qr
                            scanSeat = null
                            if (qr != null) bookSeat(qr.seat, areaOverride = qr.areaCode)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }

        val cd = confirmDialog
        BackHandler(enabled = cd != null) { confirmDialog = null }
        OverlayDialog(
            show = cd != null,
            title = "确认操作",
            summary = cd?.first,
            renderInRootScaffold = false,
            onDismissRequest = {
                android.util.Log.d("LibraryScreen", "confirm DISMISSED")
                confirmDialog = null
            }
        ) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = "取消",
                    onClick = {
                        android.util.Log.d("LibraryScreen", "confirm CANCELLED")
                        confirmDialog = null
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确认",
                    onClick = {
                        android.util.Log.d("LibraryScreen", "confirm CLICKED")
                        val act = cd?.second
                        confirmDialog = null
                        act?.invoke()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }

        // 下拉指示器只跟随真实的下拉手势：进入页面 / 切换区域等程序触发的加载
        // 由内容区的 LoadingState 呈现，避免页面自己"演"一次下拉刷新动画。
        var isPullRefreshing by remember { mutableStateOf(false) }
        LaunchedEffect(isLoading, isLoadingBooking) {
            if (!isLoading && !isLoadingBooking) isPullRefreshing = false
        }
        // 内容铺到顶栏下面，顶部留白放进列表；下拉指示器也从顶栏下面出来
        val glassTop = padding.glassTop(glass)
        top.yukonga.miuix.kmp.basic.PullToRefresh(
            refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
            // 顶栏折叠交给下拉刷新协调：往下拉先展开大标题，展开完才算下拉刷新。不传的话下拉刷新先把拖动吃掉，慢慢拉只会刷新、标题展不开
            topAppBarScrollBehavior = scrollBehavior,
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                loadSeats(force = true)
                refreshMyBooking()
                bookingResult = null
            },
            contentPadding = PaddingValues(top = glassTop),
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass)
        ) {
        // 上面这一整叠卡片：预约状态 / 校区楼层区域 / 座位统计。
        //
        // 座位列表模式下它们是座位网格的第一项，和座位一起滚：以前固定在顶部，
        // 占掉大半屏，往上划只有下面一小块座位在动，半个屏幕纹丝不动，很别扭。
        // 加载中、出错、没有座位时没有可滚的列表，它们仍然钉在顶上。
        val headerContent: @Composable ColumnScope.() -> Unit = {
            // 预约结果从上方弹进来（缩放 + 淡入），比平铺展开更像「一个结果」
            AnimatedVisibility(
                bookingResult != null,
                enter = androidx.compose.animation.scaleIn(
                    initialScale = 0.9f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f),
                ) + androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(
                        color = if (bookingResult?.success == true) {
                            MiuixTheme.colorScheme.secondaryContainer
                        } else {
                            MiuixTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            bookingResult?.message ?: "",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── 当前预约 ──（头部三张卡依次登场）
            Card(
                Modifier.enterOnce(0).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.defaultColors(
                    color = if (myBooking != null) {
                        MiuixTheme.colorScheme.secondaryContainer
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, Modifier.size(20.dp),
                            tint = if (myBooking != null) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            if (myBooking != null) {
                                Text(
                                    buildString { append("当前预约"); myBooking?.seatId?.let { append("：$it") } },
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium
                                )
                                val subInfo = buildString {
                                    myBooking?.area?.let { append(it) }
                                    myBooking?.statusText?.let { if (isNotEmpty()) append(" · "); append(it) }
                                }
                                if (subInfo.isNotBlank()) Text(
                                    subInfo, style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            } else {
                                Text("还没有预约", style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    "从下方选择区域和座位",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    val isExpiredBooking = myBooking?.statusText in LibraryApi.INACTIVE_STATUSES
                    val actions = if (isExpiredBooking) null else myBooking?.actionUrls
                    if (!actions.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.filter { (label, _) -> "换座" !in label }.forEach { (label, url) ->
                                Button(
                                    onClick = {
                                        when {
                                            "取消" in label || "离开" in label -> {
                                                // 危险操作：弹二级确认
                                                confirmDialog = "确定要「$label」吗？" to { executeBookingAction(label, url) }
                                            }
                                            else -> executeBookingAction(label, url)
                                        }
                                    },
                                    enabled = !isBooking,
                                    colors = ButtonDefaults.buttonColors(
                                        color = if ("取消" in label) MiuixTheme.colorScheme.errorContainer
                                        else MiuixTheme.colorScheme.secondaryContainer
                                    ),
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text(label, style = MiuixTheme.textStyles.footnote1) }
                            }
                        }
                    }
                }
            }

            // ── 校区/楼层/区域选择器 (一体化) ──
            Card(
                modifier = Modifier.enterOnce(1).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)
            ) {
                Column {
                    // 校区。切换会写回账号资料（rplace），所以切换期间禁用整排，
                    // 避免用户连点两下把请求打叉。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LibraryCampus.entries.forEach { c ->
                            com.xjtu.toolbox.ui.components.AppFilterChip(
                                selected = campus == c,
                                onClick = { if (!campusSwitching) switchCampus(c) },
                                label = c.displayName
                            )
                        }
                        if (campusSwitching) {
                            CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                        }
                    }
                    val home = homeCampus
                    if (home != null && home != campus) {
                        Text(
                            "离开本页会切回${home.displayName}；在这里约了座位就留在${campus.displayName}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                        )
                    }
                    if (floors.isNotEmpty()) {
                        CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                            AppSegmentedTabs(
                                tabs = floors,
                                selectedTabIndex = campus.floorCodes.indexOf(selectedFloorCode).coerceAtLeast(0),
                                onTabSelected = { index ->
                                    campus.floorCodes.getOrNull(index)?.let { loadFloor(it) }
                                },
                                embedded = true,
                            )
                        }
                    }
                    if (floors.isNotEmpty() && areaCodes.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
                        )
                    }
                    if (areaCodes.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            areaCodes.forEach { code ->
                                com.xjtu.toolbox.ui.components.AppFilterChip(
                                    selected = selectedAreaCode == code,
                                    onClick = { selectedAreaCode = code },
                                    label = floorAreas[code] ?: code
                                )
                            }
                        }
                    }
                }
            }

            if (seats.isNotEmpty() || viewMode == VIEW_PLAN) {
                Card(
                    modifier = Modifier.enterOnce(2).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    cornerRadius = 20.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    // 列表 / 平面图。平面图看得到座位在哪（靠窗、离门远近），列表适合快速扫空位。
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(VIEW_PLAN, VIEW_LIST).forEach { mode ->
                            com.xjtu.toolbox.ui.components.AppFilterChip(
                                selected = viewMode == mode,
                                onClick = { viewMode = mode; saveViewMode(context, mode) },
                                label = mode,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (totalCount > 0) Text(
                            "空闲 $availableCount / $totalCount",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (viewMode == VIEW_LIST) Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            "可用" to availableCount,
                            "收藏" to seats.count { it.seatId in favorites },
                            "全部" to totalCount
                        ).forEach { (label, count) ->
                            com.xjtu.toolbox.ui.components.AppFilterChip(
                                selected = seatScope == label,
                                onClick = { seatScope = label },
                                label = "$label $count",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            // 已有座位数据时的静默刷新（切换区域/楼层）：用顶部细进度线提示，不清空列表
            AnimatedVisibility(visible = isLoading && seats.isNotEmpty() && !isPullRefreshing) {
                LinearProgressIndicator(
                    progress = null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    height = 2.dp
                )
            }
        }

        // 宽屏两栏：左边一直是头部那叠卡片（自己滚），右边是座位网格 / 平面图。
        //
        // 手机上头部卡片**始终**是网格的第一项，加载、出错、空状态也放在网格里。
        // 以前加载时头部钉在顶上、有数据了再挪进网格：挪一次就换一个组合位置，
        // 三张卡的入场动画重播一遍，整页先跳一下再淡入一次——看着就是「加载动画很怪」。
        val wideLibrary = com.xjtu.toolbox.ui.isWideLayout()
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        Row(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        if (wideLibrary) {
            Column(
                Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(glassTop))
                headerContent()
                Spacer(Modifier.height(16.dp))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val viewportHeight = maxHeight
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 56.dp),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = if (wideLibrary) glassTop + 6.dp else 0.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().overScrollVertical()
            ) {
                if (!wideLibrary) item(span = { GridItemSpan(maxLineSpan) }, key = "header", contentType = "header") {
                    // 网格左右有 12dp 内边距，头部卡片自带 16dp 外边距；把网格的边距抵掉，
                    // 卡片才和宽屏左栏里的位置一致。
                    Column(
                        Modifier.layout { measurable, constraints ->
                            val extra = 12.dp.roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(
                                    minWidth = constraints.minWidth + extra * 2,
                                    maxWidth = constraints.maxWidth + extra * 2,
                                )
                            )
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.place(-extra, 0)
                            }
                        }
                    ) {
                        Spacer(Modifier.height(glassTop))
                        headerContent()
                    }
                }

                fun fullSpan(key: String, content: @Composable () -> Unit) =
                    item(span = { GridItemSpan(maxLineSpan) }, key = key, contentType = key) { content() }

                when {
                    isLoading && seats.isEmpty() -> fullSpan("loading") {
                        LoadingState(message = "正在查询座位…", modifier = Modifier.heightIn(min = 280.dp))
                    }

                    errorMessage != null -> fullSpan("error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(errorMessage!!, color = MiuixTheme.colorScheme.error,
                                textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 区域都没拉到时（这一层为空、楼层接口出错）重拉楼层；拉座位没用，那时还没有区域可拉
                                Button(onClick = {
                                    if (selectedAreaCode.isEmpty() || floorAreas.isEmpty()) loadFloor(selectedFloorCode)
                                    else loadSeats(force = true)
                                }) { Text("重试") }
                                // 认证相关错误 → 提供重新认证
                                if ("认证" in (errorMessage ?: "") || "登录" in (errorMessage ?: "") || "VPN" in (errorMessage ?: "")) {
                                    var isReAuth by remember { mutableStateOf(false) }
                                    Button(
                                        onClick = {
                                            isReAuth = true
                                            scope.launch {
                                                try {
                                                    val creds = appLoginState.sessionManager?.credentials
                                                        ?: error("未配置凭据")
                                                    withContext(Dispatchers.IO) {
                                                        site.ensureLogin(creds.first, creds.second, force = true)
                                                    }
                                                    loadSeats(force = true)
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) { errorMessage = "重新认证失败: ${e.message}" }
                                                isReAuth = false
                                            }
                                        },
                                        enabled = !isReAuth
                                    ) {
                                        if (isReAuth) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        else Text("重新认证")
                                    }
                                }
                            }
                        }
                    }

                    viewMode == VIEW_PLAN -> fullSpan("plan") {
                        // 手机上图高约一屏：把头部卡片往上滚走，图正好铺满；平板在右栏占满。
                        val planHeight = (viewportHeight - (if (wideLibrary) glassTop + 22.dp else 16.dp))
                            .coerceAtLeast(320.dp)
                        val layout = planLayout
                        val images = planImages
                        Column {
                        // 整层图：点区域切区域。只保留这一层真有的区域，图上别的矩形（楼梯、出口按钮）不响应
                        floorPlan?.let { (fl, fi) ->
                            val areas = remember(fl, floorAreas) { fl.copy(seats = fl.seats.filter { it.seatId in floorAreas }) }
                            if (areas.seats.isNotEmpty()) FloorPlanView(
                                layout = areas,
                                images = fi,
                                selectedArea = selectedAreaCode,
                                onPick = { selectedAreaCode = it },
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                        }
                        when {
                            layout != null && images != null -> SeatPlanPanel(
                                layout = layout,
                                images = images,
                                maxHeight = planHeight,
                                favorites = favorites,
                                isBooking = isBooking,
                                // 平面图模式下页面顶部的结果卡已经滚出屏幕，图下面再给一份
                                result = bookingResult,
                                onBook = { bookSeat(it) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            planError != null -> Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(planError!!, color = MiuixTheme.colorScheme.error,
                                    textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { loadPlan(selectedAreaCode, force = true) }) { Text("重试") }
                                    Button(onClick = { viewMode = VIEW_LIST; saveViewMode(context, VIEW_LIST) }) { Text("看列表") }
                                }
                            }
                            else -> LoadingState(message = "正在加载平面图…", modifier = Modifier.heightIn(min = 280.dp))
                        }
                        }
                    }

                    seats.isEmpty() -> fullSpan("empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                            Text("该区域暂无座位数据", style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }

                    else -> {
                        // 收藏座位快捷区
                        val favInArea = seats.filter { it.seatId in favorites }
                        if (favInArea.isNotEmpty()) fullSpan("favorites") {
                            Column(Modifier.padding(bottom = 4.dp)) {
                                Text("★ 收藏座位", style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primaryVariant)
                                Spacer(Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    favInArea.forEach { seat ->
                                        SeatChip(
                                            seat = seat,
                                            isBooking = isBooking,
                                            isFavorite = true,
                                            onClick = { if (seat.available) bookSeat(seat.seatId) },
                                            onLongClick = { toggleFavorite(seat.seatId) }
                                        )
                                    }
                                }
                            }
                        }

                        // 全部座位
                        items(visibleSeats, key = { it.seatId }) { seat ->
                            SeatChip(
                                seat = seat,
                                isBooking = isBooking,
                                isFavorite = seat.seatId in favorites,
                                onClick = { if (seat.available) bookSeat(seat.seatId) },
                                onLongClick = { toggleFavorite(seat.seatId) }
                            )
                        }
                    }
                }
            }
        }
        } // Row（宽屏：左头部、右座位）
        }
    }
}

// ══════ SeatChip ══════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeatChip(
    seat: SeatInfo, isBooking: Boolean, isFavorite: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit
) {
    val bgColor = when {
        isFavorite && seat.available -> MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.15f)
        seat.available -> MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)
    }
    val textColor = when {
        isFavorite && seat.available -> MiuixTheme.colorScheme.primaryVariant
        seat.available -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
    }

    val press = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(press, pressed = 0.9f)   // 按下缩一点再弹回，座位格摸起来是软的
            .squircleSurface(color = bgColor, cornerRadius = 10.dp)
            .then(
                if (!isBooking)
                    Modifier.combinedClickable(
                        interactionSource = press,
                        indication = SinkFeedback(),
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .animateContentSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isFavorite) {
                Icon(Icons.Default.Star, null, Modifier.size(10.dp),
                    tint = MiuixTheme.colorScheme.primaryVariant)
            }
            Text(
                seat.seatId,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = if (seat.available) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
