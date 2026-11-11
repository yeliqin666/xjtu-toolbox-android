package com.xjtu.toolbox.emptyroom

import com.xjtu.toolbox.ui.glass.tabContentBottomPadding
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
import com.xjtu.toolbox.platform.BackHandler
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.RangeSlider
import com.xjtu.toolbox.ui.glass.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import androidx.compose.material.icons.filled.Tune
import com.xjtu.toolbox.ui.components.SelectionTile
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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MeetingRoom
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.xjtu.toolbox.platform.rememberCopyText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalTime
import com.xjtu.toolbox.util.nowOnCampus
import com.xjtu.toolbox.util.todayOnCampus
import kotlin.math.roundToInt
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.ui.components.AppSearchBar
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.util.campusDateTimeOf
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior

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
    // 节次是学校作息 ⇒ 按学校时区的此刻算，不跟本机时区走。
    val now = nowOnCampus().time
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

/** 此刻综合按座位数分的教室类型。第一个是默认。 */
private val ROOM_TYPES = listOf("全部", "大教室", "中小教室")

/** 座位数未知（0）时只算进「全部」。 */
private fun roomTypeMatches(seats: Int, type: String): Boolean = when (type) {
    "大教室" -> seats >= 100
    "中小教室" -> seats in 1 until 100
    else -> true
}

/** 节次条的上午 / 下午 / 晚上分段；节数对不上时不分。 */
private fun slotBreaks(n: Int): Set<Int> =
    if (n == PERIOD_TIMES.size) com.xjtu.toolbox.ui.components.DaySlotBreaks else emptySet()

/** 计算教室从 startPeriod 开始的连续空闲节数 */
private fun consecutiveFree(status: List<Int>, startPeriod: Int): Int {
    if (startPeriod < 0 || startPeriod >= status.size) return 0
    var count = 0
    for (i in startPeriod until status.size) {
        if (status[i] == 0) count++ else break
    }
    return count
}

/** `SimpleDateFormat("HH:mm")` 是 JVM 专属 ⇒ 手写补零（:core 的既有做法）。 */
private fun hhmm(millis: Long): String {
    val at = campusDateTimeOf(millis)
    return "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
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
fun EmptyRoomScreen(
    /** 本端的三档取数（见 [EmptyRoomSource]）。 */
    source: EmptyRoomSource,
    /** 身份。 */
    accountType: AccountType?,
    onBack: () -> Unit,
    /**
     * 「Cloudflare CDN 查询说明」还没读过吗。**由宿主读自己那份持久化偏好**（Android = CredentialStore，
     * Web = localStorage）—— 屏不碰任何平台的存储实现，它只知道“该不该弹”与“弹过了要回写”。
     */
    showCdnTip: Boolean,
    onCdnTipRead: () -> Unit,
) {
    val vm: EmptyRoomViewModel = viewModel { EmptyRoomViewModel(source, accountType) }
    val rooms = vm.rooms
    val sourceKind = vm.sourceKind
    val isLive = vm.isLive
    // 本地再记一份“读过了”：传进来的 showCdnTip 是组合那一刻的快照（宿主那份存储不是 State，
    // 用户点掉后它不会自己变），所以“切走了再切回来要不要再弹”以本地这份为准。
    var cdnTipRead by remember { mutableStateOf(!showCdnTip) }
    var tipVisible by remember { mutableStateOf(!cdnTipRead && sourceKind == RoomSource.CDN) }
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
    val isToday = selectedDate == todayOnCampus().toString()
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

    // 教室归到哪栋楼：按选中的楼名前缀匹配，长的优先
    val buildingOf: (RoomInfo) -> String = remember(vm.selectedBuildings) {
        val prefixes = vm.selectedBuildings.sortedByDescending { it.length }
        val of: (RoomInfo) -> String = { room ->
            prefixes.firstOrNull { room.name.startsWith(it) } ?: room.name.substringBefore("-").substringBefore(" ")
        }
        of
    }
    // 不受节次区间影响的全量统计，给柱状图和热力图用
    val periodFree = remember(rooms) { List(PERIOD_TIMES.size) { i -> rooms.count { it.status.getOrNull(i) == 0 } } }
    val buildingFree = remember(rooms, buildingOf) {
        rooms.groupBy(buildingOf).map { (name, list) ->
            BuildingFree(name, list.size, List(PERIOD_TIMES.size) { i -> list.count { it.status.getOrNull(i) == 0 } })
        }
    }
    // 点柱子或热力格：只看这一节；「现在空闲」「刚解放」跟别的节次叠不到一起，换回全部
    val pickPeriod: (Int) -> Unit = { i ->
        startPeriod = i + 1
        endPeriod = i + 1
        if (smartFilter == "现在空闲" || smartFilter == "刚解放") smartFilter = "全部"
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
    // 此刻综合的教室类型（按座位数）
    var combinedType by rememberSaveable { mutableStateOf(ROOM_TYPES.first()) }
    fun seatsOf(room: LiveRoom) = room.seats.takeIf { it > 0 } ?: vm.liveSchedule[room.name]?.size ?: 0
    // 此刻综合的两段，都按选的节次区间与类型筛（和按节查同一套口径：选中的节次都要空）：
    // 此刻空着、且区间里此刻之后的节次也空的；此刻被占、但区间里此刻之后的节次都空的
    val combinedNow = remember(liveInScope, vm.liveSchedule, currentPeriod, startPeriod, endPeriod, combinedType) {
        val span = ((startPeriod - 1)..(endPeriod - 1)).filter { it >= currentPeriod.coerceAtLeast(0) }
        liveInScope.filter { room ->
            val status = vm.liveSchedule[room.name]?.status
            room.isFree && roomTypeMatches(seatsOf(room), combinedType) &&
                (status == null || span.all { status.getOrNull(it) == 0 })
        }.sortedWith(
            compareByDescending<LiveRoom> { vm.liveSchedule[it.name]?.status?.let { s -> consecutiveFree(s, currentPeriod) } ?: 0 }
                .thenBy { it.name },
        )
    }
    val combinedLater = remember(liveInScope, vm.liveSchedule, currentPeriod, startPeriod, endPeriod, combinedType) {
        val span = ((startPeriod - 1)..(endPeriod - 1)).filter { it > currentPeriod }
        if (currentPeriod < 0 || span.isEmpty()) emptyList() else liveInScope.filter { !it.isFree }.mapNotNull { room ->
            val status = vm.liveSchedule[room.name]?.status ?: return@mapNotNull null
            if (!roomTypeMatches(seatsOf(room), combinedType) || span.any { status.getOrNull(it) != 0 }) return@mapNotNull null
            // 从区间起点往前倒，找它最早空出来的那一节（不早于下一节）
            var from = span.first()
            while (from - 1 > currentPeriod && status.getOrNull(from - 1) == 0) from--
            // 此刻没课却有人（自习、借用、活动）：这种占用常常比一节课拖得久，两节以内空出来的要标出来
            UpcomingRoom(room, from, consecutiveFree(status, from), lingering = room.isInUse && from - currentPeriod <= 2)
        }.sortedWith(compareBy<UpcomingRoom> { it.from }.thenByDescending { it.run }.thenBy { it.room.name })
    }
    // 综合模式下柱状图的数：当天课表里每节空着的间数
    val combinedPeriodFree = remember(vm.liveSchedule, combinedType) {
        val schedule = vm.liveSchedule.values.filter { roomTypeMatches(it.size, combinedType) }
        List(PERIOD_TIMES.size) { i -> schedule.count { it.status.getOrNull(i) == 0 } }
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

    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
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
                    val sources = vm.availableSources
                    if (sources.size > 1) {
                        var menuOpen by remember { mutableStateOf(false) }
                        fun pick(target: RoomSource) {
                            menuOpen = false
                            vm.selectSource(target)
                            if (target == RoomSource.CDN && !cdnTipRead) tipVisible = true
                        }
                        val periodSources = sources.filter { it != RoomSource.LIVE }
                        fun label(it: RoomSource) = if (it == RoomSource.CDN) "课表 CDN" else "直查教务"
                        com.xjtu.toolbox.ui.components.AppIconMenu(
                            expanded = menuOpen,
                            onExpandedChange = { menuOpen = it },
                            icon = { Icon(Icons.Default.Tune, contentDescription = "查询方式") },
                        ) {
                            if (RoomSource.LIVE in sources) {
                                // 此刻综合：实况 + 当天课表，此刻能用的和接下来会空的一起给
                                com.xjtu.toolbox.ui.components.AppDropdownOption("此刻综合", isLive && vm.combined) {
                                    menuOpen = false
                                    // 进来先看接下来两节：整天都要空的话，此刻被占的几乎全被筛掉
                                    if (currentPeriod >= 0) {
                                        startPeriod = (currentPeriod + 2).coerceIn(1, 11)
                                        endPeriod = (startPeriod + 1).coerceAtMost(11)
                                    }
                                    vm.selectCombined()
                                }
                                com.xjtu.toolbox.ui.components.AppDropdownOption("此刻实况", isLive && !vm.combined) { pick(RoomSource.LIVE) }
                            }
                            // 只有一种按节来源时不用再选，直接叫「按节找空闲」
                            periodSources.singleOrNull()?.let { only ->
                                com.xjtu.toolbox.ui.components.AppDropdownOption("按节找空闲", sourceKind == only) { pick(only) }
                            }
                            if (periodSources.size > 1) {
                                com.xjtu.toolbox.ui.components.AppDropdownSubMenu(
                                    title = "按节找空闲",
                                    summary = if (isLive) null else label(sourceKind),
                                ) {
                                    periodSources.forEach { source ->
                                        com.xjtu.toolbox.ui.components.AppDropdownOption(label(source), sourceKind == source) { pick(source) }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        if (tipVisible) {
            OverlayDialog(
                show = true,
                title = "Cloudflare CDN 查询说明",
                summary = "无需登录。按课表生成，只知道有没有课，不知道此刻有没有人。" +
                    if (accountType == AccountType.POSTGRADUATE) "" else "查不到可改用直查教务。",
                onDismissRequest = {
                    onCdnTipRead()
                    cdnTipRead = true
                    tipVisible = false
                }
            ) {
                TextButton(
                    text = "知道了",
                    onClick = {
                        onCdnTipRead()
                        cdnTipRead = true
                        tipVisible = false
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
                backgroundColor = MiuixTheme.colorScheme.surface,
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
                    val visibleBuildings = sheetBuildings
                        .filter { buildingQuery.isBlank() || it.contains(buildingQuery, ignoreCase = true) }
                    if (sheetBuildings.isNotEmpty()) com.xjtu.toolbox.ui.components.AppOptionGroup(Modifier.padding(top = 4.dp)) {
                        com.xjtu.toolbox.ui.components.AppCheckRow("全部教学楼", allSelected) {
                            vm.setSheetSelected(if (allSelected) setOf(sheetBuildings.firstOrNull() ?: "") else sheetBuildings.toSet())
                        }
                        visibleBuildings.forEach { building ->
                            val isSelected = building in sheetSelected
                            com.xjtu.toolbox.ui.components.AppCheckRow(building, isSelected) {
                                // 至少留一栋：全取消后列表就没东西可查了
                                val next = if (isSelected) sheetSelected - building else sheetSelected + building
                                if (next.isNotEmpty()) vm.setSheetSelected(next)
                            }
                        }
                    }
                    if (isLive) {
                        val byPeriod = if (vm.availableSources.count { it != RoomSource.LIVE } > 1) "「课表 CDN」或「直查教务」" else "「按节找空闲」"
                        Text(
                            "部分教学楼暂无实时状态，可切到${byPeriod}查看。",
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
            val groupedRooms = remember(displayRooms, buildingOf) { displayRooms.groupBy(buildingOf) }
            val liveByBuilding = remember(liveInScope) { liveInScope.groupBy { it.building } }
            // 两栋楼以上才有得比
            val heatmapCard: (@Composable () -> Unit)? = if (!isLive && buildingFree.size >= 2) {
                {
                    BuildingHeatmap(
                        buildings = buildingFree,
                        selected = (startPeriod - 1)..(endPeriod - 1),
                        current = effectivePeriod,
                        onPick = pickPeriod,
                    )
                }
            } else null
            // 宽屏两栏：左边固定一张筛选卡（楼栋、日期、节次、智能筛选），右边教室卡片分两三列，按行对齐（AdaptiveRowGrid）：展开一间看节次时别的卡不会换列。
            // 以前整页限宽 720 居中，筛选卡占掉小半屏、教室一行一张，平板横屏两边各空一大块。
            // 窄屏照旧：筛选卡是列表的第一项，跟着教室一起滚。
            val wideRooms = com.xjtu.toolbox.ui.isWideLayout()
            // 选节次：要空的节次与时间、每节空闲柱状图（有数时）、区间滑条。按节查与此刻综合共用
            val periodPicker: @Composable (counts: List<Int>?, showNow: Boolean) -> Unit = { counts, showNow ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第${startPeriod}-${endPeriod}节", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${PERIOD_TIMES[startPeriod - 1].first} - ${PERIOD_TIMES[endPeriod - 1].second}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (showNow && currentPeriod >= 0) {
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
                if (counts != null) {
                    Spacer(Modifier.height(8.dp))
                    PeriodFreeBars(
                        counts = counts,
                        selected = (startPeriod - 1)..(endPeriod - 1),
                        current = if (showNow) currentPeriod else -1,
                        onPick = pickPeriod,
                    )
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
            }
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
                                        else "实时 · " + hhmm(snap.fetchedAt),
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
                                // 实时状态只有"此刻"，没有节次可选；按状态分段，点一段就是筛选
                                val counts = liveCounts
                                if (vm.combined) {
                                    vm.scheduleNote?.let { note ->
                                        Text(
                                            note,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                    // 和按节查同一套：选要空的节次、选教室类型
                                    periodPicker(combinedPeriodFree.takeIf { vm.liveSchedule.isNotEmpty() }, true)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ROOM_TYPES.forEach { type ->
                                            AppFilterChip(selected = combinedType == type, onClick = { combinedType = type }, label = type)
                                        }
                                    }
                                } else {
                                    if (counts.isNotEmpty()) LiveStatusBar(
                                        free = counts.getValue("空闲"),
                                        inUse = counts.getValue("其它使用"),
                                        inClass = counts.getValue("上课中"),
                                        total = counts.getValue("全部"),
                                        selected = liveFilter,
                                        onSelect = { liveFilter = it },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "其它使用：没排课但有人在用",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
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
                            periodPicker(periodFree.takeIf { rooms.isNotEmpty() }, isToday)
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
                        .verticalScroll(rememberScrollState()).tabContentBottomPadding()
                        .padding(start = 12.dp, top = glassTop + 4.dp, bottom = 16.dp),
                ) {
                    filtersCard()
                    heatmapCard?.let {
                        Spacer(Modifier.height(8.dp))
                        it()
                    }
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
                            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
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

                if (!wideRooms) {
                    fullLineItem(key = "filters") { filtersCard() }
                    heatmapCard?.let { fullLineItem(key = "heatmap") { it() } }
                }

                // 加载中、出错、空：列表里没有「剩余高度」可以撑满，给个最小高度居中
                val stateBox: (@Composable () -> Unit) -> Unit = { content ->
                    fullLineItem(key = "state") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) { content() }
                    }
                }
                val switchToCdn: () -> Unit = {
                    vm.selectSource(RoomSource.CDN)
                    if (!cdnTipRead) tipVisible = true
                }
                if (isLive) when {
                    vm.isLoading && liveInScope.isEmpty() -> stateBox {
                        com.xjtu.toolbox.ui.components.LoadingState("正在读取实时状态…")
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

                    // 此刻综合：先列此刻空着的，再列按课表接下来会空的
                    vm.combined && liveInScope.isNotEmpty() -> {
                        fullLineItem(key = "cmb_now_h") { CombinedHeader("此刻可用", combinedNow.size) }
                        if (combinedNow.isEmpty()) {
                            fullLineItem(key = "cmb_now_empty") {
                                Text(
                                    "此刻没有空着的教室",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        } else {
                            itemsIndexed(combinedNow, key = { _, it -> "cmb_now_${it.name}" }) { i, room ->
                                Box(Modifier.enterOnce(i + 1)) { LiveRoomCard(room, vm.liveSchedule[room.name], currentPeriod, showFreeBadge = false) }
                            }
                        }
                        if (combinedLater.isNotEmpty()) {
                            fullLineItem(key = "cmb_later_h") {
                                // 「有人在用」的含义只在这里说一次，不在每张卡上重复
                                CombinedHeader(
                                    "接下来空闲", combinedLater.size,
                                    note = "有人在用：没排课但此刻有人，可能拖过下一节".takeIf { combinedLater.any { it.lingering } },
                                )
                            }
                            itemsIndexed(combinedLater, key = { _, it -> "cmb_later_${it.room.name}" }) { i, upcoming ->
                                Box(Modifier.enterOnce(i + 1)) { UpcomingRoomCard(upcoming, vm.liveSchedule[upcoming.room.name], currentPeriod) }
                            }
                        }
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
                                    LiveMiniBar(liveByBuilding[building].orEmpty(), Modifier.padding(end = 8.dp))
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
                        val pg = vm.directProgress
                        com.xjtu.toolbox.ui.components.LoadingState(
                            if (pg != null) "直查教务更新中…${pg.first}/${pg.second}" else "正在查询…",
                        )
                    }

                    vm.errorMessage != null -> stateBox {
                        // CDN 源只是上游每天生成的快照，缺了就给一条能自己查的路
                        val fallback = if (sourceKind == RoomSource.CDN) {
                            listOf(RoomSource.DIRECT, RoomSource.LIVE).firstOrNull { it in vm.availableSources }
                        } else {
                            null
                        }
                        RoomStateBlock(
                            icon = Icons.Outlined.CloudOff,
                            title = if (sourceKind == RoomSource.DIRECT) "直查教务失败" else "课表数据加载失败",
                            detail = vm.errorMessage,
                            isError = true,
                            primaryLabel = "重试",
                            onPrimary = { vm.refresh() },
                            secondaryLabel = when (fallback) {
                                RoomSource.DIRECT -> "改用直查教务"
                                RoomSource.LIVE -> "改看实时状态"
                                else -> null
                            },
                            onSecondary = { fallback?.let(vm::selectSource) },
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
    val copy = rememberCopyText()

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { copy(room.name) }
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
                        // 空几节由右边的标签说，这里只补座位数和下一节课在哪
                        when {
                            isNowFree && nextBusy != null -> "${room.size} 座 · 第${nextBusy + 1}节有课"
                            isNowFree -> "${room.size} 座 · 之后没课"
                            else -> "${room.size} 座"
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
                    breaks = slotBreaks(room.status.size),
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

/**
 * @param schedule 当天课表（CDN）里的同名教室，可能没有（平台多出来的教室、CDN 拿不到）。
 * 有的话画节次条，并告诉用户这间空教室能用到什么时候。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveRoomCard(
    room: LiveRoom,
    schedule: RoomInfo?,
    currentPeriod: Int,
    /** 「此刻可用」那一段里全是空闲的，标签不再重复。 */
    showFreeBadge: Boolean = true,
) {
    val copy = rememberCopyText()
    val seats = room.seats.takeIf { it > 0 } ?: schedule?.size ?: 0
    val seatText = if (seats > 0) "$seats 座" else ""
    val status = schedule?.status.orEmpty()
    val nextBusy = if (currentPeriod >= 0) {
        status.indices.firstOrNull { it > currentPeriod && status[it] != 0 }
    } else null
    val (badge, badgeColor) = when {
        room.isFree -> (if (showFreeBadge) "空闲" else null) to MiuixTheme.colorScheme.primary
        room.isInUse -> "${room.people} 人" to IN_USE_COLOR
        room.isInClass -> "上课中" to MiuixTheme.colorScheme.error
        else -> null to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    // 状态与人数已在右边的标签里，这里只补座位数、下一节课或正在上的课
    val summary = when {
        room.isFree && status.isNotEmpty() && currentPeriod >= 0 ->
            listOf(seatText, if (nextBusy != null) "第${nextBusy + 1}节有课" else "之后没课")
        room.isInClass -> listOfNotNull(room.course, room.teacher)
        else -> listOf(seatText)
    }.filter { it.isNotBlank() }.joinToString(" · ")

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { copy(room.name) }
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
                    if (summary.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            summary,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (badge != null) {
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
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                com.xjtu.toolbox.ui.components.SlotStripe(
                    free = status.map { it == 0 },
                    freeColor = MiuixTheme.colorScheme.primary,
                    currentIndex = currentPeriod,
                    busyColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.14f),
                    height = 5.dp,
                    breaks = slotBreaks(status.size),
                )
            }
        }
    }
}

// ══════ 此刻综合 ══════

/** 此刻被占、按课表第 [from] 节（0 起）起空、连空 [run] 节的教室；[lingering] 见 combinedLater 的说明。 */
private class UpcomingRoom(val room: LiveRoom, val from: Int, val run: Int, val lingering: Boolean)

@Composable
private fun CombinedHeader(title: String, count: Int, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$count 间", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (note != null) {
            Text(note, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingRoomCard(upcoming: UpcomingRoom, schedule: RoomInfo?, currentPeriod: Int) {
    val copy = rememberCopyText()
    val room = upcoming.room
    val status = schedule?.status.orEmpty()
    val (badge, badgeColor) = when {
        upcoming.lingering -> "有人在用" to IN_USE_COLOR
        room.isInClass -> "上课中" to MiuixTheme.colorScheme.error
        room.isInUse -> "${room.people} 人" to IN_USE_COLOR
        else -> "占用" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val start = PERIOD_TIMES.getOrNull(upcoming.from)?.first.orEmpty()
    val seats = room.seats.takeIf { it > 0 } ?: schedule?.size ?: 0
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { copy(room.name) }),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            "$seats 座".takeIf { seats > 0 },
                            "$start 起空 ${upcoming.run} 节",
                        ).joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = badgeColor.copy(alpha = 0.12f)) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
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
                    breaks = slotBreaks(status.size),
                )
            }
        }
    }
}

// ══════ 加载失败 / 空结果 ══════

/** 空教室页里所有报错的文案。 */
internal fun rawError(e: Throwable): String = com.xjtu.toolbox.error.FriendlyError.of(e, "查询")

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
