package com.xjtu.toolbox.library

import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.ui.components.AppPullToRefresh
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import top.yukonga.miuix.kmp.basic.IconButton
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

    // 预约状态一变就往外发（提醒、首页信号、收纳）：预约 / 换座 / 中途离开 / 签到最后都会刷新 myBooking，盯结果比盯动作少漏
    LaunchedEffect(vm.myBookingKnown, vm.myBooking?.actionUrls?.keys, vm.myBooking?.seatId) {
        if (vm.myBookingKnown) com.xjtu.toolbox.library.LibraryStatus.publish(context, vm.myBooking)
    }

    var confirmDialog by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
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
    val planMode = vm.viewMode == VIEW_PLAN
    // 平面图模式的座位状态来自平面图数据，不再另查一份座位列表
    val planSeats = vm.planLayout?.seats.orEmpty()
    val availableCount = if (planMode) planSeats.count { it.available } else seats.count { it.available }
    val totalCount = if (planMode) planSeats.size else seats.size
    // 收藏的排在最前面
    val visibleSeats = remember(seats, seatScope, favorites) {
        when (seatScope) {
            "收藏" -> seats.filter { it.seatId in favorites }
            "全部" -> seats
            else -> seats.filter { it.available }
        }.sortedByDescending { it.seatId in favorites }
    }
    // 整层图上能点区域时，就不再给一排区域标签；整层图拿不到才退回标签
    val floorAreasOnPlan = remember(vm.floorPlan, areaCodes) {
        vm.floorPlan?.let { (fl, fi) ->
            fl.copy(seats = fl.seats.filter { it.seatId in areaCodes }).takeIf { it.seats.isNotEmpty() }?.let { it to fi }
        }
    }
    val showAreaChips = !planMode || (!vm.floorPlanLoading && floorAreasOnPlan == null)

    // ══════ UI ══════
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "${campus.displayName}图书馆",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    // 校区、平面图 / 列表都收进这个菜单：一次选定就很少再改，不值得各占一整行
                    if (vm.campusSwitching) CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = "校区与视图")
                        }
                        AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            @Composable
                            fun option(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) = AppDropdownMenuItem(
                                text = { Text(label) },
                                trailingIcon = if (checked) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary) }
                                } else null,
                                enabled = enabled,
                                onClick = { menuOpen = false; onClick() },
                            )
                            LibraryCampus.entries.forEach { c ->
                                // 切换会写回账号资料（rplace），切换期间别让人连点
                                option("${c.displayName}校区", campus == c, enabled = !vm.campusSwitching) { vm.switchCampus(c) }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            listOf(VIEW_PLAN, VIEW_LIST).forEach { mode ->
                                option(mode, vm.viewMode == mode) { vm.changeViewMode(mode) }
                            }
                        }
                    }
                },
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
                        "⏰" to "预约成功后，请在 30 分钟内入馆签到，否则当日将被禁止线上预约。",
                        "📋" to "座位状态说明：「使用中」= 已签到入座；「已预约」 = 已预约未签到；「暂离」= 短暂离开保留中。",
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
        LaunchedEffect(vm.isLoading, vm.planLoading, vm.isLoadingBooking) {
            if (!vm.isLoading && !vm.planLoading && !vm.isLoadingBooking) isPullRefreshing = false
        }
        // 内容铺到顶栏下面，顶部留白放进列表；下拉指示器也从顶栏下面出来
        val glassTop = padding.glassTop(glass)
        Box(Modifier.fillMaxSize()) {
        AppPullToRefresh(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                isPullRefreshing = true
                vm.reload()
                vm.refreshMyBooking()
                vm.bookingResult = null
            },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
        ) {
        // 上面这一整叠卡片：预约状态 / 校区楼层区域 / 座位统计。
        //
        // 座位列表模式下它们是座位网格的第一项，和座位一起滚：以前固定在顶部，
        // 占掉大半屏，往上划只有下面一小块座位在动，半个屏幕纹丝不动，很别扭。
        // 加载中、出错、没有座位时没有可滚的列表，它们仍然钉在顶上。
        val headerContent: @Composable ColumnScope.() -> Unit = {
            // ── 当前预约 ──（头部两张卡依次登场）
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
                                val unknown = !vm.myBookingKnown && vm.myBookingFailed
                                Text(if (unknown) "预约状态没查到" else "还没有预约", style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    if (unknown) "下拉刷新重试" else "从下方选择区域和座位",
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

            // ── 楼层 / 区域选择（校区和视图在右上角菜单里） ──
            Card(
                modifier = Modifier.enterOnce(1).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor)
            ) {
                Column {
                    val home = vm.homeCampus
                    if (home != null && home != campus) {
                        Text(
                            "离开本页会切回${home.displayName}；在这里约了座位就留在${campus.displayName}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
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
                    val divider = @Composable {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
                        )
                    }
                    if (showAreaChips && areaCodes.isNotEmpty()) {
                        divider()
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
                    // 列表模式的筛选；空闲数就写在「可用」上，平面图模式写在图下的信息条里
                    if (!planMode && seats.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
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
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 已有数据时的静默刷新（切换区域/楼层）：用顶部细进度线提示，不清空内容
            val silentLoading = if (planMode) (vm.isLoading || vm.planLoading) && vm.planLayout != null else vm.isLoading && seats.isNotEmpty()
            AnimatedVisibility(visible = silentLoading && !isPullRefreshing) {
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
                    !planMode && vm.isLoading && seats.isEmpty() -> fullSpan("loading") {
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
                                                        site.ensureLogin(creds.first, creds.second, force = true, userInitiated = true)
                                                    }
                                                    vm.reload()
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) { vm.errorMessage = FriendlyError.of(e, "重新认证") }
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

                    planMode -> fullSpan("plan") {
                        // 手机上卡片高约一屏：把头部卡片往上滚走，图正好铺满；平板在右栏占满。
                        val planHeight = (viewportHeight - (if (wideLibrary) glassTop + 22.dp else 16.dp))
                            .coerceAtLeast(320.dp)
                        // 网格左右 12dp，再缩 4dp，和头部卡片的 16dp 边距对齐
                        SeatPlanPanel(
                            // 整层图照全部矩形取景，只有开放的区域能点，楼梯、出口按钮不响应
                            floor = if (floorAreasOnPlan != null) vm.floorPlan else null,
                            pickableAreas = areaCodes.toSet(),
                            selectedArea = vm.selectedAreaCode,
                            areaName = selectedArea,
                            freeText = if (totalCount > 0) "空闲 $availableCount / $totalCount" else null,
                            onPickArea = vm::selectArea,
                            layout = vm.planLayout,
                            images = vm.planImages,
                            loading = vm.planLoading,
                            error = vm.planError,
                            onRetry = { vm.loadPlan(vm.selectedAreaCode, force = true) },
                            onShowList = { vm.changeViewMode(VIEW_LIST) },
                            maxHeight = planHeight,
                            favorites = favorites,
                            onToggleFavorite = vm::toggleFavorite,
                            isBooking = vm.isBooking,
                            onBook = { bookSeat(it) },
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
                        )
                    }

                    seats.isEmpty() -> fullSpan("empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                            Text("该区域暂无座位数据", style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }

                    else -> {
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
        // 预约 / 操作结果只在这里出一次：浮在底部，列表滚到哪都看得见，点一下收起
        ResultBanner(
            result = vm.bookingResult,
            onDismiss = { vm.bookingResult = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }
    }
}

@Composable
private fun ResultBanner(result: BookResult?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // 退场动画期间 result 已经是 null，文字和颜色沿用最后一次的
    var last by remember { mutableStateOf(result) }
    if (result != null) last = result
    AnimatedVisibility(
        visible = result != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 480.dp),
    ) {
        val r = last ?: return@AnimatedVisibility
        Text(
            r.message,
            style = MiuixTheme.textStyles.body2,
            color = if (r.success) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .squircleSurface(
                    color = if (r.success) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.errorContainer,
                    cornerRadius = 16.dp,
                )
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
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
