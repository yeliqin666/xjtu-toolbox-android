package com.xjtu.toolbox.emptyroom

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.xjtu.toolbox.ui.components.AppFilterChip

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
            tags.add("刚解放" to MaterialTheme.colorScheme.tertiary)
        }
        when {
            freePeriods >= 4 -> tags.add("空闲≥4节" to MaterialTheme.colorScheme.primary)
            freePeriods >= 2 -> tags.add("空闲${freePeriods}节" to MaterialTheme.colorScheme.primary)
            else -> tags.add("本节空闲" to MaterialTheme.colorScheme.secondary)
        }
    }

    if (room.size >= 100) tags.add("大教室" to MaterialTheme.colorScheme.tertiary)

    return tags
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmptyRoomScreen(onBack: () -> Unit) {
    val api = remember { EmptyRoomApi() }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val campusNames = CAMPUS_BUILDINGS.keys.toList()
    var selectedCampus by rememberSaveable { mutableStateOf(campusNames.firstOrNull() ?: "") }

    val buildings = remember(selectedCampus) { CAMPUS_BUILDINGS[selectedCampus] ?: emptyList() }
    // 切换校区时自动选第一个教学楼，消除 LaunchedEffect 竞态
    var selectedBuilding by rememberSaveable(selectedCampus) { mutableStateOf(buildings.firstOrNull() ?: "") }

    val availableDates = remember { api.getAvailableDates() }
    var selectedDate by rememberSaveable { mutableStateOf(availableDates.firstOrNull() ?: "") }

    // 智能筛选
    var smartFilter by rememberSaveable { mutableStateOf("现在空闲") }

    // 用户自选节数区间（1-based）
    var startPeriod by rememberSaveable { mutableIntStateOf(1) }
    var endPeriod by rememberSaveable { mutableIntStateOf(11) }

    val scope = rememberCoroutineScope()

    // 当前节次
    val currentPeriod = remember { getCurrentPeriod() }
    val isToday = selectedDate == LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val effectivePeriod = if (isToday) currentPeriod else -1

    // 自动查询（选择改变即触发）
    LaunchedEffect(selectedCampus, selectedBuilding, selectedDate) {
        if (selectedBuilding.isNotEmpty()) {
            isLoading = true
            errorMessage = null
            try {
                val result = withContext(Dispatchers.IO) {
                    api.getEmptyRooms(selectedCampus, selectedBuilding, selectedDate)
                }
                rooms = result
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e // 绝不吞掉取消
            } catch (e: NoDataException) {
                errorMessage = e.message; rooms = emptyList()
            } catch (e: java.net.ConnectException) {
                errorMessage = "无法连接服务器，请检查网络"
            } catch (e: java.net.SocketTimeoutException) {
                errorMessage = "连接超时，请稍后再试"
            } catch (e: Exception) {
                errorMessage = "查询失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 选择器区域 ──

            // 位置选择
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // 校区
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        campusNames.forEach { campus ->
                            AppFilterChip(
                                selected = selectedCampus == campus,
                                onClick = { selectedCampus = campus },
                                label = campus.removeSuffix("校区")
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 教学楼
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        buildings.forEach { building ->
                            AppFilterChip(
                                selected = selectedBuilding == building,
                                onClick = { selectedBuilding = building },
                                label = building
                            )
                        }
                    }
                }
            }

            // 时间筛选
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // 日期 + 当前节次
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        availableDates.forEachIndexed { index, date ->
                            val label = if (index == 0) "今天" else "明天"
                            AppFilterChip(
                                selected = selectedDate == date,
                                onClick = { selectedDate = date },
                                label = "$label ${date.substring(5)}"
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (isToday && currentPeriod >= 0) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "第${currentPeriod + 1}节 ${PERIOD_TIMES[currentPeriod].first}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 节数区间（标题 + 选择器）
                    Text(
                        "空闲节次范围",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 起始节
                        var startExpanded by remember { mutableStateOf(false) }
                        Box {
                            AppFilterChip(
                                selected = true,
                                onClick = { startExpanded = true },
                                label = "第${startPeriod}节起"
                            )
                            DropdownMenu(expanded = startExpanded, onDismissRequest = { startExpanded = false }) {
                                (1..11).forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text("第${p}节  ${PERIOD_TIMES[p - 1].first}") },
                                        onClick = {
                                            startPeriod = p
                                            if (endPeriod < p) endPeriod = p
                                            startExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        // 结束节
                        var endExpanded by remember { mutableStateOf(false) }
                        Box {
                            AppFilterChip(
                                selected = true,
                                onClick = { endExpanded = true },
                                label = "第${endPeriod}节止"
                            )
                            DropdownMenu(expanded = endExpanded, onDismissRequest = { endExpanded = false }) {
                                (startPeriod..11).forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text("第${p}节  ${PERIOD_TIMES[p - 1].first}") },
                                        onClick = { endPeriod = p; endExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 智能筛选（独立行，可横向滚动）
                    Text(
                        "快捷筛选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("全部", "现在空闲", "刚解放", "大教室").forEach { filter ->
                            AppFilterChip(
                                selected = smartFilter == filter,
                                onClick = { smartFilter = filter },
                                label = filter
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── 内容区 ──
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在查询...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(onClick = {
                                isLoading = true; errorMessage = null
                                scope.launch {
                                    try {
                                        rooms = withContext(Dispatchers.IO) {
                                            api.getEmptyRooms(selectedCampus, selectedBuilding, selectedDate)
                                        }
                                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    } finally { isLoading = false }
                                }
                            }) { Text("重试") }
                        }
                    }
                }

                rooms.isEmpty() && selectedBuilding.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择教学楼后自动查询",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                displayRooms.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无符合条件的教室", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { smartFilter = "全部" }) { Text("查看全部") }
                        }
                    }
                }

                else -> {
                    // 统计 + PeriodHeader
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${displayRooms.size} 间教室",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val totalRooms = rooms.size
                        if (totalRooms != displayRooms.size) {
                            Text(
                                " / 共 $totalRooms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    PeriodHeader(effectivePeriod)

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayRooms, key = { it.name }) { room ->
                            SmartRoomCard(room, effectivePeriod)
                        }
                    }
                }
            }
        }
    }
}

// ══════ 节次时刻表头 ══════

@Composable
private fun PeriodHeader(currentPeriod: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(start = 102.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        (1..11).forEach { p ->
            val isCurrent = (p - 1) == currentPeriod
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$p",
                    fontSize = 9.sp,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(room.name))
                    android.widget.Toast.makeText(context, "已复制：${room.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isNowFree)
                MaterialTheme.colorScheme.surfaceContainerLow
            else
                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左：教室信息
            Column(modifier = Modifier.width(92.dp)) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (!isNowFree && currentPeriod >= 0) Modifier.alpha(0.5f) else Modifier
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${room.size}座",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    tags.take(2).forEach { (label, color) ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = color.copy(alpha = 0.12f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 右：节次状态网格
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                room.status.forEachIndexed { index, value ->
                    val isFree = value == 0
                    val isCurrent = index == currentPeriod
                    val isPast = currentPeriod >= 0 && index < currentPeriod

                    val bgColor = if (isFree) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer

                    val txtColor = if (isFree) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .then(
                                if (isCurrent) Modifier.border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(3.dp)
                                ) else Modifier
                            )
                            .background(bgColor)
                            .alpha(if (isPast) 0.4f else 1f)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontSize = 9.sp,
                            color = txtColor,
                            textAlign = TextAlign.Center,
                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
