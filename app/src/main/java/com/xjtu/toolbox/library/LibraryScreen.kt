package com.xjtu.toolbox.library

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.*
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
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
import com.xjtu.toolbox.auth.LibraryLogin

import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.layout.FlowRow
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
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
fun LibraryScreen(login: LibraryLogin, onBack: () -> Unit) {
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val api = remember { LibraryApi(login) }
    val context = LocalContext.current

    // ── 首次使用提示 ──
    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("library_hint_shown", false)) }
    if (showHint.value) {
        BackHandler { showHint.value = false; prefs.edit().putBoolean("library_hint_shown", true).apply() }
        OverlayBottomSheet(
            show = showHint.value,
            title = "图书馆座位预约",
            onDismissRequest = {
                showHint.value = false
                prefs.edit().putBoolean("library_hint_shown", true).apply()
            }
        ) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Text("使用说明", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))

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
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        showHint.value = false
                        prefs.edit().putBoolean("library_hint_shown", true).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了") }
            }
        }
    }

    // 座位数据
    var seats by remember { mutableStateOf<List<SeatInfo>>(emptyList()) }
    var areaStatsMap by remember { mutableStateOf<Map<String, AreaStats>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 预约
    var bookingResult by remember { mutableStateOf<BookResult?>(null) }
    var isBooking by remember { mutableStateOf(false) }
    var seatInput by remember { mutableStateOf("") }

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
    fun loadSeatsFor(areaCode: String) {
        isLoading = true; errorMessage = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.getSeats(areaCode) }
                when (result) {
                    is SeatResult.Success -> { seats = result.seats; areaStatsMap = result.areaStatsMap; errorMessage = null }
                    is SeatResult.AuthError -> { seats = emptyList(); errorMessage = result.message }
                    is SeatResult.Error -> { seats = emptyList(); errorMessage = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.LIBRARY, Routes.LIBRARY, onBack)
            }
            catch (e: Exception) { seats = emptyList(); errorMessage = "加载失败: ${e.message}" }
            isLoading = false
        }
    }

    fun loadSeats() { LibraryApi.AREA_MAP[selectedArea]?.let { loadSeatsFor(it) } }

    // 区域变化 → 自动加载
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
                // 被占 → 自动刷新列表
                if ("已被占" in result.message || "已被预约" in result.message || "刷新" in result.message) loadSeats()
                if (result.success) {
                    loadSeats()
                    try { myBooking = withContext(Dispatchers.IO) { api.getMyBooking() } } catch (_: Exception) {}
                }
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
                if (result.success) {
                    loadSeats()
                    try { myBooking = withContext(Dispatchers.IO) { api.getMyBooking() } } catch (_: Exception) {}
                }
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
                myBooking = withContext(Dispatchers.IO) { api.getMyBooking() }
                if (label == "取消预约") loadSeats()
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
    val currentAreaCode = LibraryApi.AREA_MAP[selectedArea] ?: ""
    val mapAvailable = currentAreaCode in MAP_SUPPORTED_AREAS

    val availableCount = seats.count { it.available }
    val totalCount = seats.size

    // ── 确认对话框 ──
    val showConfirmDialog = remember { mutableStateOf(false) }
    var confirmMsg by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    confirmDialog?.let { (msg, action) ->
        LaunchedEffect(msg) {
            confirmMsg = msg
            confirmAction = action
            showConfirmDialog.value = true
        }
    }
    BackHandler(enabled = showConfirmDialog.value) { showConfirmDialog.value = false; confirmDialog = null }
    OverlayBottomSheet(
        show = showConfirmDialog.value,
        title = "确认操作",
        onDismissRequest = { showConfirmDialog.value = false; confirmDialog = null }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding()
        ) {
            Text(confirmMsg, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showConfirmDialog.value = false; confirmDialog = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer)
                ) { Text("取消", color = MiuixTheme.colorScheme.onSecondaryContainer) }
                Button(
                    onClick = { showConfirmDialog.value = false; confirmDialog = null; confirmAction?.invoke() },
                    modifier = Modifier.weight(1f)
                ) { Text("确认") }
            }
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
                },
                actions = {
                    IconButton(onClick = { loadSeats() }) { Icon(Icons.Default.Refresh, "刷新") }
                }
            )
        },
        bottomBar = {
            Surface() {
                Column(
                    Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 预约结果
                    AnimatedVisibility(bookingResult != null) {
                        top.yukonga.miuix.kmp.basic.Card(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = if (bookingResult?.success == true)
                                    MiuixTheme.colorScheme.secondaryContainer
                                else MiuixTheme.colorScheme.errorContainer
                            )
                        ) {
                            Box {
                                Column(Modifier.padding(12.dp).padding(end = 16.dp)) {
                                    Text(
                                        bookingResult?.message ?: "",
                                        style = MiuixTheme.textStyles.footnote1
                                    )
                                    // 附加操作
                                    if (bookingResult?.success == false && "刷新" in (bookingResult?.message ?: "")) {
                                        Spacer(Modifier.height(4.dp))
                                        Button(
                                            onClick = { loadSeats(); bookingResult = null },
                                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                        ) { Text("刷新座位", style = MiuixTheme.textStyles.footnote1) }
                                    }
                                }
                            }
                        }
                    }
                    // 输入框
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = seatInput,
                            onValueChange = { seatInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = "座位号（如 A101）",
                            textStyle = MiuixTheme.textStyles.footnote1
                        )
                        Button(
                            onClick = { seatInput.trim().takeIf { it.isNotEmpty() }?.let { bookSeat(it) } },
                            enabled = !isBooking && seatInput.isNotBlank()
                        ) { Text("抢座") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            // ── 当前预约 ──
            top.yukonga.miuix.kmp.basic.Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
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
                                Text("暂无预约", style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        // 刷新预约按钮
                        IconButton(
                            onClick = { refreshMyBooking() },
                            enabled = !isLoadingBooking,
                            modifier = Modifier.size(32.dp)
                        ) {
                            if (isLoadingBooking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, "刷新预约", Modifier.size(18.dp))
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
                top.yukonga.miuix.kmp.basic.Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
                ) {
                    Column {
                        if (floors.isNotEmpty()) {
                            TabRowWithContour(
                                tabs = floors,
                                selectedTabIndex = (floors.indexOf(selectedFloor)).coerceAtLeast(0),
                                onTabSelected = { selectedFloor = floors.getOrElse(it) { floors.first() } },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
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

            Spacer(Modifier.height(4.dp))

            // ── 内容区 ──
            when {
                isLoading -> {
                    LoadingState(message = "正在查询坐位…", modifier = Modifier.fillMaxSize())
                }

                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(errorMessage!!, color = MiuixTheme.colorScheme.error,
                                textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { loadSeats() }) { Text("重试") }
                                // 认证相关错误 → 提供重新认证
                                if ("认证" in (errorMessage ?: "") || "登录" in (errorMessage ?: "") || "VPN" in (errorMessage ?: "")) {
                                    var isReAuth by remember { mutableStateOf(false) }
                                    Button(
                                        onClick = {
                                            isReAuth = true
                                            scope.launch {
                                                try {
                                                    val ok = withContext(Dispatchers.IO) { login.reAuthenticate() }
                                                    if (ok) loadSeats()
                                                    else errorMessage = "重新认证失败：${login.diagnosticInfo}"
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
                    // 统计栏
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("共 $totalCount 座", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.width(8.dp))
                        Text("$availableCount 可用",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("${totalCount - availableCount} 已占",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        // 收藏入口
                        if (favorites.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("★ ${favorites.size}",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primaryVariant)
                        }
                        // 地图/列表切换
                        if (mapAvailable) {
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { showMapView = !showMapView },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    if (showMapView) Icons.Default.ViewModule else Icons.Default.Map,
                                    contentDescription = if (showMapView) "列表视图" else "地图视图",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

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
                                Column(Modifier.padding(bottom = 4.dp)) {
                                    Text("💡 推荐座位", style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary)
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
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

                        // 全部座位
                        items(seats, key = { it.seatId }) { seat ->
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
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
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