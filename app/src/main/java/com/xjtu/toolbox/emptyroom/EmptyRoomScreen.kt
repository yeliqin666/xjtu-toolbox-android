package com.xjtu.toolbox.emptyroom

import android.content.Intent
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.activity.compose.BackHandler
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.overScrollVertical

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.xjtu.toolbox.ui.components.AppFilterChip
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.util.CredentialStore

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
fun EmptyRoomScreen(
    onBack: () -> Unit,
    /**
     * 可选：已通过 JWXT 认证的 OkHttpClient（一般取自 jwxtLogin.client，或校外 vpnClient）。
     * 不为 null 时 UI 会显示「数据源：CDN / 直查教务」切换；为 null 时维持原 CDN 模式。
     */
    directClient: okhttp3.OkHttpClient? = null,
) {
    val context = LocalContext.current
    val credentialStore = remember(context) { CredentialStore(context) }
    val accountType = remember { credentialStore.accountType }
    val api = remember(context) { EmptyRoomApi(context) }
    val uncachedApi = remember { EmptyRoomApi() }
    val emptyRoomCache = remember(context) { EmptyRoomCache(context) }
    val directApi = remember(directClient, emptyRoomCache) { directClient?.let { EmptyRoomDirectQuery(it, emptyRoomCache) } }
    val uncachedDirectApi = remember(directClient) { directClient?.let { EmptyRoomDirectQuery(it) } }
    val prefs = remember { context.getSharedPreferences("empty_room", 0) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var useDirectQuery by rememberSaveable {
        mutableStateOf(accountType != AccountType.POSTGRADUATE && prefs.getBoolean("empty_room_use_direct_query", false))
    }
    var showCdnTip by remember { mutableStateOf(!credentialStore.hasReadEmptyRoomCdnTip && !useDirectQuery) }
    var directProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val campusNames = CAMPUS_BUILDINGS.keys.toList()
    fun savedCampusIndex(): Int {
        val savedCampus = prefs.getString("empty_room_last_campus", null) ?: return 0
        return campusNames.indexOf(savedCampus).takeIf { it >= 0 } ?: 0
    }
    var selectedCampusIndex by rememberSaveable { mutableIntStateOf(savedCampusIndex()) }
    val selectedCampus = campusNames.getOrElse(selectedCampusIndex) { campusNames.firstOrNull() ?: "" }

    val buildings = remember(selectedCampus) { CAMPUS_BUILDINGS[selectedCampus] ?: emptyList() }
    // 教学楼多选
    var selectedBuildings by rememberSaveable(selectedCampus) {
        val saved = prefs.getString("empty_room_last_buildings_$selectedCampus", null)
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.filterTo(mutableSetOf()) { it in buildings }
            ?.takeIf { it.isNotEmpty() }
        mutableStateOf(saved ?: setOf(buildings.firstOrNull().orEmpty()))
    }

    val availableDates = remember { api.getAvailableDates() }
    var selectedDate by rememberSaveable { mutableStateOf(availableDates.firstOrNull() ?: "") }

    // 智能筛选
    var smartFilter by rememberSaveable { mutableStateOf("现在空闲") }

    // 用户自选节数区间（1-based）
    var startPeriod by rememberSaveable { mutableIntStateOf(1) }
    var endPeriod by rememberSaveable { mutableIntStateOf(11) }

    fun persistBuildingSelection() {
        prefs.edit()
            .putString("empty_room_last_campus", selectedCampus)
            .putString("empty_room_last_buildings_$selectedCampus", selectedBuildings.filter { it.isNotBlank() }.joinToString("|"))
            .apply()
    }

    LaunchedEffect(selectedCampusIndex, selectedBuildings) {
        persistBuildingSelection()
    }

    LaunchedEffect(useDirectQuery) {
        if (accountType == AccountType.POSTGRADUATE && useDirectQuery) {
            useDirectQuery = false
        } else {
            prefs.edit().putBoolean("empty_room_use_direct_query", useDirectQuery).apply()
        }
    }

    // 当前节次
    val currentPeriod = remember { getCurrentPeriod() }
    val isToday = selectedDate == LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val effectivePeriod = if (isToday) currentPeriod else -1

    // 自动查询（选择改变 / 数据源切换即触发）
    // [取消语义] LaunchedEffect 在 keys 变化时自动 cancel 旧 coroutine。
    // OkHttp 阻塞调用本身不响应 cancel，但我们在每个 building / period 循环开头主动
    // ensureActive()：cancel 后立即抛 CancellationException → 不再发起新请求 → UI 不会被
    // 旧结果污染。正在飞的单次 HTTP 调用最多多跑完一次后丢弃，整体行为符合「杀死旧的、开新的」。
    // [优化] 单楼缓存：key = "campus|building|date"，value = 该楼当天教室列表。
    // 用户重复勾选同一建筑（A→AB→A）时，命中缓存的不会重新发请求；
    // 仅 cache miss 的建筑才进网络。日期/校区变化时缓存自然失效（不参与命中的 key 不同）。
    val buildingCache = remember { mutableStateMapOf<String, Pair<String, List<RoomInfo>>>() }

    // [并发计数] 每次 LaunchedEffect 启动 +1，用 generation 标记当前查询。
    // 旧 coroutine 在新 LaunchedEffect 启动后 myGen != queryGenCount，禁止更新 UI。
    // 这比 coroutineContext[Job].isActive 更可靠：Compose Coroutines 在 withContext 后
    // 父 Job 状态判断有时不及时，导致旧 coroutine 误判 active 继续写 UI。
    val queryGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val refreshNonce = remember { mutableIntStateOf(0) }
    val handledRefreshNonce = remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedCampus, selectedBuildings, selectedDate, useDirectQuery, refreshNonce.intValue) {
        val myGen = queryGeneration.incrementAndGet()
        fun isLatest(): Boolean = myGen == queryGeneration.get()
        val forceRefresh = refreshNonce.intValue != handledRefreshNonce.intValue
        handledRefreshNonce.intValue = refreshNonce.intValue

        // [debounce] 用户在 BottomSheet 里连续勾选多个教学楼时，selectedBuildings 短时间变化多次。
        // 350ms 防抖：连续操作只触发最后一次。这一步本身 suspend，coroutine cancel 会立即跳过。
        kotlinx.coroutines.delay(350L)
        if (!isLatest()) return@LaunchedEffect

        val active = selectedBuildings.filter { it.isNotEmpty() }.toSet()
        if (active.isEmpty()) {
            rooms = emptyList()
            isLoading = false
            directProgress = null
            return@LaunchedEffect
        }

        // 拆分 cache hit vs miss
        val cacheDay = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        buildingCache.entries.removeAll { it.value.first != cacheDay || it.value.second.isEmpty() }
        val cachedRows = mutableListOf<RoomInfo>()
        val toFetch = mutableListOf<String>()
        for (b in active) {
            val sourceKey = if (useDirectQuery) "direct" else "cdn"
            val key = "$sourceKey|$selectedCampus|$b|$selectedDate"
            val hit = buildingCache[key]
            if (!forceRefresh && hit != null && hit.first == cacheDay && hit.second.isNotEmpty()) {
                cachedRows.addAll(hit.second)
            } else {
                buildingCache.remove(key)
                toFetch.add(b)
            }
        }
        // 全部命中 cache → 不发任何请求
        if (toFetch.isEmpty()) {
            android.util.Log.d("EmptyRoomScreen", "gen=$myGen all ${active.size} buildings cache hit")
            rooms = cachedRows.sortedBy { it.name }
            errorMessage = null
            isLoading = false
            directProgress = null
            return@LaunchedEffect
        }

        // 部分需要网络
        isLoading = true
        errorMessage = null
        directProgress = null
        try {
            val queryResult: Pair<List<RoomInfo>, List<Pair<String, List<RoomInfo>>>> = withContext(Dispatchers.IO) {
                val direct = if (forceRefresh) uncachedDirectApi else directApi
                if (useDirectQuery) {
                    if (direct == null) {
                        throw RuntimeException("未完成教务登录，请返回后重新进入空闲教室")
                    }
                    val merged = mutableListOf<RoomInfo>().also { it.addAll(cachedRows) }
                    val fetched = mutableListOf<Pair<String, List<RoomInfo>>>()
                    val totalBuildings = toFetch.size
                    toFetch.forEachIndexed { idx, building ->
                        if (!isLatest()) {
                            throw kotlinx.coroutines.CancellationException("superseded by newer query")
                        }
                        try {
                            val rows = direct.queryDay(selectedCampus, building, selectedDate) { period, total ->
                                if (isLatest()) {
                                    directProgress = (idx * total + period) to (totalBuildings * total)
                                }
                            }
                            fetched.add(building to rows)
                            merged.addAll(rows)
                        } catch (e: NoDataException) {
                            android.util.Log.w("EmptyRoomScreen", "direct skip $building: ${e.message}")
                        }
                    }
                    merged.sortedBy { it.name } to fetched
                } else {
                    val cdnApi = if (forceRefresh) uncachedApi else api
                    cdnApi.getEmptyRoomsMulti(selectedCampus, active, selectedDate) to emptyList<Pair<String, List<RoomInfo>>>()
                }
            }
            val (result, fetchedRows) = queryResult
            if (isLatest()) {
                fetchedRows.forEach { (building, rowsForBuilding) ->
                    if (rowsForBuilding.isNotEmpty()) {
                        buildingCache["direct|$selectedCampus|$building|$selectedDate"] = cacheDay to rowsForBuilding
                    }
                }
                rooms = result
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 旧查询被新查询取代，不更新 UI；不再 rethrow（rethrow 会让 LaunchedEffect 抛异常）
            android.util.Log.d("EmptyRoomScreen", "gen=$myGen cancelled (newer gen ${queryGeneration.get()})")
        } catch (e: NoDataException) {
            if (isLatest()) { errorMessage = e.message; rooms = emptyList() }
        } catch (e: java.net.ConnectException) {
            if (isLatest()) errorMessage = "网络不可用，请检查连接"
        } catch (e: java.net.SocketTimeoutException) {
            if (isLatest()) errorMessage = "网络不可用，请检查连接"
        } catch (e: Exception) {
            if (isLatest()) errorMessage = "查询失败：${e.message ?: "未知错误"}"
        } finally {
            // 只有最新一代查询才能更新 isLoading=false，避免旧 coroutine 覆盖新 coroutine 的 isLoading=true
            if (isLatest()) {
                isLoading = false
                directProgress = null
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

    fun shareCurrentRooms() {
        val text = buildString {
            appendLine("空闲教室")
            appendLine("$selectedCampus · ${selectedBuildings.joinToString("、")} · $selectedDate")
            appendLine("节次：第${startPeriod}节 - 第${endPeriod}节")
            appendLine()
            displayRooms.forEach { room ->
                appendLine("${room.name} · ${room.size}座")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享空闲教室"))
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = "空闲教室",
                largeTitle = "空闲教室",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        text = if (useDirectQuery) "直查" else "CDN",
                        onClick = {
                            if (accountType != AccountType.POSTGRADUATE) {
                                useDirectQuery = !useDirectQuery
                                if (!useDirectQuery && !credentialStore.hasReadEmptyRoomCdnTip) {
                                    showCdnTip = true
                                }
                                refreshNonce.intValue++
                            }
                        }
                    )
                    TextButton(
                        text = "分享",
                        onClick = { shareCurrentRooms() }
                    )
                    IconButton(onClick = { refreshNonce.intValue++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (showCdnTip) {
            OverlayDialog(
                show = true,
                title = "Cloudflare CDN 查询说明",
                summary = "CDN 查询无需登录教务系统，也不会发送账号相关信息；数据由后台定时生成，可能不是实时结果。如果查询失败或需要最新数据，可切换为直查教务。",
                onDismissRequest = {
                    credentialStore.hasReadEmptyRoomCdnTip = true
                    showCdnTip = false
                }
            ) {
                Button(
                    onClick = {
                        credentialStore.hasReadEmptyRoomCdnTip = true
                        showCdnTip = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("知道了")
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // ── 选择器区域 (一体化) ──
            val showBuildingSheet = remember { mutableStateOf(false) }
            BackHandler(enabled = showBuildingSheet.value) {
                showBuildingSheet.value = false
            }

            top.yukonga.miuix.kmp.basic.Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
            ) {
                Column {
                    // 校区 — TabRowWithContour 单选
                    TabRowWithContour(
                        tabs = campusNames.map { it.removeSuffix("校区") },
                        selectedTabIndex = selectedCampusIndex,
                        onTabSelected = {
                            selectedCampusIndex = it
                            prefs.edit().putString("empty_room_last_campus", campusNames.getOrElse(it) { selectedCampus }).apply()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
                    )

                    // 教学楼 — 摘要行
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) { showBuildingSheet.value = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("教学楼", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selectedBuildings.joinToString("、"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, null,
                            Modifier.size(16.dp).graphicsLayer { rotationZ = 180f },
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
                    )

                    // 时间筛选
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
                                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "第${currentPeriod + 1}节 ${PERIOD_TIMES[currentPeriod].first}",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 节数区间（标题 + 选择器）
                    Text(
                        "空闲节次范围",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                            AppDropdownMenu(expanded = startExpanded, onDismissRequest = { startExpanded = false }) {
                                (1..11).forEach { p ->
                                    AppDropdownMenuItem(
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
                        Text("—", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                        // 结束节
                        var endExpanded by remember { mutableStateOf(false) }
                        Box {
                            AppFilterChip(
                                selected = true,
                                onClick = { endExpanded = true },
                                label = "第${endPeriod}节止"
                            )
                            AppDropdownMenu(expanded = endExpanded, onDismissRequest = { endExpanded = false }) {
                                (startPeriod..11).forEach { p ->
                                    AppDropdownMenuItem(
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
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
            } // Column inside Card
            } // Card

            // 教学楼选择 BottomSheet
            OverlayBottomSheet(
                show = showBuildingSheet.value,
                title = "选择教学楼",
                onDismissRequest = { showBuildingSheet.value = false }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 全选/取消全选
                    val allSelected = selectedBuildings.size == buildings.size
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = SinkFeedback()
                            ) {
                                selectedBuildings = if (allSelected) setOf(buildings.firstOrNull() ?: "") else buildings.toSet()
                                persistBuildingSelection()
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            state = if (allSelected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                            onClick = {
                                val nowAll = !allSelected
                                selectedBuildings = if (nowAll) buildings.toSet() else setOf(buildings.firstOrNull() ?: "")
                                persistBuildingSelection()
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("全选", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    buildings.forEach { building ->
                        val isSelected = building in selectedBuildings
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = SinkFeedback()
                                ) {
                                    selectedBuildings = if (isSelected) {
                                        val newSet = selectedBuildings - building
                                        if (newSet.isEmpty()) selectedBuildings else newSet
                                    } else selectedBuildings + building
                                    persistBuildingSelection()
                                }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                state = if (isSelected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                                onClick = {
                                    selectedBuildings = if (!isSelected) selectedBuildings + building
                                    else {
                                        val newSet = selectedBuildings - building
                                        if (newSet.isEmpty()) selectedBuildings else newSet
                                    }
                                    persistBuildingSelection()
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(building, style = MiuixTheme.textStyles.body1)
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
                            val pg = directProgress
                            Text(
                                if (pg != null) "直查教务 ${pg.first}/${pg.second}…" else "正在查询...",
                                style = MiuixTheme.textStyles.body2
                            )
                        }
                    }
                }

                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(errorMessage!!, color = MiuixTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { refreshNonce.intValue++ }) { Text("重试") }
                        }
                    }
                }

                rooms.isEmpty() && selectedBuildings.all { it.isEmpty() } -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择教学楼后自动查询",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }

                displayRooms.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无符合条件的教室", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextButton(text = "查看全部", onClick = { smartFilter = "全部" })
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
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        val totalRooms = rooms.size
                        if (totalRooms != displayRooms.size) {
                            Text(
                                " / 共 $totalRooms",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                            )
                        }
                    }

                    PeriodHeader(effectivePeriod)

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().overScrollVertical()
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
                    color = if (isCurrent) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
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

    top.yukonga.miuix.kmp.basic.Card(
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
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = if (isNowFree)
                MiuixTheme.colorScheme.surfaceVariant
            else
                MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
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
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (!isNowFree && currentPeriod >= 0) Modifier.alpha(0.5f) else Modifier
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${room.size}座",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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
                                style = MiuixTheme.textStyles.footnote1,
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

                    val bgColor = if (isFree) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MiuixTheme.colorScheme.error.copy(alpha = 0.12f)

                    val txtColor = if (isFree) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.error

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .then(
                                if (isCurrent) Modifier.border(
                                    1.5.dp,
                                    MiuixTheme.colorScheme.primary,
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


