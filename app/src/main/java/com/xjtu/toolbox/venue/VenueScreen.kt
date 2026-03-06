package com.xjtu.toolbox.venue

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.VenueLogin
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 体育场馆预订主页面
 *
 * 流程：场馆列表 → 选择场馆 → 日期选择 + 时段网格 → 确认 → 滑动验证码 → 预订结果
 */
@Composable
fun VenueScreen(login: VenueLogin, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { VenueApi(login) }
    val context = LocalContext.current

    val favoritesManager = remember { VenueFavorites(context) }
    val favoriteIds by favoritesManager.favoriteIds.collectAsState()

    val showFavoriteToast = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showFavoriteToast.value) {
        showFavoriteToast.value?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            showFavoriteToast.value = null
        }
    }

    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("venue_hint_shown", false)) }
    if (showHint.value) {
        BackHandler { showHint.value = false; prefs.edit().putBoolean("venue_hint_shown", true).apply() }
        SuperBottomSheet(
            show = showHint,
            title = "功能说明",
            onDismissRequest = {
                showHint.value = false
                prefs.edit().putBoolean("venue_hint_shown", true).apply()
            }
        ) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Text(
                    "场馆预约功能仅提供时段查询与预约操作。",
                    style = MiuixTheme.textStyles.body1
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "• 不会提供自动抢选功能\n• 不会接入支付流程\n\n望理解，请尽量在校园网环境下使用。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        showHint.value = false
                        prefs.edit().putBoolean("venue_hint_shown", true).apply()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了") }
            }
        }
    }

    // ─── 导航状态 ───
    var currentPage by remember { mutableStateOf<VenuePage>(VenuePage.VenueList) }

    // ─── 场馆列表 ───
    var venues by remember { mutableStateOf<List<VenueApi.Venue>>(emptyList()) }
    var venueLoading by remember { mutableStateOf(true) }
    var venueError by remember { mutableStateOf<String?>(null) }

    // ─── 时段选择 ───
    var selectedVenue by remember { mutableStateOf<VenueApi.Venue?>(null) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var availableSlots by remember { mutableStateOf<List<VenueApi.AreaSlot>>(emptyList()) }
    var lockedSlots by remember { mutableStateOf<List<VenueApi.AreaSlot>>(emptyList()) }
    var slotsLoading by remember { mutableStateOf(false) }
    var slotsError by remember { mutableStateOf<String?>(null) }
    var selectedSlots by remember { mutableStateOf<Set<VenueApi.AreaSlot>>(emptySet()) }

    // ─── 预订 ───
    var bookingInProgress by remember { mutableStateOf(false) }
    var bookingResult by remember { mutableStateOf<VenueApi.BookingResult?>(null) }
    val showCaptchaDialog = remember { mutableStateOf(false) }
    var captchaData by remember { mutableStateOf<VenueApi.CaptchaData?>(null) }
    var captchaLoading by remember { mutableStateOf(false) }
    var captchaError by remember { mutableStateOf<String?>(null) }
    val showResultDialog = remember { mutableStateOf(false) }

    // ─── 加载函数 ───
    fun loadVenues() {
        venueLoading = true; venueError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.fetchVenueList() }
                venues = result
            } catch (e: Exception) {
                venueError = e.message ?: "加载场馆列表失败"
            } finally { venueLoading = false }
        }
    }

    fun loadSlots() {
        slotsLoading = true; slotsError = null; selectedSlots = emptySet()
        scope.launch {
            try {
                val date = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val venueId = selectedVenue!!.id
                val (ok, locked) = withContext(Dispatchers.IO) {
                    val ok = api.fetchAvailableSlots(venueId, date)
                    val locked = api.fetchLockedSlots(venueId, date)
                    ok to locked
                }
                availableSlots = ok
                lockedSlots = locked
            } catch (e: Exception) {
                slotsError = e.message ?: "加载时段失败"
            } finally { slotsLoading = false }
        }
    }

    fun doBooking(sliderResult: SliderResult) {
        val venue = selectedVenue ?: return
        val captcha = captchaData ?: return
        bookingInProgress = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.submitBooking(
                        serviceid = venue.id,
                        selections = selectedSlots.toList(),
                        captchaId = captcha.id,
                        sliderTrackJson = sliderResult.toJson()
                    )
                }
                bookingResult = result
                showCaptchaDialog.value = false
                showResultDialog.value = true
            } catch (e: Exception) {
                bookingResult = VenueApi.BookingResult(false, message = e.message ?: "预订失败")
                showCaptchaDialog.value = false
                showResultDialog.value = true
            } finally { bookingInProgress = false }
        }
    }

    fun loadCaptcha() {
        captchaLoading = true; captchaError = null
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { api.generateCaptcha() }
                captchaData = data

                // 尝试自动解题
                val targetX = withContext(Dispatchers.Default) {
                    autoSolveCaptcha(
                        data.backgroundImage, data.sliderImage,
                        data.bgWidth, data.bgHeight
                    )
                }
                if (targetX != null) {
                    // 自动解题成功 → 生成仿人轨迹并提交
                    val track = generateHumanLikeTrack(targetX)
                    val now = java.time.Instant.now()
                    val start = now.minusMillis(track.last().t + 500)
                    val fmt = java.time.format.DateTimeFormatter.ISO_INSTANT
                    val slHeight = (data.sliderHeight * 260.0 / data.bgWidth).toInt()
                    val result = SliderResult(
                        bgImageWidth = 260,
                        bgImageHeight = 0,
                        sliderImageWidth = 0,
                        sliderImageHeight = slHeight,
                        startSlidingTime = fmt.format(start),
                        entSlidingTime = fmt.format(now),
                        trackList = track
                    )
                    captchaLoading = false
                    doBooking(result)
                    return@launch
                }
                // 自动解题失败 → 显示手动滑块
            } catch (e: Exception) {
                captchaError = e.message ?: "获取验证码失败"
            } finally { captchaLoading = false }
        }
    }

    // 初始加载
    LaunchedEffect(Unit) { loadVenues() }

    // 切换日期/场馆时重新加载时段
    LaunchedEffect(selectedVenue, selectedDate) {
        if (selectedVenue != null && currentPage is VenuePage.SlotSelection) {
            loadSlots()
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = when (currentPage) {
                    VenuePage.VenueList -> "场馆预订"
                    is VenuePage.SlotSelection -> selectedVenue?.name ?: "选择时段"
                },
                largeTitle = when (currentPage) {
                    VenuePage.VenueList -> "场馆预订"
                    is VenuePage.SlotSelection -> selectedVenue?.name ?: "选择时段"
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = {
                        when (currentPage) {
                            VenuePage.VenueList -> onBack()
                            is VenuePage.SlotSelection -> {
                                currentPage = VenuePage.VenueList
                                selectedSlots = emptySet()
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (currentPage is VenuePage.SlotSelection) {
                        IconButton(onClick = { loadSlots() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    } else {
                        IconButton(onClick = { loadVenues() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState is VenuePage.SlotSelection) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "VenuePage"
        ) { page ->
            when (page) {
                VenuePage.VenueList -> VenueListContent(
                    venues = venues,
                    isLoading = venueLoading,
                    error = venueError,
                    onRetry = { loadVenues() },
                    onVenueSelected = { venue ->
                        selectedVenue = venue
                        currentPage = VenuePage.SlotSelection
                        loadSlots()
                    },
                    favoriteIds = favoriteIds,
                    onToggleFavorite = { venue ->
                        val isFavorite = favoritesManager.toggleFavorite(venue.id)
                        showFavoriteToast.value = if (isFavorite) "已收藏 ${venue.name}" else "已取消收藏 ${venue.name}"
                    },
                    modifier = Modifier.padding(padding),
                    scrollBehavior = scrollBehavior
                )

                is VenuePage.SlotSelection -> SlotSelectionContent(
                    venue = selectedVenue!!,
                    date = selectedDate,
                    onDateChange = { selectedDate = it },
                    availableSlots = availableSlots,
                    lockedSlots = lockedSlots,
                    selectedSlots = selectedSlots,
                    onToggleSlot = { slot ->
                        selectedSlots = if (slot in selectedSlots) selectedSlots - slot else selectedSlots + slot
                    },
                    isLoading = slotsLoading,
                    error = slotsError,
                    onRetry = { loadSlots() },
                    onConfirm = {
                        loadCaptcha()
                        showCaptchaDialog.value = true
                    },
                    modifier = Modifier.padding(padding),
                    scrollBehavior = scrollBehavior
                )
            }
        }
    }

    // ─── 验证码弹窗 ───
    if (showCaptchaDialog.value) {
        BackHandler { showCaptchaDialog.value = false }
        SuperDialog(
            title = "滑动验证",
            show = showCaptchaDialog,
            onDismissRequest = {
                showCaptchaDialog.value = false
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    captchaLoading -> {
                        Spacer(Modifier.height(32.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("加载验证码...", style = MiuixTheme.textStyles.body2)
                        Spacer(Modifier.height(32.dp))
                    }
                    captchaError != null -> {
                        Text(captchaError!!, color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { loadCaptcha() }) { Text("重试") }
                    }
                    captchaData != null && !bookingInProgress -> {
                        SliderCaptchaView(
                            backgroundImageBase64 = captchaData!!.backgroundImage,
                            sliderImageBase64 = captchaData!!.sliderImage,
                            bgOriginalWidth = captchaData!!.bgWidth,
                            bgOriginalHeight = captchaData!!.bgHeight,
                            sliderOriginalWidth = captchaData!!.sliderWidth,
                            sliderOriginalHeight = captchaData!!.sliderHeight,
                            onSlideComplete = { result -> doBooking(result) }
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(text = "换一张", onClick = { loadCaptcha() })
                    }
                    bookingInProgress -> {
                        Spacer(Modifier.height(32.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("正在预订...", style = MiuixTheme.textStyles.body2)
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    // ─── 预订结果弹窗 ───
    if (showResultDialog.value && bookingResult != null) {
        BackHandler {
            showResultDialog.value = false
            if (bookingResult!!.success) { selectedSlots = emptySet(); loadSlots() }
        }
        SuperDialog(
            title = if (bookingResult!!.success) "预订成功" else "预订失败",
            show = showResultDialog,
            onDismissRequest = {
                showResultDialog.value = false
                if (bookingResult!!.success) {
                    selectedSlots = emptySet()
                    loadSlots()
                }
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val result = bookingResult!!
                if (result.success) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                    Text(result.message, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
                    if (result.orderId != null) {
                        Text("订单号: ${result.orderId}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (result.price > 0) {
                        Text("金额: ¥${"%.1f".format(result.price)}", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请前往「移动交通大学」App 完成支付",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MiuixTheme.colorScheme.error
                    )
                    Text(result.message, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        showResultDialog.value = false
                        if (result.success) {
                            selectedSlots = emptySet()
                            loadSlots()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("确定") }
            }
        }
    }
}

// ─── 页面状态 ───
private sealed class VenuePage {
    data object VenueList : VenuePage()
    data object SlotSelection : VenuePage()
}

// ─── 场馆列表页 ───
@Composable
private fun VenueListContent(
    venues: List<VenueApi.Venue>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onVenueSelected: (VenueApi.Venue) -> Unit,
    favoriteIds: Set<Int>,
    onToggleFavorite: (VenueApi.Venue) -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior
) {
    val sortedVenues = remember(venues, favoriteIds) {
        venues.sortedByDescending { it.id in favoriteIds }
    }

    when {
        isLoading -> LoadingState(
            message = "加载场馆列表...",
            modifier = modifier.fillMaxSize()
        )
        error != null -> ErrorState(
            message = error,
            onRetry = onRetry,
            modifier = modifier.fillMaxSize()
        )
        sortedVenues.isEmpty() -> EmptyState(
            title = "暂无可预订场馆",
            modifier = modifier.fillMaxSize()
        )
        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedVenues, key = { it.id }) { venue ->
                VenueCard(
                    venue = venue,
                    isFavorite = venue.id in favoriteIds,
                    onClick = { onVenueSelected(venue) },
                    onDoubleClick = { onToggleFavorite(venue) }
                )
            }
        }
    }
}

@Composable
private fun VenueCard(
    venue: VenueApi.Venue,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    var showFavoriteAnimation by remember { mutableStateOf(false) }
    
    LaunchedEffect(isFavorite) {
        if (isFavorite) {
            showFavoriteAnimation = true
        }
    }

    val favoriteScale by animateFloatAsState(
        targetValue = if (showFavoriteAnimation) 1.3f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        finishedListener = { showFavoriteAnimation = false }
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onDoubleClick = {
                    onDoubleClick()
                }
            ),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MiuixTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    venue.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium
                )
                if (!venue.address.isNullOrBlank()) {
                    Text(
                        venue.address,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
            AnimatedVisibility(
                visible = isFavorite,
                enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "已收藏",
                    modifier = Modifier
                        .size(24.dp)
                        .scale(favoriteScale),
                    tint = Color(0xFFE91E63)
                )
            }
        }
    }
}

// ─── 时段选择页 ───
@Composable
private fun SlotSelectionContent(
    venue: VenueApi.Venue,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    availableSlots: List<VenueApi.AreaSlot>,
    lockedSlots: List<VenueApi.AreaSlot>,
    selectedSlots: Set<VenueApi.AreaSlot>,
    onToggleSlot: (VenueApi.AreaSlot) -> Unit,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 日期选择栏
        DateSelector(
            selectedDate = date,
            onDateChange = onDateChange
        )

        when {
            isLoading -> LoadingState(
                message = "加载可用时段...",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            error != null -> ErrorState(
                message = error,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            availableSlots.isEmpty() -> EmptyState(
                title = "该日期暂无可预订时段",
                subtitle = "请尝试其他日期",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            else -> {
                // 按时段分组（同一时段可能有多个场地）
                val slotsByTime = remember(availableSlots) {
                    availableSlots.groupBy { it.timeSlot }.toSortedMap()
                }
                val lockedSet = remember(lockedSlots) {
                    lockedSlots.map { it.areaId to it.timeSlot }.toSet()
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    slotsByTime.forEach { (timeSlot, slots) ->
                        item(key = timeSlot) {
                            TimeSlotGroup(
                                timeSlot = timeSlot,
                                slots = slots,
                                lockedSet = lockedSet,
                                selectedSlots = selectedSlots,
                                onToggleSlot = onToggleSlot
                            )
                        }
                    }
                    // 底部留白给确认按钮
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }

        // 底部确认栏
        AnimatedVisibility(
            visible = selectedSlots.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "已选 ${selectedSlots.size} 个时段",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium
                        )
                        val totalPrice = selectedSlots.sumOf { it.price }
                        Text(
                            "合计 ¥${"%.1f".format(totalPrice)}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(onClick = onConfirm) {
                        Text("确认预订")
                    }
                }
            }
        }
    }
}

// ─── 日期选择器 ───
@Composable
private fun DateSelector(
    selectedDate: LocalDate,
    onDateChange: (LocalDate) -> Unit
) {
    val today = remember { LocalDate.now() }
    // 显示未来 7 天
    val dates = remember { (0..6).map { today.plusDays(it.toLong()) } }
    val dayNames = remember {
        listOf("今天", "明天", "后天").plus(
            (3..6).map { today.plusDays(it.toLong()).dayOfWeek.let { d ->
                when (d) {
                    java.time.DayOfWeek.MONDAY -> "周一"
                    java.time.DayOfWeek.TUESDAY -> "周二"
                    java.time.DayOfWeek.WEDNESDAY -> "周三"
                    java.time.DayOfWeek.THURSDAY -> "周四"
                    java.time.DayOfWeek.FRIDAY -> "周五"
                    java.time.DayOfWeek.SATURDAY -> "周六"
                    java.time.DayOfWeek.SUNDAY -> "周日"
                }
            }}
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        dates.forEachIndexed { index, date ->
            val isSelected = date == selectedDate
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDateChange(date) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        dayNames[index],
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isSelected) MiuixTheme.colorScheme.onPrimary
                               else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        "${date.monthValue}/${date.dayOfMonth}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (isSelected) MiuixTheme.colorScheme.onPrimary
                               else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

// ─── 时段分组卡片 ───
@Composable
private fun TimeSlotGroup(
    timeSlot: String,
    slots: List<VenueApi.AreaSlot>,
    lockedSet: Set<Pair<Long, String>>,
    selectedSlots: Set<VenueApi.AreaSlot>,
    onToggleSlot: (VenueApi.AreaSlot) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 时段标题 + 价格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    timeSlot,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                val price = slots.firstOrNull()?.price ?: 0.0
                Text(
                    "¥${"%.0f".format(price)}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(8.dp))

            // 场地网格
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                slots.forEach { slot ->
                    val isLocked = (slot.areaId to slot.timeSlot) in lockedSet
                    val isSelected = slot in selectedSlots
                    val canSelect = slot.isAvailable && !isLocked

                    SlotChip(
                        areaName = slot.areaName,
                        isAvailable = canSelect,
                        isSelected = isSelected,
                        onClick = { if (canSelect) onToggleSlot(slot) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotChip(
    areaName: String,
    isAvailable: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = when {
        isSelected -> MiuixTheme.colorScheme.primary
        isAvailable -> MiuixTheme.colorScheme.surface
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        isSelected -> MiuixTheme.colorScheme.onPrimary
        isAvailable -> MiuixTheme.colorScheme.onSurface
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val borderColor = when {
        isSelected -> MiuixTheme.colorScheme.primary
        isAvailable -> MiuixTheme.colorScheme.outline
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = isAvailable) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            areaName,
            style = MiuixTheme.textStyles.body2,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
