package com.xjtu.toolbox.venue

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.staggeredgrid.items
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.adaptive.readableWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.glass.LocalOnGlassBar
import com.xjtu.toolbox.ui.components.AppTabPager
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.data.CredentialStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import com.xjtu.toolbox.nav.AppRoute

/**
 * 体育场馆预订主页面
 *
 * 流程：场馆列表 → 选择场馆 → 日期选择 + 时段网格 → 确认 → 滑动验证码 → 预订结果
 */
@Composable
fun VenueScreen(
    site: SiteSession,
    credentialStore: CredentialStore,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val context = LocalContext.current
    val vm: VenueViewModel = viewModel(key = "venue-${System.identityHashCode(site)}") {
        VenueViewModel(site) { credentialStore.venueAutoSolveCaptchaEnabled }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                VenueEvent.AuthExpired -> appLoginState.handleAuthExpired(AppRoute.Venue, onBack)
                is VenueEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val favoritesManager = remember { VenueFavorites(context) }
    val favoriteIds by favoritesManager.favoriteIds.collectAsStateWithLifecycle()

    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("venue_hint_shown", false)) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showBookingConfirm by remember { mutableStateOf(false) }
    var orderDetail by remember { mutableStateOf<VenueApi.OrderInfo?>(null) }
    var cancelTarget by remember { mutableStateOf<VenueApi.OrderInfo?>(null) }
    var payTarget by remember { mutableStateOf<VenueApi.OrderInfo?>(null) }

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    // 「场馆预订」「我的订单」两个顶层标签共用的选中回调：标签行点击、
    // 翻页器滑动停稳都会走这里，切到「我的订单」时顺带首次拉一页。
    val onSelectTab: (Int) -> Unit = { index ->
        selectedTab = index
        if (index == 1) vm.loadOrdersOnce()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = if (selectedTab == 1) "我的订单" else when (vm.page) {
                    VenuePage.VenueList -> "场馆预订"
                    VenuePage.SlotSelection -> vm.selectedVenue?.name ?: "选择时段"
                },
                largeTitle = if (selectedTab == 1) "我的订单" else when (vm.page) {
                    VenuePage.VenueList -> "场馆预订"
                    VenuePage.SlotSelection -> vm.selectedVenue?.name ?: "选择时段"
                },
                color = glassBarColor(glass),
                modifier = Modifier.glassTopBar(glass),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTab == 1) {
                            selectedTab = 0
                        } else {
                            when (vm.page) {
                                VenuePage.VenueList -> onBack()
                                VenuePage.SlotSelection -> vm.closeVenue()
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 分段标签、选日期这些不滚动的头部挂在顶栏里，和顶栏一起做一整块玻璃，
                // 列表从它们下面滚过去（压在玻璃后面的是列表，不是这几行按钮）
                bottomContent = {
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                        Column {
                            AppSegmentedTabs(
                                tabs = listOf("场馆预订", "我的订单"),
                                selectedTabIndex = selectedTab,
                                onTabSelected = onSelectTab,
                                // 宽屏限宽居中：两格标签拉满整个平板宽度不好点
                                modifier = Modifier.readableWidth(),
                            )
                            // 选时段时的日期条。放在下拉刷新外面，避免和横向选日抢手势
                            val venue = vm.selectedVenue
                            AnimatedVisibility(
                                visible = selectedTab == 0 && vm.page == VenuePage.SlotSelection && venue != null,
                            ) {
                                if (venue != null) {
                                    DateSelector(
                                        selectedDate = vm.selectedDate,
                                        onDateChange = vm::selectDate,
                                        advanceDay = venue.advanceDay,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)

        // ── 首次使用提示 ──
        //
        // 必须放在 Scaffold 的 content 里：miuix 0.9.3 起 Overlay* 注册进 LocalDialogStates，
        // 而该 CompositionLocal 只有 Scaffold 提供。写在 Scaffold 外面会注册进一个没有宿主的
        // 空列表，无宿主渲染，不报错也不崩溃，就是不显示。
        if (showHint.value) {
            BackHandler { showHint.value = false; prefs.edit().putBoolean("venue_hint_shown", true).apply() }
            OverlayDialog(
                show = showHint.value,
                title = "功能说明",
                summary = "场馆预约支持时段查询、预约和订单管理。",
                onDismissRequest = {
                    showHint.value = false
                    prefs.edit().putBoolean("venue_hint_shown", true).apply()
                }
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "• 验证码默认自动识别，可在设置中关闭；失败仍可手滑\n" +
                            "• 支付和登录会在系统浏览器中完成\n\n" +
                            "望理解，请尽量在校园网环境下使用。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = "知道了",
                        onClick = {
                            showHint.value = false
                            prefs.edit().putBoolean("venue_hint_shown", true).apply()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
        ) {
            // 标签行在顶栏里（bottomContent），这里的列表各自把 glassTop 放进 contentPadding
            AppTabPager(
                pageCount = 2,
                selectedTabIndex = selectedTab,
                onTabSelected = onSelectTab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                // 「场馆预订」栏内部还有一层「场馆列表 → 选时段」的子导航；
                // 只在停在场馆列表时允许横滑切顶层标签，选时段中途横滑容易和
                // 返回按钮的语义（回到场馆列表）打架，所以关掉。
                swipeEnabled = vm.page == VenuePage.VenueList,
            ) { tabPage ->
                if (tabPage == 1) {
                    VenueOrdersContent(
                        orders = vm.orders,
                        isLoading = vm.ordersLoading,
                        isLoadingMore = vm.ordersLoadingMore,
                        error = vm.ordersError,
                        hasMore = vm.ordersHasMore,
                        onRetry = { vm.loadOrders(reset = true) },
                        onRefresh = { vm.loadOrders(reset = true) },
                        onLoadMore = { vm.loadOrders(reset = false) },
                        onDetail = { orderDetail = it },
                        onCancel = { cancelTarget = it },
                        onPay = { payTarget = it },
                        modifier = Modifier.fillMaxSize(),
                        scrollBehavior = scrollBehavior,
                        topPadding = glassTop,
                    )
                } else {
                    AnimatedContent(
                        targetState = vm.page,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (targetState == VenuePage.SlotSelection) {
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
                                venues = vm.venues,
                                isLoading = vm.venueLoading,
                                isRefreshing = vm.venueRefreshing,
                                error = vm.venueError,
                                onRetry = { vm.loadVenues() },
                                onRefresh = { vm.loadVenues(silent = true) },
                                onVenueSelected = vm::openVenue,
                                favoriteIds = favoriteIds,
                                onToggleFavorite = { venue ->
                                    val isFavorite = favoritesManager.toggleFavorite(venue.id)
                                    Toast.makeText(context, if (isFavorite) "已收藏 ${venue.name}" else "已取消收藏 ${venue.name}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxSize(),
                                scrollBehavior = scrollBehavior,
                                topPadding = glassTop,
                            )

                            VenuePage.SlotSelection -> SlotSelectionContent(
                                availableSlots = vm.availableSlots,
                                selectedSlots = vm.selectedSlots,
                                onToggleSlot = vm::toggleSlot,
                                isLoading = vm.slotsLoading,
                                isRefreshing = vm.slotsRefreshing,
                                error = vm.slotsError,
                                onRetry = { vm.loadSlots() },
                                onRefresh = { vm.loadSlots(silent = true) },
                                onConfirm = { showBookingConfirm = true },
                                modifier = Modifier.fillMaxSize(),
                                scrollBehavior = scrollBehavior,
                                topPadding = glassTop,
                            )
                        }
                    }
                }
            }
        }

        // 弹窗必须写在 Scaffold 的 content 里：miuix 0.9.3 的 Overlay* 默认
        // renderInRootScaffold=true，靠 Scaffold 提供的 LocalDialogStates 注册、
        // 由 Scaffold 内部的 MiuixPopupHost 渲染。放在 Scaffold 外面（与它平级）时
        // 拿到的是静态默认空列表，弹窗会被静默丢弃——不报错、不崩溃、就是不显示。

        // ─── 预约确认 ───
        if (showBookingConfirm) {
            BackHandler { showBookingConfirm = false }
            OverlayDialog(
                title = "确认预订",
                show = true,
                onDismissRequest = { showBookingConfirm = false }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "确定预订以下 ${vm.selectedSlots.size} 个时段吗？",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium
                    )
                    vm.selectedSlots.sortedWith(compareBy({ it.date }, { it.timeSlot }, { it.areaName }))
                        .forEach { slot ->
                            Text(
                                listOf(slot.date, slot.timeSlot, slot.areaName)
                                    .filter { it.isNotBlank() }
                                    .joinToString("  "),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    Text(
                        "合计：¥${"%.2f".format(vm.selectedSlots.sumOf { it.price })}",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "再看看",
                            onClick = { showBookingConfirm = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "继续预约",
                            onClick = { showBookingConfirm = false; vm.startBooking() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }

        // ─── 验证码弹窗 ───
        if (vm.showCaptcha) {
            BackHandler(onBack = vm::closeCaptcha)
            OverlayDialog(
                title = "滑动验证",
                show = true,
                onDismissRequest = vm::closeCaptcha,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        vm.bookingInProgress -> {
                            Spacer(Modifier.height(32.dp))
                            com.xjtu.toolbox.ui.components.MorphingLoader()  // 整页加载统一用形变加载器
                            Spacer(Modifier.height(8.dp))
                            Text("正在预订...", style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(32.dp))
                        }
                        vm.captchaLoading -> {
                            Spacer(Modifier.height(32.dp))
                            com.xjtu.toolbox.ui.components.MorphingLoader()  // 整页加载统一用形变加载器
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (vm.captchaAutoSolving) "正在自动识别验证码..." else "加载验证码...",
                                style = MiuixTheme.textStyles.body2
                            )
                            Spacer(Modifier.height(32.dp))
                        }
                        vm.captchaError != null -> {
                            Text(vm.captchaError!!, color = MiuixTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            TextButton(text = "重试", onClick = vm::startBooking)
                        }
                        vm.captchaData != null -> {
                            val captcha = vm.captchaData!!
                            vm.captchaNotice?.let { notice ->
                                Text(
                                    notice,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote1
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            SliderCaptchaView(
                                backgroundImageBase64 = captcha.backgroundImage,
                                sliderImageBase64 = captcha.sliderImage,
                                bgOriginalWidth = captcha.bgWidth,
                                bgOriginalHeight = captcha.bgHeight,
                                sliderOriginalWidth = captcha.sliderWidth,
                                sliderOriginalHeight = captcha.sliderHeight,
                                onSlideComplete = vm::submitBooking
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(text = "换一张", onClick = vm::reloadCaptcha)
                        }
                    }
                }
            }
        }

        // ─── 预订结果弹窗 ───
        vm.bookingResult?.let { result ->
            BackHandler { vm.dismissResult(refresh = true) }
            OverlayDialog(
                title = if (result.success) "预订成功" else "预订失败",
                show = true,
                onDismissRequest = { vm.dismissResult(refresh = true) },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                            if (result.price > 0) "订单需要支付，可在订单页继续操作" else "订单已提交",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (result.price > 0) MiuixTheme.colorScheme.error
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                    if (result.success && result.orderId != null && result.price > 0) {
                        TextButton(
                            text = "去支付",
                            onClick = {
                                vm.dismissResult(refresh = false)
                                payTarget = VenueApi.OrderInfo(
                                    orderId = result.orderId,
                                    status = 0,
                                    createdAt = "",
                                    price = result.price,
                                    details = emptyList()
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    TextButton(
                        text = "确定",
                        onClick = { vm.dismissResult(refresh = true) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // ─── 订单详情 ───
        orderDetail?.let { order ->
            BackHandler { orderDetail = null }
            OverlayDialog(
                title = "订单详情",
                show = true,
                onDismissRequest = { orderDetail = null }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("订单号：${order.orderId}", style = MiuixTheme.textStyles.body2)
                    Text("状态：${order.statusText}", style = MiuixTheme.textStyles.body2)
                    if (order.createdAt.isNotBlank()) {
                        Text("下单时间：${order.createdAt}", style = MiuixTheme.textStyles.body2)
                    }
                    if (order.venueName.isNotBlank()) {
                        Text("场馆：${order.venueName}", style = MiuixTheme.textStyles.body2)
                    }
                    Spacer(Modifier.height(2.dp))
                    if (order.details.isEmpty()) {
                        Text(
                            "暂无场地明细",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    } else {
                        order.details.forEachIndexed { index, detail ->
                            val line = listOf(
                                detail.date,
                                detail.timeSlot,
                                detail.areaName
                            ).filter { it.isNotBlank() }.joinToString("  ")
                            Text(
                                "${index + 1}. ${line.ifBlank { "场地明细" }}  ¥${"%.2f".format(detail.price)}",
                                style = MiuixTheme.textStyles.body2
                            )
                        }
                    }
                    Text(
                        "合计：¥${"%.2f".format(order.price)}",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        if (order.canPay) {
                            TextButton(
                                text = "去支付",
                                onClick = {
                                    orderDetail = null
                                    payTarget = order
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                        if (order.canPay && order.canCancel) {
                            Spacer(Modifier.width(20.dp))
                        }
                        if (order.canCancel) {
                            TextButton(
                                text = "取消订单",
                                onClick = {
                                    orderDetail = null
                                    cancelTarget = order
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColors(
                                    textColor = MiuixTheme.colorScheme.error
                                )
                            )
                        }
                        if (!order.canPay && !order.canCancel) {
                            TextButton(
                                text = "关闭",
                                onClick = { orderDetail = null },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // ─── 取消确认 ───
        cancelTarget?.let { order ->
            BackHandler { if (!vm.orderActionLoading) cancelTarget = null }
            OverlayDialog(
                title = "取消订单",
                show = true,
                onDismissRequest = { if (!vm.orderActionLoading) cancelTarget = null }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "确定要取消订单 ${order.orderId} 吗？",
                        style = MiuixTheme.textStyles.body2
                    )
                    Text(
                        "已支付金额将按系统规则原路退回，一般需要 3 个工作日。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "再想想",
                            onClick = { cancelTarget = null },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "确认取消",
                            onClick = { cancelTarget = null; vm.cancelOrder(order) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(
                                textColor = MiuixTheme.colorScheme.error
                            )
                        )
                    }
                }
            }
        }

        // ─── 支付登录引导 ───
        payTarget?.let { order ->
            BackHandler { payTarget = null }
            OverlayDialog(
                title = "去支付",
                show = true,
                onDismissRequest = { payTarget = null }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "订单尚未支付，请先在浏览器中登录，再前往支付。",
                        style = MiuixTheme.textStyles.body2
                    )
                    Text(
                        "订单号：${order.orderId}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "去登录",
                            onClick = { openExternalUrl(VenueApi.BROWSER_LOGIN_URL) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "去支付",
                            onClick = {
                                payTarget = null
                                openExternalUrl(vm.api.paymentUrl(order.orderId))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    }
}

// ─── 场馆列表页 ───
@Composable
private fun VenueListContent(
    venues: List<VenueApi.Venue>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onVenueSelected: (VenueApi.Venue) -> Unit,
    favoriteIds: Set<Int>,
    onToggleFavorite: (VenueApi.Venue) -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior,
    /** 玻璃顶栏（含标签行）的高度，放进列表顶部留白。 */
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val sortedVenues = remember(venues, favoriteIds) {
        venues.sortedByDescending { it.id in favoriteIds }
    }
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefresh(
        refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        pullToRefreshState = pullToRefreshState,
        topAppBarScrollBehavior = scrollBehavior,
        contentPadding = PaddingValues(top = topPadding),
        modifier = modifier.fillMaxSize()
    ) {
    when {
        isLoading -> LazyColumn(Modifier.fillMaxSize().padding(top = topPadding)) {
            item { Box(Modifier.fillParentMaxSize()) { LoadingState(message = "加载场馆列表...", modifier = Modifier.fillMaxSize()) } }
        }
        error != null && venues.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(top = topPadding)) {
            item { Box(Modifier.fillParentMaxSize()) { ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize()) } }
        }
        sortedVenues.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(top = topPadding)) {
            item { Box(Modifier.fillParentMaxSize()) { EmptyState(title = "暂无可预订场馆", modifier = Modifier.fillMaxSize()) } }
        }
        // 宽屏场馆卡分两三列（见 AdaptiveCardGrid）
        else -> com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + topPadding, bottom = 8.dp),
            spacing = 8.dp,
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
}

@Composable
private fun VenueCard(
    venue: VenueApi.Venue,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    var showFavoriteAnimation by remember { mutableStateOf(false) }
    var isInitialComposition by remember { mutableStateOf(true) }

    LaunchedEffect(isFavorite) {
        if (isInitialComposition) {
            isInitialComposition = false
            return@LaunchedEffect
        }
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
                val address = venue.address
                if (!address.isNullOrBlank()) {
                    Text(
                        address,
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
                        // 在 graphicsLayer 里读：动画期间只重画图标，不让整张场馆卡重组
                        .graphicsLayer { scaleX = favoriteScale; scaleY = favoriteScale },
                    tint = Color(0xFFE91E63)
                )
            }
        }
    }
}

// ─── 时段选择页 ───
@Composable
private fun SlotSelectionContent(
    availableSlots: List<VenueApi.AreaSlot>,
    selectedSlots: Set<VenueApi.AreaSlot>,
    onToggleSlot: (VenueApi.AreaSlot) -> Unit,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior,
    /** 玻璃顶栏（含标签行、日期条）的高度，放进列表顶部留白。日期条在顶栏里，见 VenueScreen。 */
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val pullToRefreshState = rememberPullToRefreshState()
    Column(modifier = modifier.fillMaxSize()) {
        PullToRefresh(
            refreshTexts = com.xjtu.toolbox.ui.components.AppRefreshTexts,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            pullToRefreshState = pullToRefreshState,
            topAppBarScrollBehavior = scrollBehavior,
            contentPadding = PaddingValues(top = topPadding),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
        when {
            isLoading -> LazyColumn(Modifier.fillMaxSize().padding(top = topPadding)) {
                item { Box(Modifier.fillParentMaxSize()) { LoadingState(message = "加载可用时段...", modifier = Modifier.fillMaxSize()) } }
            }
            error != null && availableSlots.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(top = topPadding)) {
                item { Box(Modifier.fillParentMaxSize()) { ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize()) } }
            }
            availableSlots.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(top = topPadding)) {
                item { Box(Modifier.fillParentMaxSize()) { EmptyState(title = "该日期暂无可预订时段", subtitle = "请尝试其他日期", modifier = Modifier.fillMaxSize()) } }
            }
            else -> {
                // 按时段分组（同一时段可能有多个场地）
                val slotsByTime = remember(availableSlots) {
                    availableSlots.groupBy { it.timeSlot }.toSortedMap()
                }

                // 宽屏一个时间段一张卡，分两三列（见 AdaptiveCardGrid）
                com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + topPadding, bottom = 8.dp),
                    spacing = 12.dp,
                ) {
                    slotsByTime.forEach { (timeSlot, slots) ->
                        item(key = timeSlot) {
                            TimeSlotGroup(
                                timeSlot = timeSlot,
                                slots = slots,
                                selectedSlots = selectedSlots,
                                onToggleSlot = onToggleSlot
                            )
                        }
                    }
                    // 底部留白给确认按钮
                    fullLineItem { Spacer(Modifier.height(72.dp)) }
                }
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
    onDateChange: (LocalDate) -> Unit,
    // 可提前几天由场馆自己给（productData 的 advanceday），不同场馆并不一样
    advanceDay: Int = 7,
) {
    val today = remember { LocalDate.now() }
    val span = advanceDay.coerceIn(1, 14)
    val dates = remember(span) { (0 until span).map { today.plusDays(it.toLong()) } }
    val dayNames = remember(span) {
        (0 until span).map { offset ->
            when (offset) {
                0 -> "今天"
                1 -> "明天"
                2 -> "后天"
                else -> when (today.plusDays(offset.toLong()).dayOfWeek) {
                    java.time.DayOfWeek.MONDAY -> "周一"
                    java.time.DayOfWeek.TUESDAY -> "周二"
                    java.time.DayOfWeek.WEDNESDAY -> "周三"
                    java.time.DayOfWeek.THURSDAY -> "周四"
                    java.time.DayOfWeek.FRIDAY -> "周五"
                    java.time.DayOfWeek.SATURDAY -> "周六"
                    java.time.DayOfWeek.SUNDAY -> "周日"
                }
            }
        }
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
                    val isSelected = slot in selectedSlots
                    val canSelect = slot.isAvailable

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
