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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.utils.SinkFeedback
import androidx.compose.foundation.layout.FlowRow
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import com.xjtu.toolbox.nav.AppRoute
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LibraryScreen(site: SiteSession, onBack: () -> Unit) {
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel(key = "library-${System.identityHashCode(site)}") {
        LibraryViewModel(context, site)
    }
    LaunchedEffect(vm) {
        vm.events.collect { appLoginState.handleAuthExpired(AppRoute.Library, onBack) }
    }

    // ── 首次使用提示 ──
    val prefs = remember { context.getSharedPreferences("feature_hints", Context.MODE_PRIVATE) }
    val showHint = remember { mutableStateOf(!prefs.getBoolean("library_hint_shown", false)) }

    // 预约结果自动消失
    LaunchedEffect(vm.bookingResult) {
        val result = vm.bookingResult ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (result.success) 4000L else 6000L)
        vm.bookingResult = null
    }

    // 预约状态一变就重排后台提醒：预约 / 换座 / 中途离开最后都会刷新 myBooking，盯结果比盯动作少漏
    LaunchedEffect(vm.myBooking?.actionUrls?.keys, vm.myBooking?.seatId) {
        com.xjtu.toolbox.notification.LibraryReminderScheduler.sync(context, vm.myBooking)
    }

    var confirmDialog by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val campus = vm.campus
    val selectedArea = vm.floorAreas[vm.selectedAreaCode] ?: vm.api.areaNameOf(vm.selectedAreaCode)
    val floors = remember(campus) { campus.floorCodes.map { campus.floorLabel(it) } }
    // 只滤掉明确关闭（有统计且 total=0）的区域；统计没到或学校没给的照常列出
    val areaCodes = remember(vm.floorAreas, vm.areaStatsMap) {
        vm.floorAreas.keys.filter { code -> vm.areaStatsMap[code]?.isOpen != false }
    }

    // 已有预约时先确认换座；跨校区不能直接换，只能先取消原预约
    // areaOverride：扫码时二维码上的区域码，页面选中的区域可能还没加载到那个区
    fun bookSeat(seatId: String, areaOverride: String? = null) {
        val booking = vm.myBooking
        val existing = booking?.seatId
        val isExpired = booking?.statusText?.let { "超时" in it || "过期" in it || "失效" in it } == true
        if (existing == null || isExpired) {
            vm.book(seatId, areaOverride)
            return
        }
        val bookedArea = booking.area
        if (vm.api.isForeignArea(bookedArea)) {
            confirmDialog = (
                "你在「$bookedArea」有预约（$existing），不在${campus.displayName}。\n" +
                    "跨校区不能直接换座，需要先取消原预约。\n是否现在取消？"
            ) to {
                val cancelUrl = vm.myBooking?.actionUrls?.get("取消预约")
                if (cancelUrl != null) vm.executeAction("取消预约", cancelUrl)
                else vm.bookingResult = BookResult(false, "没找到取消入口，请到「我的预约」里手动取消")
            }
            return
        }
        val area = bookedArea?.let { " ($it)" } ?: ""
        confirmDialog = "你已预约座位 $existing$area\n是否换座到 $seatId？" to { vm.swap(seatId, areaOverride) }
    }

    var seatScope by rememberSaveable { mutableStateOf("可用") }
    val seats = vm.seats
    val favorites = vm.favorites
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
            GlassTopAppBar(
                title = "图书馆座位",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
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
        val ss = vm.scanSeat
        BackHandler(enabled = ss != null) { vm.scanSeat = null }
        OverlayDialog(
            show = ss != null,
            title = "预约座位",
            summary = ss?.let { "${it.qr.areaName} · ${it.qr.seat} 号" },
            renderInRootScaffold = false,
            onDismissRequest = { vm.scanSeat = null },
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
                        onClick = { vm.scanSeat = null },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "预约",
                        enabled = !blocked && !vm.isBooking,
                        onClick = {
                            val qr = ss?.qr
                            vm.scanSeat = null
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
        LaunchedEffect(vm.isLoading, vm.isLoadingBooking) {
            if (!vm.isLoading && !vm.isLoadingBooking) isPullRefreshing = false
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
                vm.reload()
                vm.refreshMyBooking()
                vm.bookingResult = null
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
                vm.bookingResult != null,
                enter = androidx.compose.animation.scaleIn(
                    initialScale = 0.9f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f),
                ) + androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(
                        color = if (vm.bookingResult?.success == true) {
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
                            vm.bookingResult?.message ?: "",
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
                    color = if (vm.myBooking != null) {
                        MiuixTheme.colorScheme.secondaryContainer
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, Modifier.size(20.dp),
                            tint = if (vm.myBooking != null) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            if (vm.myBooking != null) {
                                Text(
                                    buildString { append("当前预约"); vm.myBooking?.seatId?.let { append("：$it") } },
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium
                                )
                                val subInfo = buildString {
                                    vm.myBooking?.area?.let { append(it) }
                                    vm.myBooking?.statusText?.let { if (isNotEmpty()) append(" · "); append(it) }
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
                    val isExpiredBooking = vm.myBooking?.statusText in LibraryApi.INACTIVE_STATUSES
                    val actions = if (isExpiredBooking) null else vm.myBooking?.actionUrls
                    if (!actions.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.filter { (label, _) -> "换座" !in label }.forEach { (label, url) ->
                                Button(
                                    onClick = {
                                        when {
                                            "取消" in label || "离开" in label -> {
                                                // 危险操作：弹二级确认
                                                confirmDialog = "确定要「$label」吗？" to { vm.executeAction(label, url) }
                                            }
                                            else -> vm.executeAction(label, url)
                                        }
                                    },
                                    enabled = !vm.isBooking,
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
                                onClick = { vm.switchCampus(c) },
                                label = c.displayName
                            )
                        }
                        if (vm.campusSwitching) {
                            CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                        }
                    }
                    val home = vm.homeCampus
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
                                selectedTabIndex = campus.floorCodes.indexOf(vm.selectedFloorCode).coerceAtLeast(0),
                                onTabSelected = { index ->
                                    campus.floorCodes.getOrNull(index)?.let { vm.loadFloor(it) }
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
                                    selected = vm.selectedAreaCode == code,
                                    onClick = { vm.selectArea(code) },
                                    label = vm.floorAreas[code] ?: code
                                )
                            }
                        }
                    }
                }
            }

            if (seats.isNotEmpty() || vm.viewMode == VIEW_PLAN) {
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
                                selected = vm.viewMode == mode,
                                onClick = { vm.changeViewMode(mode) },
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
                    if (vm.viewMode == VIEW_LIST) Row(
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
            AnimatedVisibility(visible = vm.isLoading && seats.isNotEmpty() && !isPullRefreshing) {
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
                    vm.isLoading && seats.isEmpty() -> fullSpan("loading") {
                        LoadingState(message = "正在查询座位…", modifier = Modifier.heightIn(min = 280.dp))
                    }

                    vm.errorMessage != null -> fullSpan("error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(vm.errorMessage!!, color = MiuixTheme.colorScheme.error,
                                textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 区域都没拉到时（这一层为空、楼层接口出错）重拉楼层；拉座位没用，那时还没有区域可拉
                                Button(onClick = {
                                    vm.reload()
                                }) { Text("重试") }
                                // 认证相关错误 → 提供重新认证
                                if ("认证" in (vm.errorMessage ?: "") || "登录" in (vm.errorMessage ?: "") || "VPN" in (vm.errorMessage ?: "")) {
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
                                                    vm.reload()
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) { vm.errorMessage = "重新认证失败: ${e.message}" }
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

                    vm.viewMode == VIEW_PLAN -> fullSpan("plan") {
                        // 手机上图高约一屏：把头部卡片往上滚走，图正好铺满；平板在右栏占满。
                        val planHeight = (viewportHeight - (if (wideLibrary) glassTop + 22.dp else 16.dp))
                            .coerceAtLeast(320.dp)
                        val layout = vm.planLayout
                        val images = vm.planImages
                        Column {
                        // 整层图：点区域切区域。只保留这一层真有的区域，图上别的矩形（楼梯、出口按钮）不响应
                        vm.floorPlan?.let { (fl, fi) ->
                            val areas = remember(fl, vm.floorAreas) { fl.copy(seats = fl.seats.filter { it.seatId in vm.floorAreas }) }
                            if (areas.seats.isNotEmpty()) FloorPlanView(
                                layout = areas,
                                images = fi,
                                selectedArea = vm.selectedAreaCode,
                                onPick = vm::selectArea,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                        }
                        when {
                            layout != null && images != null -> SeatPlanPanel(
                                layout = layout,
                                images = images,
                                maxHeight = planHeight,
                                favorites = favorites,
                                isBooking = vm.isBooking,
                                // 平面图模式下页面顶部的结果卡已经滚出屏幕，图下面再给一份
                                result = vm.bookingResult,
                                onBook = { bookSeat(it) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            vm.planError != null -> Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(vm.planError!!, color = MiuixTheme.colorScheme.error,
                                    textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.loadPlan(vm.selectedAreaCode, force = true) }) { Text("重试") }
                                    Button(onClick = { vm.changeViewMode(VIEW_LIST) }) { Text("看列表") }
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
                                            isBooking = vm.isBooking,
                                            isFavorite = true,
                                            onClick = { if (seat.available) bookSeat(seat.seatId) },
                                            onLongClick = { vm.toggleFavorite(seat.seatId) }
                                        )
                                    }
                                }
                            }
                        }

                        // 全部座位
                        items(visibleSeats, key = { it.seatId }) { seat ->
                            SeatChip(
                                seat = seat,
                                isBooking = vm.isBooking,
                                isFavorite = seat.seatId in favorites,
                                onClick = { if (seat.available) bookSeat(seat.seatId) },
                                onLongClick = { vm.toggleFavorite(seat.seatId) }
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
