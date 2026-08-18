package com.xjtu.toolbox.library

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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ViewModule
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
    var isLoading by remember { mutableStateOf(false) }
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

    // 我的预约
    var myBooking by remember { mutableStateOf<MyBookingInfo?>(null) }

    // 收藏
    var favorites by remember { mutableStateOf(loadFavorites(context)) }

    // ── 楼层/区域选择 ──
    val allFloors = remember { LibraryApi.FLOORS.keys.toList() }
    val floors = remember(allFloors, areaStatsMap) {
        if (areaStatsMap.isEmpty()) allFloors
        else allFloors.filter { floor ->
            val floorAreas = LibraryApi.FLOORS[floor] ?: emptyList()
            floorAreas.any { area ->
                val code = LibraryApi.AREA_MAP[area] ?: return@any false
                areaStatsMap[code]?.isOpen == true
            }
        }
    }
    var selectedFloor by remember { mutableStateOf(allFloors.first()) }
    LaunchedEffect(floors) {
        if (selectedFloor !in floors && floors.isNotEmpty()) selectedFloor = floors.first()
    }

    val allAreas = remember(selectedFloor) { LibraryApi.FLOORS[selectedFloor] ?: emptyList() }
    // scount 未加载时不显示区域，避免未开放区域闪烁
    val areas = remember(allAreas, areaStatsMap) {
        if (areaStatsMap.isEmpty()) emptyList()
        else allAreas.filter { area ->
            val code = LibraryApi.AREA_MAP[area] ?: return@filter false
            areaStatsMap[code]?.isOpen == true
        }
    }
    var selectedArea by remember(selectedFloor) { mutableStateOf(areas.firstOrNull() ?: "") }
    LaunchedEffect(areas) {
        if (selectedArea !in areas) selectedArea = areas.firstOrNull() ?: ""
    }

    // 智能推荐座位
    val recommendedSeats by remember(seats, selectedArea) {
        derivedStateOf {
            val areaCode = LibraryApi.AREA_MAP[selectedArea] ?: return@derivedStateOf emptyList()
            if (seats.isEmpty()) return@derivedStateOf emptyList()
            api.recommendSeats(seats, areaCode, topN = 5)
        }
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

    fun loadSeats(force: Boolean = false) {
        LibraryApi.AREA_MAP[selectedArea]?.let { loadSeatsFor(it, force) }
    }

    // 区域变化 → 自动加载（与首次 bootstrap 去重，同一区域不打第二枪）
    LaunchedEffect(selectedArea) {
        if (selectedArea.isNotEmpty()) {
            LibraryApi.AREA_MAP[selectedArea]?.let { loadSeatsFor(it) }
        }
    }

    // 首次 bootstrap：加载 scount + 预约信息
    LaunchedEffect(Unit) {
        val bootstrapCode = allAreas.firstOrNull()?.let { LibraryApi.AREA_MAP[it] }
        if (bootstrapCode != null) loadSeatsFor(bootstrapCode)
        try { myBooking = withContext(Dispatchers.IO) { api.getMyBooking() } } catch (_: Exception) {}
    }

    // 预约/换座/取消后只刷新一轮：座位 + 我的预约并行，不再各走一遍
    suspend fun refreshAfterBooking() = coroutineScope {
        val areaCode = LibraryApi.AREA_MAP[selectedArea]
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
    }

    // ── 预约 ──
    fun doBookSeat(seatId: String) {
        val areaCode = LibraryApi.AREA_MAP[selectedArea]
            ?: LibraryApi.guessAreaCode(seatId)
            ?: run { bookingResult = BookResult(false, "无法确定区域"); return }
        isBooking = true; bookingResult = null

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.bookSeat(seatId, areaCode) }
                bookingResult = result
                if (result.success) kotlinx.coroutines.delay(400)
                refreshAfterBooking()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { bookingResult = BookResult(false, "预约异常: ${e.message}") }
            isBooking = false
        }
    }

    // 直接换座（已知有现有预约时使用）
    fun doSwapSeat(seatId: String) {
        val areaCode = LibraryApi.AREA_MAP[selectedArea]
            ?: LibraryApi.guessAreaCode(seatId)
            ?: run { bookingResult = BookResult(false, "无法确定区域"); return }
        isBooking = true; bookingResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.swapSeat(seatId, areaCode) }
                bookingResult = result
                refreshAfterBooking()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { bookingResult = BookResult(false, "换座异常: ${e.message}") }
            isBooking = false
        }
    }

    // 预约前检查：如有现有预约则弹窗确认换座
    fun bookSeat(seatId: String) {
        val existing = myBooking?.seatId
        val isExpired = myBooking?.statusText?.let { "超时" in it || "过期" in it || "失效" in it } == true
        if (existing != null && !isExpired) {
            val area = myBooking?.area?.let { " ($it)" } ?: ""
            confirmDialog = "你已预约座位 $existing$area\n是否换座到 $seatId？" to {
                // 直接调用 /updateseat/ 端点，不走 /seat/ 的检测逻辑
                doSwapSeat(seatId)
            }
        } else {
            doBookSeat(seatId)
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

    // 地图/列表视图切换
    var showMapView by remember { mutableStateOf(false) }
    var seatScope by rememberSaveable { mutableStateOf("可用") }
    val currentAreaCode = LibraryApi.AREA_MAP[selectedArea] ?: ""
    val mapAvailable = currentAreaCode in MAP_SUPPORTED_AREAS

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = "图书馆座位",
                largeTitle = "图书馆座位",
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
                        "💡" to "智能推荐算法会根据「桌组空闲度、邻座占用率、是否靠墙/角落、离入口距离」等因素为你打分推荐最佳座位。",
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
        top.yukonga.miuix.kmp.basic.PullToRefresh(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                loadSeats(force = true)
                refreshMyBooking()
                bookingResult = null
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
            AnimatedVisibility(bookingResult != null) {
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

            // ── 当前预约 ──
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
                    val expiredStatuses = setOf("已取消", "已完成", "已过期", "已失效", "已违约", "超时取消", "超时未入馆", "超时", "已离馆")
                    val isExpiredBooking = myBooking?.statusText in expiredStatuses
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

            // ── 楼层/区域选择器 (一体化) ──
            if (floors.isNotEmpty() || areas.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                ) {
                    Column {
                        if (floors.isNotEmpty()) {
                            AppSegmentedTabs(
                                tabs = floors,
                                selectedTabIndex = (floors.indexOf(selectedFloor)).coerceAtLeast(0),
                                onTabSelected = { selectedFloor = floors.getOrElse(it) { floors.first() } },
                                embedded = true,
                            )
                        }
                        if (floors.isNotEmpty() && areas.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
                            )
                        }
                        if (areas.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                areas.forEach { area ->
                                    com.xjtu.toolbox.ui.components.AppFilterChip(
                                        selected = selectedArea == area,
                                        onClick = { selectedArea = area },
                                        label = area
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (seats.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    cornerRadius = 20.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
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
                        if (mapAvailable) {
                            IconButton(
                                onClick = { showMapView = !showMapView },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (showMapView) Icons.Default.ViewModule else Icons.Default.Map,
                                    contentDescription = if (showMapView) "列表视图" else "地图视图",
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
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

            // ── 内容区 ──
            when {
                isLoading && seats.isEmpty() -> {
                    LoadingState(message = "正在查询座位…", modifier = Modifier.fillMaxSize())
                }

                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(errorMessage!!, color = MiuixTheme.colorScheme.error,
                                textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { loadSeats(force = true) }) { Text("重试") }
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
                }

                seats.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("该区域暂无座位数据", style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }

                else -> {
                    if (showMapView && mapAvailable) {
                        // ── 座位地图（物理布局版） ──
                        SeatMapCanvas(
                            seats = seats,
                            areaCode = currentAreaCode,
                            favorites = favorites,
                            recommendedSeats = recommendedSeats,
                            onSeatClick = { bookSeat(it.seatId) },
                            onSeatLongClick = { toggleFavorite(it) },
                            onUnavailableSeatClick = { /* no-op */ }
                        )
                    } else {
                    // 座位网格
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 56.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize().overScrollVertical()
                    ) {
                        // 收藏座位快捷区
                        val favInArea = seats.filter { it.seatId in favorites }
                        if (favInArea.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
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
                        }

                        // 智能推荐座位
                        if (recommendedSeats.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    colors = CardDefaults.defaultColors(
                                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.09f)
                                    )
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Star,
                                                null,
                                                Modifier.size(18.dp),
                                                tint = MiuixTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    "为你推荐",
                                                    style = MiuixTheme.textStyles.body1,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MiuixTheme.colorScheme.primary
                                                )
                                                Text(
                                                    "综合桌组空闲度、邻座和位置",
                                                    style = MiuixTheme.textStyles.footnote1,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            recommendedSeats.forEach { seat ->
                                                SeatChip(
                                                    seat = seat,
                                                    isBooking = isBooking,
                                                    isFavorite = seat.seatId in favorites,
                                                    onClick = { bookSeat(seat.seatId) },
                                                    onLongClick = { toggleFavorite(seat.seatId) }
                                                )
                                            }
                                        }
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
                    } // end else (list view)
                }
            }
        }
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

    Box(
        modifier = Modifier
            .squircleSurface(color = bgColor, cornerRadius = 10.dp)
            .then(
                if (!isBooking)
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
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
