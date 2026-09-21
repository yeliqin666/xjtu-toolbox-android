package com.xjtu.toolbox

import com.xjtu.toolbox.nav.expandOriginSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.*
import com.xjtu.toolbox.ui.theme.serviceColor
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ExpressiveIcon
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.home.ServiceCategory

// ══════════════════════════════════════════
//  Tab 1 — 首页
// ══════════════════════════════════════════

@Composable
private fun HomeHero(
    greetingName: String,
    dateLabel: String,
    weekNumber: Int,
    isLoggedIn: Boolean,
    isFocusLoaded: Boolean,
    reminder: ScheduleReminderInfo?,
    balance: Float,
    todaySpend: Float,
    onOpenCourses: () -> Unit,
    onOpenCard: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val hour = java.time.LocalTime.now().hour
    val greeting = when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    val headline = if (greetingName.isBlank()) greeting else "$greeting，$greetingName"
    val meta = buildString {
        append(dateLabel)
        if (weekNumber in 1..25) append(" · 第${weekNumber}周")
    }
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val artSize = 128.dp

    val courseTitle: String
    val courseDetail: String?
    when {
        !isLoggedIn -> {
            courseTitle = "登录后查看课表和余额"
            courseDetail = "课表、校园卡会显示在这里"
        }
        !isFocusLoaded -> {
            courseTitle = "正在读取今日安排…"
            courseDetail = null
        }
        reminder != null -> {
            val now = java.time.LocalDateTime.now()
            val minutesUntil = java.time.Duration.between(now, reminder.startAt)
                .toMinutes().coerceAtLeast(0)
            val dayLabel = formatScheduleReminderDateLabel(
                reminder.startAt.toLocalDate(), now.toLocalDate()
            )
            val startLabel = formatMinuteClock(reminder.startAt.hour * 60 + reminder.startAt.minute)
            val endLabel = reminder.endAt?.let {
                formatMinuteClock(it.hour * 60 + it.minute)
            }
            val timePart = if (endLabel != null) "$startLabel–$endLabel" else startLabel
            courseTitle = reminder.name
            courseDetail = buildString {
                append(formatScheduleReminderEta(minutesUntil))
                if (dayLabel != "今天") append(" · $dayLabel")
                append(" · $timePart")
                if (reminder.location.isNotBlank()) append(" · ${reminder.location}")
            }
        }
        else -> {
            courseTitle = "未来两周暂无日程"
            courseDetail = "打开课表看看"
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor),
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp, y = (-64).dp)
                    .size(260.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(primary.copy(alpha = 0.20f), Color.Transparent),
                        ),
                    ),
            )
            Image(
                painter = painterResource(R.drawable.home_campus_hero),
                contentDescription = "兴庆校区主楼",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 12.dp)
                    .size(artSize),
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                primary.copy(alpha = 0.14f),
                                primary.copy(alpha = 0.04f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 22.dp, bottom = 22.dp, end = artSize + 8.dp),
        ) {
            Text(
                meta,
                style = MiuixTheme.textStyles.footnote1,
                color = primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                headline,
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = SinkFeedback(),
                    onClick = if (isLoggedIn) onOpenCourses else onOpenProfile,
                ),
            ) {
                Text(
                    courseTitle,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (courseDetail != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        courseDetail,
                        style = MiuixTheme.textStyles.footnote1,
                        color = muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isLoggedIn) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = SinkFeedback(),
                        onClick = onOpenCard,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val balanceText = if (balance >= 0f) "¥${"%.2f".format(balance)}" else "—"
                    val spendText = if (todaySpend >= 0f) "¥${"%.2f".format(todaySpend)}" else "—"
                    Text("余额 ", style = MiuixTheme.textStyles.footnote1, color = muted)
                    Text(
                        balanceText,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                        color = if (balance in 0f..30f) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                    )
                    Text("  ·  今日 ", style = MiuixTheme.textStyles.footnote1, color = muted)
                    Text(
                        spendText,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeTab(
    loginState: AppLoginState,
    isRestoring: Boolean = false,
    onNavigate: (String) -> Unit,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCourses: () -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    navBarStyle: String = "floating",
    homeTheme: String = CredentialStore.THEME_CARD,
    showQuickActions: Boolean = true,
    bulletins: List<Bulletin> = emptyList(),
    onBulletinTap: (Bulletin) -> Unit = {},
    onBulletinDismiss: (Bulletin) -> Unit = {},
) {
    // ── 仪表盘数据：下一节日程 + 校园卡余额缓存（供 Hero 重点信息区使用）──
    val heroContext = LocalContext.current
    var scheduleReminderState by remember { mutableStateOf<ScheduleReminderInfo?>(null) }
    var isScheduleReminderLoaded by remember { mutableStateOf(false) }
    var currentWeekNumber by remember { mutableIntStateOf(0) }
    val cardPrefs = remember(com.xjtu.toolbox.account.AccountContext.activeAccountId) {
        com.xjtu.toolbox.card.CampusCardCache.cardPrefs(heroContext)
    }
    var cachedBalance by remember { mutableStateOf(cardPrefs.getFloat("card_balance_cache", -1f)) }
    var cachedTodaySpend by remember { mutableStateOf(cardPrefs.getFloat("card_today_spend_cache", -1f)) }
    LaunchedEffect(loginState.campusCardCacheVersion) {
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedTodaySpend = cardPrefs.getFloat("card_today_spend_cache", -1f)
    }
    LaunchedEffect(loginState.accountId) {
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        // 账号切换：先清旧账号的提醒与校园卡缓存内存态，再从新账号命名空间重读
        isScheduleReminderLoaded = false
        scheduleReminderState = null
        currentWeekNumber = 0
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedTodaySpend = cardPrefs.getFloat("card_today_spend_cache", -1f)
        val loadedFocus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dataCache = com.xjtu.toolbox.util.DataCache(heroContext)
                val gson = com.google.gson.Gson()
                val termListJson = dataCache.get("schedule_term_list", Long.MAX_VALUE)
                val termList = if (termListJson != null) {
                    gson.fromJson(termListJson, Array<String>::class.java)?.toList() ?: emptyList()
                } else emptyList<String>()
                val termCode = termList.firstOrNull() ?: return@withContext Pair(null, 0)
                val apiCourses = com.xjtu.toolbox.schedule.ScheduleCache
                    .readOptimizedCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                    ?: com.xjtu.toolbox.schedule.ScheduleCache
                        .readRawCourses(dataCache, gson, termCode, Long.MAX_VALUE)
                    ?: emptyList()
                val customCourses = try {
                    com.xjtu.toolbox.util.AppDatabase.getInstance(heroContext)
                        .customCourseDao().getByTerm(com.xjtu.toolbox.account.AccountContext.activeAccountId ?: "", termCode)
                        .map { it.toCourseItem() }
                } catch (_: Exception) { emptyList() }
                val allSchedules = apiCourses + customCourses
                val startDateJson = dataCache.get("start_date_$termCode", Long.MAX_VALUE)
                val startDateStr = if (startDateJson != null) gson.fromJson(startDateJson, String::class.java) else null
                val startDate = if (!startDateStr.isNullOrBlank()) runCatching { java.time.LocalDate.parse(startDateStr) }.getOrNull() else null
                val today = java.time.LocalDate.now()
                val weekNumber = if (startDate != null) {
                    com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate, today)
                        .takeIf { it in 1..25 } ?: 0
                } else {
                    0
                }
                if (startDate == null) {
                    return@withContext Pair(null, weekNumber)
                }
                val holidayDates = try {
                    com.xjtu.toolbox.schedule.HolidayApi.getHolidayDates(heroContext)
                } catch (_: Exception) {
                    emptyMap()
                }

                val nowDateTime = java.time.LocalDateTime.now()
                for (offset in 0..14) {
                    val targetDate = today.plusDays(offset.toLong())
                    if (holidayDates.containsKey(targetDate)) continue

                    val targetWeek = com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate, targetDate)
                    if (targetWeek <= 0) continue
                    val daySchedules = allSchedules
                        .filter { it.dayOfWeek == targetDate.dayOfWeek.value && it.isInWeek(targetWeek) }
                        .map {
                            ScheduleReminderCourseInfo(
                                name = it.courseName,
                                location = it.location,
                                startSection = it.startSection,
                                endSection = it.endSection,
                                startMinuteOfDay = it.startMinuteOfDay,
                                endMinuteOfDay = it.endMinuteOfDay
                            )
                        }
                        .sortedBy {
                            it.resolveStartMinute(
                                com.xjtu.toolbox.util.XjtuTime.isSummerTime(targetDate.monthValue)
                            ) ?: Int.MAX_VALUE
                        }
                    for (schedule in daySchedules) {
                        val targetIsSummer = com.xjtu.toolbox.util.XjtuTime.isSummerTime(targetDate.monthValue)
                        val startMinute = schedule.resolveStartMinute(targetIsSummer) ?: continue
                        val safeStartMinute = startMinute.coerceIn(0, (24 * 60) - 1)
                        val startAt = targetDate.atTime(safeStartMinute / 60, safeStartMinute % 60)
                        if (!startAt.isAfter(nowDateTime)) continue

                        val endMinute = schedule.resolveEndMinute(targetIsSummer)
                        val endAt = endMinute?.let { minuteOfDay ->
                            when {
                                minuteOfDay >= 24 * 60 -> targetDate.plusDays(1).atStartOfDay()
                                minuteOfDay >= 0 -> targetDate.atTime(minuteOfDay / 60, minuteOfDay % 60)
                                else -> null
                            }
                        }
                        return@withContext Pair(
                            ScheduleReminderInfo(
                                name = schedule.name,
                                location = schedule.location,
                                startAt = startAt,
                                endAt = endAt
                            ),
                            weekNumber,
                        )
                    }
                }
                Pair(null, weekNumber)
            } catch (_: Exception) {
                Pair(null, 0)
            }
        }
        scheduleReminderState = loadedFocus.first
        currentWeekNumber = loadedFocus.second
        isScheduleReminderLoaded = true
        // 提醒评估在 MainScreen 层跑，够不到这里的状态，用共享信号带过去。
        com.xjtu.toolbox.home.HomeSignals.scheduleReminder = loadedFocus.first?.let {
            com.xjtu.toolbox.home.HomeSignals.ScheduleFocus(it.name, it.startAt)
        }
    }


    // 服务列表的推导（图标、颜色、点击行为）与布局无关，提到分块之前，
    // 好让下面三块内容各自捕获同一份数据，宽窄两种摆法共用。
    //
    // 分类是数据的一部分，不再是注释 + subList(0,7) 这种靠列表顺序的魔法下标：
    // 那种写法一旦在中间插入服务，后面所有分组会静默错位。
    data class MoreSvc(
        val key: String,
        val icon: ImageVector,
        val title: String,
        val color: androidx.compose.ui.graphics.Color,
        val category: ServiceCategory,
        val onClick: () -> Unit
    )
    val ctx = LocalContext.current
    val homeIcons = mapOf(
        Routes.SCHEDULE to Icons.Default.CalendarMonth,
        Routes.EMPTY_ROOM to Icons.Default.MeetingRoom,
        Routes.LMS to Icons.Default.School,
        Routes.SCHOOL_COURSE to Icons.Default.TravelExplore,
        Routes.NEW_ATTENDANCE to Icons.Default.AssignmentTurnedIn,
        Routes.ICLASSFACE to Icons.Default.Face,
        Routes.JWAPP_SCORE to Icons.Default.Assessment,
        Routes.JUDGE to Icons.Default.RateReview,
        Routes.JIAOCAI to Icons.AutoMirrored.Filled.MenuBook,
        Routes.JIAOCAI1 to Icons.AutoMirrored.Filled.LibraryBooks,
        Routes.LIBRARY to Icons.Default.Chair,
        Routes.TRANSCRIPT to Icons.Default.Description,
        Routes.NOTIFICATION to Icons.Default.Notifications,
        Routes.FACULTY to Icons.Default.PersonSearch,
        Routes.CAMPUS_CARD to Icons.Default.CreditCard,
        Routes.PAYMENT_CODE to Icons.Default.QrCode,
        Routes.COUPON to Icons.Default.Restaurant,
        Routes.SCHOOL_CALENDAR to Icons.AutoMirrored.Filled.EventNote,
        Routes.VENUE to Icons.Default.Stadium,
        Routes.FITNESS to Icons.AutoMirrored.Filled.DirectionsRun,
        Routes.YELLOW_PAGE to Icons.Default.ContactPhone,
        Routes.WEBVPN_CONVERTER to Icons.Default.VpnKey,
        Routes.AGENT to Icons.Default.SmartToy,
        Routes.GAMES to Icons.Default.SportsEsports,
        Routes.MATCH to Icons.Default.Groups,
    )
    val allServices = AppServices.homeFor(loginState.accountType).map { svc ->
        MoreSvc(
            key = svc.route,
            icon = homeIcons[svc.route] ?: Icons.Default.Apps,
            title = svc.title,
            color = com.xjtu.toolbox.ui.theme.legacyColor(svc.route),
            category = svc.category,
            onClick = {
                when (svc.route) {
                    Routes.SCHEDULE -> onNavigateToCourses()
                    else -> {
                        val login = loginTypeForRoute(svc.route)
                        if (login != null) onNavigateWithLogin(svc.route, login)
                        else onNavigate(svc.route)
                    }
                }
            },
        )
    }
    fun servicesByKeys(keys: List<String>): List<MoreSvc> =
        keys.mapNotNull { key -> allServices.firstOrNull { it.key == key } }

    fun trackedAction(service: MoreSvc): () -> Unit = {
        com.xjtu.toolbox.util.ServiceUsageTracker.record(ctx, service.key)
        service.onClick()
    }

    val iconColorByKey = mutableMapOf<String, androidx.compose.ui.graphics.Color>()
    for (index in allServices.indices) {
        val service = allServices[index]
        iconColorByKey[service.key] = serviceColor(index, allServices.size)
    }

    fun coloredForIconTheme(service: MoreSvc): MoreSvc {
        return service.copy(color = iconColorByKey[service.key] ?: service.color)
    }

    // 两个主题共用的分类视觉标识。
    // 用 when 而不是 mapOf + getValue：以后加分类漏写了，编译就不过，
    // 不会像「课余」（PLAY）那次一样，到首页渲染分类卡时才抛 NoSuchElementException 闪退。
    fun categoryIcon(category: ServiceCategory): ImageVector = when (category) {
        ServiceCategory.CLASS -> Icons.Default.School
        ServiceCategory.STUDY -> Icons.Default.Assessment
        ServiceCategory.LIFE -> Icons.Default.Restaurant
        ServiceCategory.TOOL -> Icons.Default.SmartToy
        ServiceCategory.PLAY -> Icons.Default.SportsEsports
    }
    fun categoryAccentKey(category: ServiceCategory): String = when (category) {
        ServiceCategory.CLASS -> Routes.SCHEDULE
        ServiceCategory.STUDY -> Routes.JWAPP_SCORE
        ServiceCategory.LIFE -> Routes.CAMPUS_CARD
        ServiceCategory.TOOL -> Routes.AGENT
        ServiceCategory.PLAY -> Routes.GAMES
    }

    // 首页内容拆成三块。窄屏按原顺序竖排，与改造前逐行等价；
    // 宽屏左栏放状态区与常用功能、右栏放分类卡（两列）。三块内部一个字没动。
    val headerSection: @Composable () -> Unit = {
        // ── Zone A: 状态信息行（日期 + 系统状态，大标题已移至 TopAppBar）──
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
        ) {
            val today = java.time.LocalDate.now()
            val weekDay = today.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.CHINESE
            )
            if (bulletins.isNotEmpty()) {
                BulletinNoticePanel(
                    bulletins = bulletins,
                    onTap = onBulletinTap,
                    onDismiss = onBulletinDismiss,
                )
                Spacer(Modifier.height(10.dp))
            }
            HomeHero(
                greetingName = loginState.cachedNickname.orEmpty()
                    .ifBlank { loginState.ywtbUserInfo?.userName.orEmpty() }
                    .ifBlank { loginState.activeUsername },
                dateLabel = "${today.monthValue}月${today.dayOfMonth}日 · $weekDay",
                weekNumber = currentWeekNumber,
                isLoggedIn = loginState.isLoggedIn,
                isFocusLoaded = isScheduleReminderLoaded,
                reminder = scheduleReminderState,
                balance = cachedBalance,
                todaySpend = cachedTodaySpend,
                onOpenCourses = onNavigateToCourses,
                onOpenCard = { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                onOpenProfile = onNavigateToProfile,
            )
            if (loginState.isLoggedIn) {
                Spacer(Modifier.height(10.dp))
                // 网络环境徽标 + 会话数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (netLabel, netColor) = when (loginState.isOnCampus) {
                        true -> "校园网" to androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        false -> "校外 · WebVPN" to androidx.compose.ui.graphics.Color(0xFF1565C0)
                        null -> "网络检测中" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = netColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (loginState.isOnCampus == false) Icons.Default.VpnKey else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = netColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                netLabel,
                                style = MiuixTheme.textStyles.footnote2,
                                color = netColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val sessionColor = if ((loginState.sessionManager?.activeSiteCount ?: 0) > 0)
                        androidx.compose.ui.graphics.Color(0xFF2E7D32)
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    val showStatusSheet = remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = sessionColor.copy(alpha = 0.12f),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = SinkFeedback()
                        ) { showStatusSheet.value = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = sessionColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            // 用全集，避免手写列表漏项与两处不一致
                            val visibleTypes = remember { LoginType.entries.toList() }
                            fun isReady(type: LoginType): Boolean =
                                loginState.sessionManager?.getSiteOrNull(type.siteKey())?.hasLogin == true
                            val ok = visibleTypes.count { isReady(it) }
                            Text(
                                when {
                                    isRestoring -> "正在连接…"
                                    ok > 0 -> "$ok / ${visibleTypes.size} 已就绪"
                                    else -> "未连接"
                                },
                                style = MiuixTheme.textStyles.footnote2,
                                color = sessionColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (showStatusSheet.value) {
                        BackHandler { showStatusSheet.value = false }
                        OverlayBottomSheet(
                            show = showStatusSheet.value,
                            title = "子系统连接状态",
                            onDismissRequest = { showStatusSheet.value = false }
                        ) {
                            Column(
                                Modifier.fillMaxWidth().navigationBarsPadding()
                                    .heightIn(max = 460.dp)
                                    .verticalScroll(rememberScrollState())   // 子系统较多，弹窗内容需要可滚动。
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                val types = LoginType.entries.toList()
                                types.forEach { t ->
                                    val ready = loginState.sessionManager?.getSiteOrNull(t.siteKey())?.hasLogin == true
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val statusColor = if (ready) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                        else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        Icon(
                                            if (ready) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                                            contentDescription = null,
                                            tint = statusColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(t.label, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                                            Text(t.description, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                        Text(
                                            if (ready) "已连接" else "未登录",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = statusColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val quickActionsSection: @Composable () -> Unit = {
        // 「常用功能」原本长在卡片主题的分支里，图标主题没有这一块——保持原样。
        if (homeTheme != CredentialStore.THEME_ICON) {
            val usedKeys = mutableSetOf<String>()

            val quickCandidateKeys = listOf(
                Routes.CAMPUS_CARD,
                Routes.EMPTY_ROOM,
                Routes.PAYMENT_CODE,
                Routes.NOTIFICATION,
                Routes.JWAPP_SCORE,
                Routes.COUPON,
                Routes.LIBRARY,
                Routes.LMS,
                Routes.AGENT,
            ).filterNot { it in usedKeys }
            val quickKeys = if (showQuickActions && quickCandidateKeys.isNotEmpty()) {
                remember(quickCandidateKeys) {
                    // 屁岱曾经被钉死在第 0 位，为的是给主动提醒气泡一个稳定锚点。
                    // 现在气泡改挂底栏正中的屁岱按钮上，这里就没有理由再搞特殊了——
                    // 它回到频率排序里正常参与竞争，四格全部按使用频率给。
                    com.xjtu.toolbox.util.ServiceUsageTracker.topKeys(
                        ctx,
                        quickCandidateKeys,
                        n = 4,
                        fallback = listOf(Routes.CAMPUS_CARD, Routes.EMPTY_ROOM, Routes.NOTIFICATION)
                            .filter { it in quickCandidateKeys } + quickCandidateKeys
                    ).filter { it in quickCandidateKeys }.distinct().take(4)
                }
            } else {
                emptyList()
            }
            val quickShown = servicesByKeys(quickKeys)
            if (quickShown.isNotEmpty()) {
                // 不再把快捷入口从下方分类里剔除：「常用功能」是**额外**多一个入口，
                // 不是把功能搬走。原来会 usedKeys += 之后在分类里过滤掉，
                // 表现为"某个功能从它所属的分类里凭空消失了"，找不到。
                // 气泡搬走后这里不再需要测量图标坐标，整块退回成一个朴素的等分 Row。
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HomeSectionHeader("常用功能", Modifier.padding(start = 4.dp, bottom = 12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .squircleClip(CARD_RADIUS)
                            .background(AppCardColor)
                            .padding(vertical = 10.dp),
                    ) {
                        quickShown.forEach { service ->
                            HomeQuickAction(
                                service.icon,
                                service.title,
                                service.color,
                                onClick = trackedAction(service),
                                modifier = Modifier.weight(1f),
                                originKey = service.key,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val categorySection: @Composable () -> Unit = {
        when (homeTheme) {
            CredentialStore.THEME_ICON -> {
                // 图标主题 = 分类卡（超椭圆 + 主色渐变 + 细描边）+ 卡内 4 列密集宫格。
                // 追求"一屏尽收、认图标找功能"，所以格子小、排布规整。
                //
                // 收藏夹已彻底删除：长按固定会把项目从当前分类"搬"到页面最上方的收藏夹，
                // 用户长按"常用功能"里的东西时体验是"东西突然跑到别的地方去了"，混乱。
                // 现在只保留自动识别的常用功能（按使用频率算），完全不支持手动移动/固定。
                val categories = ServiceCategory.entries.mapNotNull { category ->
                    val items = allServices
                        .filter { it.category == category }
                        .map { coloredForIconTheme(it) }
                    if (items.isEmpty()) null else category to items
                }
                CategoryCards(count = categories.size, spacing = 16.dp) { index ->
                    val (category, items) = categories[index]
                    HomeCategoryCard(
                        title = category.title,
                        subtitle = category.subtitle,
                        icon = categoryIcon(category),
                        accent = com.xjtu.toolbox.ui.theme.legacyColor(categoryAccentKey(category)),
                        rows = items.map { svc ->
                            HomeServiceRow(svc.key, svc.icon, svc.title, svc.color, trackedAction(svc))
                        },
                    )
                }
            }
            else -> {
                // 卡片主题 = Bento（便当盒）不规则网格。
                //
                // 与图标主题的区别必须是**结构性**的，不能只是"给宫格套个壳"——那样两个主题
                // 只剩几列之差，等于没有区别。这里每个分类的首项占一块 2 列宽的大瓷砖
                // （大图标 + 名称，主色实心渐变），旁边竖排两块小的，剩下的走 3 列常规块，
                // 由此产生大小错落的节奏；而图标主题是严格等分的密集宫格。
                //
                // 纯布局实现，没有引第三方组件：Bento 的观感来自尺寸对比与留白节奏，
                // 不是某个控件，为它引依赖只会徒增体积和版本耦合。
                //
                // 分类不再各自成卡，改由标题分隔——瓷砖本身就是卡，再套一层就是"卡中卡"。
                // 各功能的当前状态。全部读**本地缓存**，首页不发任何网络请求
                // （详见 HomeStats）。拿不到就是 null，该功能退回纯入口。
                val statsCtx = LocalContext.current

                var homeStats by remember { mutableStateOf<Map<String, com.xjtu.toolbox.home.HomeStat>>(emptyMap()) }
                // 只负责**读**，不再自己触发刷新——那一步已经提到 MainScreen 层，
                // 好让它与"用户有没有点过首页"解耦。这里跟着 statsVersion 走：
                // MainScreen 每跑完一轮就自增，于是首页拿到的永远是刚落盘的那份。
                LaunchedEffect(
                    loginState.accountId,
                    loginState.campusCardCacheVersion,
                    com.xjtu.toolbox.home.HomeSignals.statsVersion,
                ) {
                    val term = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val dc = com.xjtu.toolbox.util.DataCache(statsCtx)
                            dc.get("schedule_term_list", Long.MAX_VALUE)?.let { j ->
                                com.google.gson.Gson().fromJson(j, Array<String>::class.java)?.firstOrNull()
                            }
                        }.getOrNull()
                    }
                    homeStats = com.xjtu.toolbox.home.HomeStats.collect(statsCtx, term)
                    // 校园卡由 refresher 写进 CampusCardCache 的 prefs，不经过 homeStats，
                    // 所以要单独重读一次，否则 Hero 区的余额要等到下次账号切换才更新。
                    cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
                    cachedTodaySpend = cardPrefs.getFloat("card_today_spend_cache", -1f)
                }

                val statOf: (String) -> Pair<String, String?>? = { key ->
                    when (key) {
                        // 下节课用 Hero 区已算好的那份，避免重复解析课表
                        Routes.SCHEDULE -> scheduleReminderState?.let { r ->
                            val eta = java.time.Duration.between(java.time.LocalDateTime.now(), r.startAt).toMinutes()
                            r.name to buildString {
                                append(formatMinuteClock(r.startAt.hour * 60 + r.startAt.minute))
                                if (r.location.isNotBlank()) append(" · ${r.location}")
                                if (eta > 0) append(" · ${formatScheduleReminderEta(eta)}")
                            }
                        }
                        // 日程没有下节课时，退回最近一场考试（HomeStats 把它挂在同一个 key 下）
                        // 快速考勤流水不再单列在首页，今天刷过卡就借新版考勤这一行露出来
                        Routes.NEW_ATTENDANCE -> homeStats[Routes.NEW_ATTENDANCE]?.let { att ->
                            val punch = homeStats[Routes.ICLASSFACE]
                            val detail = if (punch != null && punch.value != "今日未刷卡") {
                                "今日已刷 ${punch.value}" + (punch.detail?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                            } else att.detail
                            att.value to detail
                        }
                        else -> homeStats[key]?.let { it.value to it.detail }
                    }
                }

                val categoryCards = ServiceCategory.entries.mapNotNull { category ->
                    val items = allServices.filter { it.category == category }
                    if (items.isEmpty()) null else category to items
                }
                CategoryCards(count = categoryCards.size, spacing = 14.dp) { index ->
                    val (category, items) = categoryCards[index]
                    val rows = items.map { svc ->
                        val stat = statOf(svc.key)
                        HomeServiceRow(
                            key = svc.key,
                            icon = svc.icon,
                            title = svc.title,
                            color = svc.color,
                            onClick = trackedAction(svc),
                            stat = stat?.first,
                            statDetail = stat?.second,
                        )
                    }
                    HomeSceneCard(
                        title = category.title,
                        subtitle = category.subtitle,
                        icon = categoryIcon(category),
                        accent = com.xjtu.toolbox.ui.theme.legacyColor(categoryAccentKey(category)),
                        rows = rows,
                    )
                }
            }
        }
    }

    val isWide = com.xjtu.toolbox.ui.isWideLayout()
    if (isWide) {
        // 宽屏：左栏固定 380dp（状态区本来就不该被拉宽），右栏分类卡两列。
        // 两栏各自滚动；大标题的折叠由挂在外层 Row 上的 nestedScroll 接住，
        // 哪一栏在滚都算数。
        Row(
            Modifier
                .fillMaxSize()
                .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
        ) {
            Column(
                Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
            ) {
                headerSection()
                Spacer(Modifier.height(24.dp))
                quickActionsSection()
                Spacer(Modifier.height(24.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                categorySection()
                Spacer(Modifier.height(24.dp))
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier)
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
        ) {
            headerSection()
            Spacer(Modifier.height(24.dp))
            quickActionsSection()
            categorySection()
            if (navBarStyle == "floating") Spacer(Modifier.height(96.dp))
        }
    }
}

/**
 * 分类卡的排布。
 *
 * 窄屏：一列竖排、卡间留 [spacing]，与改造前的 `forEachIndexed { card; Spacer }` 逐行等价
 * （这个函数本身不建布局节点，卡片仍然是外层 Column 的直接孩子）。
 * 宽屏：两列，偶数下标进左列、奇数进右列——按顺序填一列到底会让左边长得离谱。
 */
@Composable
private fun CategoryCards(
    count: Int,
    spacing: androidx.compose.ui.unit.Dp,
    card: @Composable (Int) -> Unit,
) {
    if (!com.xjtu.toolbox.ui.isWideLayout()) {
        for (i in 0 until count) {
            card(i)
            if (i != count - 1) Spacer(Modifier.height(spacing))
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        for (col in 0..1) {
            Column(Modifier.weight(1f)) {
                var first = true
                var i = col
                while (i < count) {
                    if (!first) Spacer(Modifier.height(spacing))
                    card(i)
                    first = false
                    i += 2
                }
            }
        }
    }
}

// ══════════════════════════════════════════
//  通用组件
// ══════════════════════════════════════════

/** HomeTab 日程提醒卡片用的轻量数据类 */
private data class ScheduleReminderCourseInfo(
    val name: String,
    val location: String,
    val startSection: Int,
    val endSection: Int,
    val startMinuteOfDay: Int = -1,
    val endMinuteOfDay: Int = -1
)

private data class ScheduleReminderInfo(
    val name: String,
    val location: String,
    val startAt: java.time.LocalDateTime,
    val endAt: java.time.LocalDateTime?
)

private fun ScheduleReminderCourseInfo.resolveStartMinute(isSummer: Boolean): Int? {
    if (startMinuteOfDay in 0 until (24 * 60)) return startMinuteOfDay
    val startTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(startSection, isSummer)?.start ?: return null
    return startTime.hour * 60 + startTime.minute
}

private fun ScheduleReminderCourseInfo.resolveEndMinute(isSummer: Boolean): Int? {
    if (endMinuteOfDay in 1..(24 * 60)) return endMinuteOfDay
    val endTime = com.xjtu.toolbox.util.XjtuTime.getClassTime(endSection, isSummer)?.end ?: return null
    return endTime.hour * 60 + endTime.minute
}

private fun formatMinuteClock(minuteOfDay: Int): String {
    return when {
        minuteOfDay >= 24 * 60 -> "24:00"
        minuteOfDay < 0 -> "00:00"
        else -> "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }
}

private fun formatScheduleReminderEta(minutesUntil: Long): String {
    if (minutesUntil <= 0) return "即将开始"
    if (minutesUntil < 60) return "${minutesUntil}分钟后"

    val hours = minutesUntil / 60
    val remainMinutes = minutesUntil % 60
    if (hours < 24) {
        return if (remainMinutes == 0L) "${hours}小时后" else "${hours}小时${remainMinutes}分钟后"
    }

    val days = hours / 24
    val remainHours = hours % 24
    return if (remainHours == 0L) "${days}天后" else "${days}天${remainHours}小时后"
}

private fun formatScheduleReminderDateLabel(targetDate: java.time.LocalDate, today: java.time.LocalDate): String {
    val delta = java.time.temporal.ChronoUnit.DAYS.between(today, targetDate).toInt()
    return when (delta) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        in 3..6 -> when (targetDate.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
        else -> "${targetDate.monthValue}月${targetDate.dayOfMonth}日"
    }
}

/**
 * 两个主题共用的服务条目数据。
 *
 * [stat] / [statDetail] 是卡片主题的核心：**有实时状态可展示的服务才配大卡**。
 * 图标主题忽略这两个字段——它的定位是等分入口宫格。
 */
private data class HomeServiceRow(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val color: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit,
    /** 主数据，大字号展示，如「¥42.50」「高等数学」。null 表示没有可展示的状态。 */
    val stat: String? = null,
    /** 辅助说明，小字，如「08:00 · 主楼A-203 · 还有2小时」。 */
    val statDetail: String? = null,
) {
    val hasStat: Boolean get() = !stat.isNullOrBlank()
}

/**
 * 卡片主题的分类卡。
 *
 * 设计取向是**克制**。避免大面积光晕与 2 列超大瓷砖：光晕盖过标题、瓷砖占半屏宽会
 * 把图标撑大、留白空洞，一屏装不下几个功能。具体做法：
 * - 去掉光晕，分类主色只留标题左侧一根 3dp 竖条，够做区分又不喧宾夺主；
 * - 3 列紧凑瓷砖，图标 38dp，一屏能完整看到一个分类；
 * - 瓷砖底色用中性的 surfaceVariant 而不是分类主色染色，避免整卡花花绿绿；
 * - 圆角、内边距整体收一档（28→24、18→14）。
 *
 * 与图标主题的区别仍然立得住：图标主题是**无卡片的 4 列通栏宫格**，这里是**成卡分组
 * 的 3 列**，卡片边界 + 分类标题 + 副标题承担信息层级。
 */
@Composable
private fun HomeCategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    rows: List<HomeServiceRow>,
) {
    val isDark = isSystemInDarkTheme()
    // 分类主色的对角渐变，浓度压得很低——它是"氛围"，不是"色块"。
    // 深色模式下同样的 alpha 会显脏，所以两套值。
    val tint = if (isDark) 0.16f else 0.10f
    val fill = Brush.linearGradient(
        listOf(
            accent.copy(alpha = tint),
            accent.copy(alpha = tint * 0.25f),
            AppCardColor
        )
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // squircleBackground 只接受纯色，渐变要走 clip + background(brush)
            .squircleClip(CARD_RADIUS)
            .background(fill)
            // 细边框是精致感的关键。之前整卡没有任何描边，边界全靠底色差，
            // 在浅色主题下几乎看不出卡在哪儿，就显得"糊成一片"。
            .squircleBorder(1.dp, accent.copy(alpha = if (isDark) 0.28f else 0.20f), CARD_RADIUS)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .squircleBackground(accent.copy(alpha = if (isDark) 0.28f else 0.16f), 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        rows.chunked(4).forEach { group ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                group.forEach { row ->
                    HomeServiceTile(row, Modifier.weight(1f))
                }
                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 分类卡里的瓷砖：图标 + 名称，**无独立背景块**。
 *
 * 关键取舍：不给瓷砖单独的底色。之前每个瓷砖都是一个小圆角色块，整体是"卡片里再套
 * 一堆小卡片"，这种嵌套容器正是廉价感的来源。现在瓷砖直接落在分类卡的渐变底上，
 * 卡片本身承担唯一的容器角色，层级干净。按压反馈由 SinkFeedback 提供，不需要底色来提示可点。
 */
@Composable
private fun HomeServiceTile(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
) {
    val origin = com.xjtu.toolbox.nav.rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = modifier
            .expandOriginSource(origin)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(row.key, 14.dp, density)
                    row.onClick()
                }
            )
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ExpressiveIcon(icon = row.icon, color = row.color, size = 40.dp, iconSize = 21.dp)
        Spacer(Modifier.height(7.dp))
        Text(
            row.title,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 分类卡圆角。超椭圆下这个值可以给得比普通圆角更大而不显得"胀"。 */
private val CARD_RADIUS = 26.dp

// ══════════════════════════════════════════
//  卡片主题：场景大卡
// ══════════════════════════════════════════

/**
 * 场景大卡：一张卡装下一整类，卡内是**双列数据排版**。
 *
 * 与图标主题的分工：
 * - 图标主题 = 彩色宫格 + 主色渐变卡，回答「有哪些功能」；
 * - 卡片主题 = **中性卡 + 数据排版**，回答「这一块现在怎么样」。
 *
 * 视觉上刻意不用渐变。渐变已经是图标主题的语言，两边都铺一层同样的主色渐变，
 * 换来的只是"看起来一样"。这里改成：卡片本身中性，主色只出现在**左缘一条书脊**
 * 和**数值文字**上——颜色少而准，信息才立得住。
 *
 * 条目既不是方块也不是胶囊，而是"名称在上、数值在下"的数据格：没有任何背景块，
 * 卡片是唯一容器。有数据的格子数值用主色加粗，没数据的只剩一行淡名称，
 * 一眼就能扫出哪里有事。
 */
@Composable
private fun HomeSceneCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    rows: List<HomeServiceRow>,
) {
    if (rows.isEmpty()) return
    // 有状态的排前面：卡片打开就先看见"有事"的部分
    val ordered = rows.sortedByDescending { it.hasStat }
    val liveCount = rows.count { it.hasStat }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .height(IntrinsicSize.Min)
    ) {
        // 书脊：整卡左缘一条主色，替代整片渐变做分类识别
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(Modifier.weight(1f).padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (liveCount > 0) "$liveCount 条更新" else subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (liveCount > 0) accent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontWeight = if (liveCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            ordered.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { ServiceStatCell(it, accent, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 数据格：名称（弱）在上，数值（主色加粗）在下，无背景块。
 * 没有数据时只留名称一行并压低不透明度，让"有事的"自然浮出来。
 */
@Composable
private fun ServiceStatCell(
    row: HomeServiceRow,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val origin = com.xjtu.toolbox.nav.rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = modifier
            .expandOriginSource(origin)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(row.key, 10.dp, density)
                    row.onClick()
                }
            )
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                row.icon,
                contentDescription = null,
                tint = if (row.hasStat) row.color else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                row.title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.hasStat) {
            Spacer(Modifier.height(2.dp))
            Text(
                row.stat.orEmpty(),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            row.statDetail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 主页小节标题：主色强调条 + 粗体标题，全页统一。 */
@Composable
internal fun HomeSectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .width(4.dp)
                .height(15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeQuickAction(
    icon: ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 点它打开的路由；用来让功能页从这一格放大出来（PR V）。 */
    originKey: String? = null,
) {
    val origin = com.xjtu.toolbox.nav.rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .then(if (originKey != null) Modifier.expandOriginSource(origin) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    originKey?.let { origin.arm(it, 18.dp, density) }
                    onClick()
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // 唯一的调用方是首页「常用功能」，气泡搬到底栏后这里不再需要向外报告图标坐标，
        // 那个 onIconGloballyPositioned 参数已随之删掉。
        ExpressiveIcon(icon = icon, color = color)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
    }
}

/** 更多服务宫格项：纯图标 + 标签，无背景无副标题。 */
@Composable
private fun HomeGridItem(
    icon: ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .heightIn(min = 86.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        ExpressiveIcon(icon = icon, color = color, size = 46.dp, iconSize = 23.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeServiceTile(
    icon: ImageVector, title: String, subtitle: String,
    iconColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExpressiveIcon(
            icon = icon,
            color = iconColor,
            size = 42.dp,
            iconSize = 22.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun ServiceCard(icon: ImageVector, title: String, description: String, loggedIn: Boolean, iconColor: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.primary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 24.dp,
        pressFeedbackType = PressFeedbackType.Sink
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                Text(description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loggedIn) {
                Surface(shape = RoundedCornerShape(8.dp), color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Text("已登录", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                }
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
