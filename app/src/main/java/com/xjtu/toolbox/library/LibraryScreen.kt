package com.xjtu.toolbox.library

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.xjtu.toolbox.ui.components.AppFilterChip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(login: LibraryLogin, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { LibraryApi(login) }
    val context = LocalContext.current

    // 座位数据
    var seats by remember { mutableStateOf<List<SeatInfo>>(emptyList()) }
    var areaStatsMap by remember { mutableStateOf<Map<String, AreaStats>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 预约
    var bookingResult by remember { mutableStateOf<BookResult?>(null) }
    var isBooking by remember { mutableStateOf(false) }
    var seatInput by remember { mutableStateOf("") }

    // 确认对话框
    var confirmDialog by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // 我的预约
    var myBooking by remember { mutableStateOf<MyBookingInfo?>(null) }

    // 收藏
    var favorites by remember { mutableStateOf(loadFavorites(context)) }

    // 定时抢座
    var showGrabDialog by remember { mutableStateOf(false) }
    var grabScheduled by remember { mutableStateOf(SeatGrabScheduler.isScheduled(context)) }
    var grabConfig by remember { mutableStateOf(SeatGrabConfigStore.load(context)) }


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

    // 预约前检查：如有现有预约则弹窗确认换座
    fun bookSeat(seatId: String) {
        val existing = myBooking?.seatId
        if (existing != null) {
            val area = myBooking?.area?.let { " ($it)" } ?: ""
            confirmDialog = "你已预约座位 $existing$area\n是否换座到 $seatId？" to {
                // 直接预约新座位，bookSeat 内部自动检测并确认换座
                doBookSeat(seatId)
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

    val availableCount = seats.count { it.available }
    val totalCount = seats.size

    // ── 定时抢座对话框 ──
    if (showGrabDialog) {
        SeatGrabDialog(
            currentFavorites = favorites,
            selectedArea = selectedArea,
            onDismiss = { showGrabDialog = false },
            onConfirm = { newConfig ->
                SeatGrabConfigStore.save(context, newConfig)
                grabConfig = newConfig
                val ok = SeatGrabScheduler.schedule(context, newConfig)
                grabScheduled = ok
                showGrabDialog = false
                if (ok) {
                    bookingResult = BookResult(true, "⏰ 定时抢座已设定：${newConfig.triggerTimeStr}\n" +
                        "目标: ${newConfig.targetSeats.joinToString { it.seatId }}")
                } else {
                    bookingResult = BookResult(false, "设定失败：请授予精确闹钟权限后重试")
                }
            }
        )
    }

    // ── 确认对话框 ──
    confirmDialog?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirmDialog = null },
            title = { Text("确认操作") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { confirmDialog = null; action() }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialog = null }) { Text("取消") }
            }
        )
    }

    // ══════ UI ══════
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书馆座位") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    // 定时抢座入口
                    IconButton(onClick = {
                        if (grabScheduled) {
                            // 已有闹钟 → 弹确认取消
                            confirmDialog = "已设定 ${grabConfig.triggerTimeStr} 定时抢座\n" +
                                "目标: ${grabConfig.targetSeats.joinToString { it.seatId }}\n\n取消定时？" to {
                                SeatGrabScheduler.cancel(context)
                                SeatGrabConfigStore.save(context, grabConfig.copy(enabled = false))
                                grabScheduled = false
                                bookingResult = BookResult(true, "定时抢座已取消")
                            }
                        } else {
                            showGrabDialog = true
                        }
                    }) {
                        Icon(
                            Icons.Default.Schedule,
                            "定时抢座",
                            tint = if (grabScheduled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { loadSeats() }) { Icon(Icons.Default.Refresh, "刷新") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 预约结果
                    AnimatedVisibility(bookingResult != null) {
                        Card(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (bookingResult?.success == true)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    bookingResult?.message ?: "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                // 附加操作
                                if (bookingResult?.success == false) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if ("刷新" in (bookingResult?.message ?: "")) {
                                            FilledTonalButton(
                                                onClick = { loadSeats(); bookingResult = null },
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                            ) { Text("刷新座位", style = MaterialTheme.typography.labelSmall) }
                                        }
                                        TextButton(
                                            onClick = { bookingResult = null },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) { Text("关闭", style = MaterialTheme.typography.labelSmall) }
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
                        OutlinedTextField(
                            value = seatInput,
                            onValueChange = { seatInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("座位号（如 A101）") },
                            textStyle = MaterialTheme.typography.bodySmall
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 当前预约 ──
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (myBooking != null) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, Modifier.size(20.dp),
                            tint = if (myBooking != null) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            if (myBooking != null) {
                                Text(
                                    buildString { append("当前预约"); myBooking?.seatId?.let { append("：$it") } },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                val subInfo = buildString {
                                    myBooking?.area?.let { append(it) }
                                    myBooking?.statusText?.let { if (isNotEmpty()) append(" · "); append(it) }
                                }
                                if (subInfo.isNotBlank()) Text(
                                    subInfo, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            } else {
                                Text("暂无预约", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val actions = myBooking?.actionUrls?.filter { (label, _) ->
                        // 换座功能已内置于选座流程，隐藏多余的"我想换座"按钮
                        "换座" !in label
                    }
                    if (!actions.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.forEach { (label, url) ->
                                FilledTonalButton(
                                    onClick = { executeBookingAction(label, url) },
                                    enabled = !isBooking,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if ("取消" in label) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }

            // ── 楼层 ──
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                floors.forEach { floor ->
                    AppFilterChip(
                        selected = floor == selectedFloor,
                        onClick = { selectedFloor = floor },
                        label = floor
                    )
                }
            }

            // ── 区域 ──
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                areas.forEach { area ->
                    AppFilterChip(
                        selected = area == selectedArea,
                        onClick = { selectedArea = area },
                        label = area
                    )
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
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { loadSeats() }) { Text("重试") }
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
                        Text("该区域暂无座位数据", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                else -> {
                    // 统计栏
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("共 $totalCount 座", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(" $availableCount 可用 ", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(" ${totalCount - availableCount} 已占 ", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 收藏入口
                        if (favorites.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text(" ★ ${favorites.size} ", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }

                    // 座位网格
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 56.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 收藏座位快捷区
                        val favInArea = seats.filter { it.seatId in favorites }
                        if (favInArea.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.padding(bottom = 4.dp)) {
                                    Text("★ 收藏座位", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        favInArea.forEach { seat ->
                                            val avail = seat.available
                                            SuggestionChip(
                                                onClick = { if (avail) bookSeat(seat.seatId) },
                                                label = {
                                                    Text(
                                                        seat.seatId,
                                                        fontWeight = if (avail) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (avail) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    )
                                                },
                                                icon = {
                                                    Box(Modifier.size(8.dp).background(
                                                        if (avail) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ))
                                                }
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
        isFavorite && seat.available -> MaterialTheme.colorScheme.tertiaryContainer
        seat.available -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        isFavorite && seat.available -> MaterialTheme.colorScheme.onTertiaryContainer
        seat.available -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    val borderColor = when {
        isFavorite -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
        seat.available -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(if (isFavorite) 1.dp else 0.5.dp, borderColor, RoundedCornerShape(6.dp))
            .then(
                if (!isBooking)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .animateContentSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isFavorite) {
                Icon(Icons.Default.Star, null, Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                seat.seatId,
                fontSize = 11.sp,
                fontWeight = if (seat.available) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
