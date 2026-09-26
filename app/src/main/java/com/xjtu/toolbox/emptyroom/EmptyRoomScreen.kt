package com.xjtu.toolbox.emptyroom

import com.xjtu.toolbox.ui.components.AppPullToRefresh
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.ui.components.enterOnce
import androidx.compose.foundation.lazy.itemsIndexed
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.grid.itemsIndexed
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.activity.compose.BackHandler
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.RangeSlider
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MeetingRoom
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.data.CredentialStore

/** 空闲教室的数据源。[key] 存进偏好，改名别动它。 */
internal enum class RoomSource(val key: String) {
    /** 智慧教室平台的此刻状态：含上课、没排课但有人（带人数）。默认。 */
    LIVE("live"),
    /** 预生成的课表数据，免登录，可看今天/明天。 */
    CDN("cdn"),
    /** 登录教务实时查课表，结果和 CDN 同源。 */
    DIRECT("direct"),
}

/** 新键：旧的 empty_room_use_direct_query 是 CDN/直查二选一时代的，实时状态上线后默认改回实时，不沿用。 */
internal const val SOURCE_PREF_KEY = "empty_room_source"

/** 实时状态的快捷筛选。第一个是默认。 */
private val LIVE_FILTERS = listOf("空闲", "其它使用", "上课中", "全部")

/** 空闲 → 其它使用 → 上课中 → 未知。 */
private fun liveStatusRank(status: Int): Int = when (status) {
    LiveRoomStatus.FREE -> 0
    LiveRoomStatus.IN_USE -> 1
    LiveRoomStatus.IN_CLASS -> 2
    else -> 3
}

/** 每节课对应的时间段 (1-11) */
private val PERIOD_TIMES = listOf(
    "08:00" to "08:50",  // 1
    "09:00" to "09:50",  // 2
    "10:10" to "11:00",  // 3
    "11:10" to "12:00",  // 4
    "14:00" to "14:50",  // 5
    "15:00" to "15:50",  // 6
    "16:10" to "17:00",  // 7
    "17:10" to "18:00",  // 8
    "19:00" to "19:50",  // 9
    "20:00" to "20:50",  // 10
    "21:00" to "21:50"   // 11
)

/** 根据当前时间判断当前节次（0-based），返回 -1 表示不在上课时间 */
private fun getCurrentPeriod(): Int {
    val now = LocalTime.now()
    PERIOD_TIMES.forEachIndexed { index, (start, end) ->
        val s = LocalTime.parse(start)
        val e = LocalTime.parse(end)
        if (now in s..e) return index
        // 在两节课之间的休息时间，归为下一节课
        if (index < PERIOD_TIMES.size - 1) {
            val nextStart = LocalTime.parse(PERIOD_TIMES[index + 1].first)
            if (now in e..nextStart) return index + 1
        }
    }
    // 在第一节课之前
    if (now < LocalTime.parse(PERIOD_TIMES[0].first)) return 0
    // 最后一节课之后
    return -1
}

/** 计算教室从 startPeriod 开始的连续空闲节数 */
private fun consecutiveFree(status: List<Int>, startPeriod: Int): Int {
    if (startPeriod < 0 || startPeriod >= status.size) return 0
    var count = 0
    for (i in startPeriod until status.size) {
        if (status[i] == 0) count++ else break
    }
    return count
}

/** 分析教室的智能标签 */
@Composable
private fun getSmartTags(room: RoomInfo, currentPeriod: Int): List<Pair<String, Color>> {
    val tags = mutableListOf<Pair<String, Color>>()
    if (currentPeriod < 0 || currentPeriod >= room.status.size) return tags

    val isNowFree = room.status[currentPeriod] == 0
    val wasBusy = currentPeriod > 0 && room.status[currentPeriod - 1] == 1

    if (isNowFree) {
        val freePeriods = consecutiveFree(room.status, currentPeriod)
        if (wasBusy) {
            tags.add("刚解放" to MiuixTheme.colorScheme.primaryVariant)
        }
        when {
            freePeriods >= 4 -> tags.add("空闲≥4节" to MiuixTheme.colorScheme.primary)
            freePeriods >= 2 -> tags.add("空闲${freePeriods}节" to MiuixTheme.colorScheme.primary)
            else -> tags.add("本节空闲" to MiuixTheme.colorScheme.secondary)
        }
    }

    if (room.size >= 100) tags.add("大教室" to MiuixTheme.colorScheme.primaryVariant)

    return tags
}

@Composable
private fun BuildingSelectionTile(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 原来手绘了一圈蓝色 border + 自绘圆点勾选，不是 miuix 原生语言。
    // 改用 miuix 原生 Checkbox 表达多选状态，去掉描边，选中态只靠底色区分。
    val shape = RoundedCornerShape(14.dp)
    val containerColor = if (selected) {
        MiuixTheme.colorScheme.tertiaryContainer
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            ),
        shape = shape,
        color = containerColor
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            top.yukonga.miuix.kmp.basic.Checkbox(
                state = if (selected) androidx.compose.ui.state.ToggleableState.On
                    else androidx.compose.ui.state.ToggleableState.Off,
                onClick = onClick
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun EmptyRoomScreen(
    onBack: () -> Unit,
    /**
     * 会话管家。实时状态要登智慧教室、直查要登教务，都在页面里按需登录（入口不再先登教务）。
     * 为 null（未初始化）时只剩 CDN 可用。
     */
    sessionManager: com.xjtu.toolbox.auth.SessionManager? = null,
) {
    val context = LocalContext.current
    val credentialStore = remember(context) { CredentialStore(context) }
    val accountType = remember { credentialStore.accountType }
    val vm: EmptyRoomViewModel = viewModel { EmptyRoomViewModel(context, sessionManager, accountType) }
    val rooms = vm.rooms
    val source = vm.source
    val isLive = vm.isLive
    var showCdnTip by remember { mutableStateOf(!credentialStore.hasReadEmptyRoomCdnTip && source == RoomSource.CDN) }
    val campusNames = vm.campusNames
    val selectedCampus = vm.campus
    val selectedCampusIndex = campusNames.indexOf(selectedCampus)
    val liveEffective = vm.liveEffective
    val sheetBuildings = vm.sheetBuildings
    val sheetSelected = vm.sheetSelected
    val availableDates = vm.availableDates
    val selectedDate = vm.selectedDate

    // 智能筛选
    var smartFilter by rememberSaveable { mutableStateOf("现在空闲") }
    // 用户自选节数区间（1-based）
    var startPeriod by rememberSaveable { mutableIntStateOf(1) }
    var endPeriod by rememberSaveable { mutableIntStateOf(11) }

    val currentPeriod = remember { getCurrentPeriod() }
    val isToday = selectedDate == LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val effectivePeriod = if (isToday) currentPeriod else -1
    val smartFilters = if (isToday) listOf("现在空闲", "刚解放", "大教室", "全部") else listOf("大教室", "全部")
    LaunchedEffect(isToday) {
        if (!isToday && smartFilter in listOf("现在空闲", "刚解放")) smartFilter = "全部"
    }

    // 智能排序 + 筛选
    val displayRooms = remember(rooms, smartFilter, effectivePeriod, startPeriod, endPeriod) {
        // 先按用户选定的节数区间过滤：所有选定节次均空闲
        val rangeFiltered = rooms.filter { room ->
            val start0 = (startPeriod - 1).coerceIn(0, room.status.lastIndex)
            val end0 = (endPeriod - 1).coerceIn(start0, room.status.lastIndex)
            (start0..end0).all { room.status.getOrNull(it) == 0 }
        }

        val filtered = when (smartFilter) {
            "现在空闲" -> if (effectivePeriod >= 0) rangeFiltered.filter { it.status.getOrNull(effectivePeriod) == 0 } else rangeFiltered
            "刚解放" -> if (effectivePeriod > 0) rangeFiltered.filter {
                it.status.getOrNull(effectivePeriod) == 0 && it.status.getOrNull(effectivePeriod - 1) == 1
            } else emptyList()
            "大教室" -> rangeFiltered.filter { it.size >= 100 }
            else -> rangeFiltered
        }
        if (effectivePeriod >= 0) {
            filtered.sortedByDescending { consecutiveFree(it.status, effectivePeriod) }
        } else {
            filtered.sortedBy { it.name }
        }
    }

    // 实时状态的筛选与排序。楼的顺序跟平台一致（groupBy 保留首次出现顺序）。
    var liveFilter by rememberSaveable { mutableStateOf(LIVE_FILTERS.first()) }
    val liveInScope = remember(vm.liveSnapshot, liveEffective, selectedCampus) {
        val snap = vm.liveSnapshot?.takeIf { it.campus == selectedCampus } ?: return@remember emptyList()
        if (liveEffective.isEmpty()) snap.rooms else snap.rooms.filter { it.building in liveEffective }
    }
    val liveCounts = remember(liveInScope) {
        if (liveInScope.isEmpty()) emptyMap() else mapOf(
            "空闲" to liveInScope.count { it.isFree },
            "其它使用" to liveInScope.count { it.isInUse },
            "上课中" to liveInScope.count { it.isInClass },
            "全部" to liveInScope.size,
        )
    }
    val liveGrouped = remember(liveInScope, liveFilter) {
        val picked = when (liveFilter) {
            "空闲" -> liveInScope.filter { it.isFree }
            "其它使用" -> liveInScope.filter { it.isInUse }
            "上课中" -> liveInScope.filter { it.isInClass }
            else -> liveInScope
        }
        // 空闲的排前面；其它使用的按人数从少到多
        picked.groupBy { it.building }.mapValues { (_, list) ->
            list.sortedWith(compareBy<LiveRoom>({ liveStatusRank(it.status) }, { if (it.isInUse) it.people else 0 }, { it.name }))
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var showActionsMenu by remember { mutableStateOf(false) }
    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "空闲教室",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "切换数据源")
                        }
                        AppDropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false }
                        ) {
                            Text(
                                "数据源",
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            val options = buildList {
                                add(Triple(RoomSource.LIVE, "实时状态", "此刻哪间空、哪间有人，含没排课但有人用的"))
                                add(Triple(RoomSource.CDN, "CDN 课表", "免登录，看今天、明天逐节安排"))
                                // 沿用原来的限制：研究生身份不提供直查教务
                                if (accountType != AccountType.POSTGRADUATE) {
                                    add(Triple(RoomSource.DIRECT, "直查教务", "登录教务查课表，和 CDN 同源"))
                                }
                            }
                            options.forEach { (option, label, hint) ->
                                AppDropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                label,
                                                style = MiuixTheme.textStyles.body2,
                                                fontWeight = if (source == option) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                hint,
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            )
                                        }
                                    },
                                    onClick = {
                                        showActionsMenu = false
                                        if (source != option) {
                                            vm.selectSource(option)
                                            if (option == RoomSource.CDN && !credentialStore.hasReadEmptyRoomCdnTip) {
                                                showCdnTip = true
                                            }
                                        }
                                    },
                                    trailingIcon = {
                                        if (source == option) {
                                            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        if (showCdnTip) {
            OverlayDialog(
                show = true,
                title = "Cloudflare CDN 查询说明",
                summary = if (accountType == AccountType.POSTGRADUATE) {
                    "CDN 查询无需登录，也不会发送账号相关信息；数据是按课表定时生成的，只知道哪节有课，不知道没排课的教室里有没有人。想看此刻的实际情况，可切回实时状态。"
                } else {
                    "CDN 查询无需登录，也不会发送账号相关信息；数据是按课表定时生成的，只知道哪节有课，不知道没排课的教室里有没有人。想看此刻的实际情况，可切回实时状态；CDN 查询失败时可改用直查教务。"
                },
                onDismissRequest = {
                    credentialStore.hasReadEmptyRoomCdnTip = true
                    showCdnTip = false
                }
            ) {
                TextButton(
                    text = "知道了",
                    onClick = {
                        credentialStore.hasReadEmptyRoomCdnTip = true
                        showCdnTip = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        val glassTop = padding.glassTop(glass)
        val showFilterSheet = remember { mutableStateOf(false) }
        var buildingQuery by rememberSaveable { mutableStateOf("") }
        BackHandler(enabled = showFilterSheet.value) {
            showFilterSheet.value = false
        }

            OverlayBottomSheet(
                show = showFilterSheet.value,
                // 弹窗里实际只有"选校区 + 选教学楼"两件事（下面注释自己也写明了），
                // "筛选空闲教室"这个标题范围太大、容易让人以为还能选时间/节次，改成准确的"选择教学楼"
                title = "选择教学楼",
                onDismissRequest = { showFilterSheet.value = false }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    AppSegmentedTabs(
                        tabs = campusNames.map { it.removeSuffix("校区") },
                        selectedTabIndex = selectedCampusIndex,
                        onTabSelected = vm::selectCampus,
                        embedded = true,
                    )

                    Text(
                        "教学楼",
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                    AppSearchBar(
                        query = buildingQuery,
                        onQueryChange = { buildingQuery = it },
                        label = "搜索教学楼",
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                    if (isLive && sheetBuildings.isEmpty()) {
                        Text(
                            if (vm.isLoading) "正在读取这个校区的楼…" else "这个校区的实时状态还没拿到",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        )
                    }
                    // 全选/取消全选
                    val allSelected = sheetBuildings.isNotEmpty() && sheetSelected.size == sheetBuildings.size
                    if (sheetBuildings.isNotEmpty()) BuildingSelectionTile(
                        text = if (allSelected) "已选择全部教学楼" else "选择全部教学楼",
                        selected = allSelected,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        vm.setSheetSelected(if (allSelected) setOf(sheetBuildings.firstOrNull() ?: "") else sheetBuildings.toSet())
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val visibleBuildings = sheetBuildings
                        .filter { buildingQuery.isBlank() || it.contains(buildingQuery, ignoreCase = true) }
                    visibleBuildings.chunked(2).forEach { rowBuildings ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowBuildings.forEach { building ->
                                val isSelected = building in sheetSelected
                                BuildingSelectionTile(
                                    text = building,
                                    selected = isSelected,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    vm.setSheetSelected(
                                        if (isSelected) {
                                            val newSet = sheetSelected - building
                                            if (newSet.isEmpty()) sheetSelected else newSet
                                        } else {
                                            sheetSelected + building
                                        }
                                    )
                                }
                            }
                            if (rowBuildings.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (isLive) {
                        Text(
                            "实时状态来自学校智慧教室平台，只有兴庆、雁塔、创新港三个校区；仲英楼、中1、计教中心、田家炳等楼和曲江、苏州校区不在平台上，要看它们请在右上角切到课表数据。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                        )
                    }

                    // 日期 / 空闲节次 / 快捷筛选已在主页面卡片提供，弹窗内不再重复（避免与页面控件重叠）。
                    // 本弹窗只负责「选校区 + 选教学楼」。
                    // 底部"查看 x 间教室"按钮已删——弹窗本身支持拖拽关闭和点外部关闭，
                    // 选完即生效（结果实时反映在下面的列表），这个按钮不点开也照样生效，纯粹多余。
                    Spacer(Modifier.height(12.dp))
                }
            }


        // 重排（2026-09-21）：原来列表上面叠着楼栋卡、「什么时候有空」卡、统计行三大块，固定不动，
        // 占了半屏，真正要看的教室只剩下半截。现在：
        // - 筛选压成一张紧凑的卡：第一行楼栋胶囊 + 日期，第二行节次和时间，下面一根滑条，最后一行快捷筛选；
        // - 整块筛选区作为列表的前几项跟着滚，往上一推，教室列表就占满整屏（也才能从玻璃顶栏下面滚过去）。
        // 滑条是横向拖动，放进纵向列表里不会和滚动、下拉刷新抢手势。
        AppPullToRefresh(
            isRefreshing = vm.isLoading && (if (isLive) vm.liveSnapshot != null else rooms.isNotEmpty()),
            onRefresh = { vm.refresh() },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.fillMaxSize().padding(padding.withoutTop(glass)).glassSource(glass),
        ) {
            val groupedRooms = remember(displayRooms, vm.selectedBuildings) {
                displayRooms.groupBy { room ->
                    vm.selectedBuildings
                        .sortedByDescending { it.length }
                        .firstOrNull { room.name.startsWith(it) }
                        ?: room.name.substringBefore("-").substringBefore(" ")
                }
            }
            // 宽屏两栏：左边固定一张筛选卡（楼栋、日期、节次、智能筛选），右边教室卡片分两三列，按行对齐（AdaptiveRowGrid）：展开一间看节次时别的卡不会换列。
            // 以前整页限宽 720 居中，筛选卡占掉小半屏、教室一行一张，平板横屏两边各空一大块。
            // 窄屏照旧：筛选卡是列表的第一项，跟着教室一起滚。
            val wideRooms = com.xjtu.toolbox.ui.isWideLayout()
            val filtersCard: @Composable () -> Unit = {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            // 第一行：楼栋（点开选校区、选楼）+ 日期
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(50))
                                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                                        .clickable { showFilterSheet.value = true }
                                        .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Apartment,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        if (isLive) {
                                            if (liveEffective.isEmpty()) "全部教学楼"
                                            else vm.liveBuildings.filter { it in liveEffective }.joinToString("、")
                                        } else {
                                            vm.selectedBuildings.joinToString("、").ifEmpty { "选择教学楼" }
                                        },
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(
                                        " · ${selectedCampus.removeSuffix("校区")}",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.75f),
                                        maxLines = 1,
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "调整楼栋与校区",
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                if (isLive) {
                                    val snap = vm.liveSnapshot?.takeIf { it.campus == selectedCampus }
                                    Text(
                                        if (snap == null) "实时"
                                        else "实时 · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(snap.fetchedAt),
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (!isLive) availableDates.forEachIndexed { index, date ->
                                    AppFilterChip(
                                        selected = selectedDate == date,
                                        onClick = { vm.selectDate(date) },
                                        label = when (index) {
                                            0 -> "今天"
                                            1 -> "明天"
                                            else -> date.takeLast(5).replace("-", "/")
                                        },
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (isLive) {
                                // 实时状态只有"此刻"，没有节次可选；快捷筛选按状态分，带上各自的间数
                                val counts = liveCounts
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    LIVE_FILTERS.forEach { filter ->
                                        val n = counts[filter]
                                        AppFilterChip(
                                            selected = liveFilter == filter,
                                            onClick = { liveFilter = filter },
                                            label = if (n != null) "$filter $n" else filter,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "「其它使用」是课表上没课、但平台统计到有人的教室（自习、社团借用、活动等，平台不区分），人数是此刻在场人数。",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                if (vm.isLoading && vm.liveSnapshot != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(size = 12.dp, strokeWidth = 1.5.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "正在更新实时状态",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            } else {
                            // 第二行：要空的节次和对应时间；今天再标出现在是第几节
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "第${startPeriod}-${endPeriod}节",
                                    style = MiuixTheme.textStyles.body2,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${PERIOD_TIMES[startPeriod - 1].first} - ${PERIOD_TIMES[endPeriod - 1].second}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.weight(1f))
                                if (isToday && currentPeriod >= 0) {
                                    Text(
                                        "现在第${currentPeriod + 1}节",
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            RangeSlider(
                                value = startPeriod.toFloat()..endPeriod.toFloat(),
                                onValueChange = { range ->
                                    startPeriod = range.start.roundToInt().coerceIn(1, 11)
                                    endPeriod = range.endInclusive.roundToInt().coerceIn(startPeriod, 11)
                                },
                                valueRange = 1f..11f,
                                steps = 9,
                                showKeyPoints = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            // 第三行：快捷筛选
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                smartFilters.forEach { filter ->
                                    AppFilterChip(
                                        selected = smartFilter == filter,
                                        onClick = { smartFilter = filter },
                                        label = filter,
                                    )
                                }
                            }
                            if (vm.isLoading && rooms.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(size = 12.dp, strokeWidth = 1.5.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        vm.directProgress?.let { "直查教务更新中…${it.first}/${it.second}" }
                                            ?: "正在更新结果",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                            } // 课表数据源的节次/日期筛选
                        }
                    }
            }
            Row(Modifier.fillMaxSize()) {
            if (wideRooms) {
                Column(
                    Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .overScrollVertical()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, top = glassTop + 4.dp, bottom = 16.dp),
                ) {
                    filtersCard()
                }
            }
            com.xjtu.toolbox.ui.adaptive.AdaptiveRowGrid(
                modifier = Modifier.weight(1f).fillMaxHeight().overScrollVertical(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = glassTop + 4.dp, bottom = 16.dp),
                spacing = 7.dp,
                minColumnWidth = 320.dp,
            ) {
                // 网络失败兜底提示：展示磁盘缓存 + 「缓存于 HH:mm」标识。
                // 与下面 vm.errorMessage 的区别：vm.errorMessage 是红字无数据；vm.staleNote 是黄底有数据可看。
                vm.staleNote?.let { note ->
                    fullLineItem(key = "stale") {
                        Surface(
                            color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Outlined.CloudOff,
                                    contentDescription = "网络不可用提示",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    note,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote1,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                if (!wideRooms) fullLineItem(key = "filters") { filtersCard() }

                // 加载中、出错、空：列表里没有「剩余高度」可以撑满，给个最小高度居中
                val stateBox: (@Composable () -> Unit) -> Unit = { content ->
                    fullLineItem(key = "state") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) { content() }
                    }
                }
                val switchToCdn: () -> Unit = {
                    vm.selectSource(RoomSource.CDN)
                    if (!credentialStore.hasReadEmptyRoomCdnTip) showCdnTip = true
                }
                if (isLive) when {
                    vm.isLoading && liveInScope.isEmpty() -> stateBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            com.xjtu.toolbox.ui.components.MorphingLoader()
                            Spacer(Modifier.height(8.dp))
                            Text("正在读取实时状态…", style = MiuixTheme.textStyles.body2)
                        }
                    }

                    vm.errorMessage != null -> stateBox {
                        RoomStateBlock(
                            icon = Icons.Outlined.CloudOff,
                            title = "实时状态加载失败",
                            detail = vm.errorMessage,
                            isError = true,
                            primaryLabel = "重试",
                            onPrimary = { vm.refresh() },
                            secondaryLabel = "改用 CDN 课表",
                            onSecondary = switchToCdn,
                        )
                    }

                    liveGrouped.isEmpty() -> stateBox {
                        RoomStateBlock(
                            icon = Icons.Outlined.MeetingRoom,
                            title = if (liveFilter == "空闲") "此刻没有空闲的教室" else "此刻没有符合条件的教室",
                            secondaryLabel = if (liveFilter != "全部") "查看全部" else null,
                            onSecondary = { liveFilter = "全部" },
                        )
                    }

                    else -> {
                        val nowPeriod = currentPeriod
                        liveGrouped.forEach { (building, buildingRooms) ->
                            fullLineItem(key = "live_header_$building") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.11f)
                                    ) {
                                        Icon(
                                            Icons.Default.Apartment,
                                            contentDescription = null,
                                            tint = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier.padding(7.dp).size(17.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Text(building, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${buildingRooms.size} 间",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            itemsIndexed(buildingRooms, key = { _, it -> "live_${it.name}" }) { i, room ->
                                Box(Modifier.enterOnce(i + 1)) { LiveRoomCard(room, vm.liveSchedule[room.name], nowPeriod) }
                            }
                        }
                    }
                } else when {
                    vm.isLoading && rooms.isEmpty() -> stateBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            com.xjtu.toolbox.ui.components.MorphingLoader()  // 整页加载统一用形变加载器
                            Spacer(Modifier.height(8.dp))
                            val pg = vm.directProgress
                            Text(
                                if (pg != null) "直查教务更新中…${pg.first}/${pg.second}" else "正在查询...",
                                style = MiuixTheme.textStyles.body2,
                            )
                        }
                    }

                    vm.errorMessage != null -> stateBox {
                        RoomStateBlock(
                            icon = Icons.Outlined.CloudOff,
                            title = if (source == RoomSource.DIRECT) "直查教务失败" else "课表数据加载失败",
                            detail = vm.errorMessage,
                            isError = true,
                            primaryLabel = "重试",
                            onPrimary = { vm.refresh() },
                        )
                    }

                    rooms.isEmpty() && vm.selectedBuildings.all { it.isEmpty() } -> stateBox {
                        RoomStateBlock(
                            icon = Icons.Default.Apartment,
                            title = "选择教学楼后自动查询",
                            primaryLabel = "选择教学楼",
                            onPrimary = { showFilterSheet.value = true },
                        )
                    }

                    displayRooms.isEmpty() -> stateBox {
                        RoomStateBlock(
                            icon = Icons.Outlined.MeetingRoom,
                            title = "暂无符合条件的教室",
                            detail = "可以放宽节次范围，或换一个筛选",
                            secondaryLabel = if (smartFilter != "全部") "查看全部" else null,
                            onSecondary = { smartFilter = "全部" },
                        )
                    }

                    else -> {
                        fullLineItem(key = "count") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${displayRooms.size} 间教室",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                if (rooms.size != displayRooms.size) {
                                    Text(
                                        " / 共 ${rooms.size}",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                        groupedRooms.forEach { (building, buildingRooms) ->
                            fullLineItem(key = "header_$building") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.11f)
                                    ) {
                                        Icon(
                                            Icons.Default.Apartment,
                                            contentDescription = null,
                                            tint = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier.padding(7.dp).size(17.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Text(building, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${buildingRooms.size} 间",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            itemsIndexed(buildingRooms, key = { _, it -> it.name }) { i, room ->
                                Box(Modifier.enterOnce(i + 1)) { SmartRoomCard(room, effectivePeriod) }
                            }
                        }
                    }
                }
            }
            } // Row（宽屏：左筛选、右教室）
        }
    }
}

// ══════ 智能教室卡片 ══════

@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmartRoomCard(room: RoomInfo, currentPeriod: Int) {
    val tags = getSmartTags(room, currentPeriod)
    val isNowFree = currentPeriod >= 0 && room.status.getOrNull(currentPeriod) == 0
    val freePeriods = if (isNowFree) consecutiveFree(room.status, currentPeriod) else 0
    val nextBusy = if (isNowFree) {
        room.status.indices.firstOrNull { it > currentPeriod && room.status[it] != 0 }
    } else {
        null
    }
    var expanded by rememberSaveable(room.name) { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(room.name))
                    android.widget.Toast.makeText(context, "已复制：${room.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            ),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = if (isNowFree)
                MiuixTheme.colorScheme.surfaceVariant
            else
                MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        room.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            currentPeriod < 0 -> "${room.size} 座 · 点击查看全天安排"
                            isNowFree && nextBusy != null ->
                                "${room.size} 座 · 可用 $freePeriods 节，下一次占用在第${nextBusy + 1}节"
                            isNowFree -> "${room.size} 座 · 今天余下时段均空闲"
                            else -> "${room.size} 座 · 当前占用"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                tags.firstOrNull()?.let { (label, color) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = color.copy(alpha = 0.12f)
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = color,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 收起时也给一条全天节次条：不用点开就能看出哪几节空
            if (!expanded && room.status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                com.xjtu.toolbox.ui.components.SlotStripe(
                    free = room.status.map { it == 0 },
                    freeColor = MiuixTheme.colorScheme.primary,
                    currentIndex = currentPeriod,
                    busyColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.14f),
                    height = 5.dp,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    room.status.forEachIndexed { index, value ->
                        val isFree = value == 0
                        val isCurrent = index == currentPeriod
                        val bgColor = if (isFree) {
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                        }
                        val txtColor = if (isFree) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.error
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCurrent) MiuixTheme.colorScheme.primary else bgColor
                        ) {
                            Text(
                                text = "${index + 1}",
                                modifier = Modifier.padding(vertical = 7.dp),
                                fontSize = 10.sp,
                                color = if (isCurrent) MiuixTheme.colorScheme.onPrimary else txtColor,
                                textAlign = TextAlign.Center,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}


// ══════ 实时状态教室卡片 ══════

/** 其它使用（没排课但有人）：用琥珀色，和"空闲"的主色、"上课中"的错误色都分得开。 */
private val IN_USE_COLOR = Color(0xFFD9822B)

/**
 * @param schedule 当天课表（CDN）里的同名教室，可能没有（平台多出来的教室、CDN 拿不到）。
 * 有的话画节次条，并告诉用户这间空教室能用到什么时候。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveRoomCard(room: LiveRoom, schedule: RoomInfo?, currentPeriod: Int) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val seats = room.seats.takeIf { it > 0 } ?: schedule?.size ?: 0
    val seatText = if (seats > 0) "$seats 座" else "座位数未知"
    val status = schedule?.status.orEmpty()
    val nextBusy = if (currentPeriod >= 0) {
        status.indices.firstOrNull { it > currentPeriod && status[it] != 0 }
    } else null
    val (badge, badgeColor) = when {
        room.isFree -> "空闲" to MiuixTheme.colorScheme.primary
        room.isInUse -> "${room.people} 人" to IN_USE_COLOR
        room.isInClass -> "上课中" to MiuixTheme.colorScheme.error
        else -> "未知" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val summary = when {
        room.isFree -> when {
            status.isEmpty() || currentPeriod < 0 -> "$seatText · 此刻没人"
            nextBusy != null -> "$seatText · 下一节课在第${nextBusy + 1}节"
            else -> "$seatText · 今天余下时段没排课"
        }
        room.isInUse -> "$seatText · 没排课，此刻 ${room.people} 人在用"
        room.isInClass -> listOfNotNull(room.course, room.teacher).joinToString(" · ").ifEmpty { "有课" } +
            " · ${room.people}/${seats.takeIf { it > 0 } ?: "?"} 人"
        else -> "$seatText · 平台没给出状态"
    }

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(room.name))
                    android.widget.Toast.makeText(context, "已复制：${room.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            ),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = if (room.isFree) MiuixTheme.colorScheme.surfaceVariant
            else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        room.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                com.xjtu.toolbox.ui.components.SlotStripe(
                    free = status.map { it == 0 },
                    freeColor = MiuixTheme.colorScheme.primary,
                    currentIndex = currentPeriod,
                    busyColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.14f),
                    height = 5.dp,
                )
            }
        }
    }
}

// ══════ 加载失败 / 空结果 ══════

/** 报错不翻译：原文最准。没有 message 的给类名，至少知道是哪一类错。 */
internal fun rawError(e: Throwable): String =
    e.message?.trim()?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName

/**
 * 页面里所有「没东西可显示」的状态共用一个样子：浅底圆形图标 + 标题 + 说明 +
 * 一个主按钮 + 一个文字链接。
 *
 * 以前是手写的：整句报错染成红色、没有图标，「重试」是 miuix Button，下面的次要操作
 * 用的是 miuix TextButton——它不是文字链接，而是另一块灰底胶囊，两块宽度颜色都不一样，
 * 叠在一起像两块砖。「查看全部」同样是一块灰胶囊紧贴在提示字下面。
 * 现在只有主操作是按钮，次要操作一律是主色文字链接。
 */
@Composable
private fun RoomStateBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String? = null,
    isError: Boolean = false,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val accent = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (primaryLabel != null) {
            Spacer(Modifier.height(20.dp))
            TextButton(
                text = primaryLabel,
                onClick = onPrimary,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.widthIn(min = 160.dp),
            )
        }
        if (secondaryLabel != null) {
            Spacer(Modifier.height(if (primaryLabel != null) 6.dp else 14.dp))
            Text(
                secondaryLabel,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSecondary)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
