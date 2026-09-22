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
import androidx.compose.ui.draw.drawBehind
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
import com.xjtu.toolbox.ui.components.appCardShadow
import com.xjtu.toolbox.ui.components.enterOnce
import com.xjtu.toolbox.ui.components.pressScale
import com.xjtu.toolbox.util.CredentialStore
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.home.ServiceCategory

// ══════════════════════════════════════════
//  Tab 1 — 首页
// ══════════════════════════════════════════

/** Hero 卡顶部那一行状态：网络环境 + 子系统就绪数，点开看明细。 */
private data class HeroStatus(val label: String, val detail: String, val color: Color)

/** Hero 卡底部的常用入口。 */
private data class HeroQuickAction(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val color: Color,
    val onClick: () -> Unit,
)

/**
 * 首页的「今日」卡：一张卡装下此刻最该看的全部东西。
 *
 * 以前从上到下是 Hero、一排网络/子系统小胶囊、「常用功能」标题 + 一条宫格，三块各管各的，
 * Hero 里的余额和下节课又在下面的分类卡里再出现一遍。现在收成一张：
 * 问候与状态 → 下一项安排（整条可点）→ 余额 / 今日消费 / 考试三格速览 → 常用入口。
 * 下面的分类卡只放 Hero 没有的信息。
 */
@Composable
private fun HomeHero(
    modifier: Modifier = Modifier,
    greetingName: String,
    dateLabel: String,
    weekNumber: Int,
    isLoggedIn: Boolean,
    isFocusLoaded: Boolean,
    reminder: ScheduleReminderInfo?,
    balance: Float,
    todaySpend: Float,
    exam: com.xjtu.toolbox.home.HomeStat?,
    status: HeroStatus?,
    quickActions: List<HeroQuickAction>,
    solidQuickIcons: Boolean,
    onOpenCourses: () -> Unit,
    onOpenCard: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    val greeting = com.xjtu.toolbox.util.Greeting.of()
    val headline = if (greetingName.isBlank()) greeting else "$greeting，$greetingName"
    val meta = buildString {
        append(dateLabel)
        if (weekNumber in 1..25) append(" · 第${weekNumber}周")
    }
    val primary = MiuixTheme.colorScheme.primary
    val artSize = 104.dp

    Box(
        modifier
            .fillMaxWidth()
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS), strong = true)
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor),
    ) {
        Box(Modifier.matchParentSize()) {
            // 底色是缓慢流动的 Mesh 渐变，顶点颜色由主题色按不同浓度混进卡片底色，Monet 取色下也跟着走。
            val cardBase = AppCardColor
            val heroMesh = remember(primary, cardBase) {
                HeroMeshWeights.map { row -> row.map { t -> androidx.compose.ui.graphics.lerp(cardBase, primary, t) } }
            }
            com.xjtu.toolbox.ui.components.MeshBackground(
                modifier = Modifier.matchParentSize(),
                lightVertexColors = heroMesh,
                darkVertexColors = heroMesh,
                // 卡片在玻璃顶栏/底栏的取样范围里，一直流动会让静止的首页也持续重模糊，流动一小段就停。
                runForMillis = 6_000L,
            )
        }
        Image(
            painter = painterResource(R.drawable.home_campus_hero),
            contentDescription = "兴庆校区主楼",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .size(artSize),
            contentScale = ContentScale.Fit,
        )
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Column(Modifier.padding(start = 6.dp, top = 8.dp, end = artSize)) {
                Text(
                    meta,
                    style = MiuixTheme.textStyles.footnote1,
                    color = primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    headline,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    HeroStatusLine(status, onOpenStatus)
                }
            }
            Spacer(Modifier.height(if (status != null) 18.dp else 30.dp))
            HeroNextUp(
                isLoggedIn = isLoggedIn,
                isFocusLoaded = isFocusLoaded,
                reminder = reminder,
                onClick = if (isLoggedIn) onOpenCourses else onOpenProfile,
            )
            if (isLoggedIn) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val lowBalance = balance in 0f..30f
                    HeroGlance(
                        icon = Icons.Default.CreditCard,
                        label = if (lowBalance) "余额不多了" else "校园卡余额",
                        value = if (balance >= 0f) "¥${"%.2f".format(balance)}" else "—",
                        number = balance.takeIf { it >= 0f }?.toDouble(),
                        valueColor = if (lowBalance) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                        onClick = onOpenCard,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    HeroGlance(
                        icon = Icons.Default.Restaurant,
                        label = "今日消费",
                        value = if (todaySpend >= 0f) "¥${"%.2f".format(todaySpend)}" else "—",
                        number = todaySpend.takeIf { it >= 0f }?.toDouble(),
                        onClick = onOpenCard,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    if (exam != null) {
                        HeroGlance(
                            icon = Icons.AutoMirrored.Filled.EventNote,
                            label = exam.detail?.substringBefore(" · ")?.ifBlank { null } ?: "下一场考试",
                            value = exam.value,
                            valueColor = primary,
                            onClick = onOpenCourses,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            if (quickActions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    quickActions.forEach { a ->
                        HomeQuickAction(
                            a.icon,
                            a.title,
                            a.color,
                            onClick = a.onClick,
                            modifier = Modifier.weight(1f),
                            originKey = a.key,
                            iconSize = 44.dp,
                            solidIcon = solidQuickIcons,
                        )
                    }
                }
            }
        }
    }
}

/** Hero 里半透明的内嵌面板：叠在 Mesh 上像一层磨砂，比再套一张实心卡轻。 */
@Composable
private fun Modifier.heroInset(radius: androidx.compose.ui.unit.Dp = 18.dp): Modifier {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    return this
        .squircleClip(radius)
        .background(AppCardColor.copy(alpha = if (dark) 0.55f else 0.72f))
        .squircleBorder(1.dp, Color.White.copy(alpha = if (dark) 0.06f else 0.6f), radius)
}

@Composable
private fun HeroStatusLine(status: HeroStatus, onClick: () -> Unit) {
    Row(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = SinkFeedback(),
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(status.color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${status.label} · ${status.detail}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "查看子系统连接状态",
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 下一项安排：左边钟点，右边名称与倒计时，整条点进日程。 */
@Composable
private fun HeroNextUp(
    isLoggedIn: Boolean,
    isFocusLoaded: Boolean,
    reminder: ScheduleReminderInfo?,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .heroInset()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title: String
        var detail: androidx.compose.ui.text.AnnotatedString? = null
        if (reminder != null) {
            val now = java.time.LocalDateTime.now()
            val minutesUntil = java.time.Duration.between(now, reminder.startAt).toMinutes().coerceAtLeast(0)
            val dayLabel = formatScheduleReminderDateLabel(reminder.startAt.toLocalDate(), now.toLocalDate())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatMinuteClock(reminder.startAt.hour * 60 + reminder.startAt.minute),
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
                Text(
                    reminder.endAt?.let { "至 " + formatMinuteClock(it.hour * 60 + it.minute) } ?: dayLabel,
                    style = MiuixTheme.textStyles.footnote2,
                    color = muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(30.dp)
                    .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f))
            )
            Spacer(Modifier.width(12.dp))
            title = reminder.name
            detail = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = primary, fontWeight = FontWeight.Medium))
                append(formatScheduleReminderEta(minutesUntil))
                pop()
                if (dayLabel != "今天" && reminder.endAt != null) append(" · $dayLabel")
                if (reminder.location.isNotBlank()) append(" · ${reminder.location}")
            }
        } else {
            val (icon, t, d) = when {
                !isLoggedIn -> Triple(Icons.AutoMirrored.Filled.Login, "登录后查看课表和余额", "课表、校园卡会显示在这里")
                !isFocusLoaded -> Triple(Icons.Default.CalendarMonth, "正在读取今日安排…", null)
                else -> Triple(Icons.Default.EventAvailable, "接下来两周都没课", "空出来的日子怎么过，可以问问屁岱")
            }
            ExpressiveIcon(icon = icon, color = primary, size = 38.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            title = t
            detail = d?.let { androidx.compose.ui.text.AnnotatedString(it) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
    }
}

/** 速览格：小标签在上、数值在下。 */
@Composable
private fun HeroGlance(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
    /** 金额类传数值：第一次从 0 滚上来，之后随缓存刷新从旧值滚到新值。 */
    number: Double? = null,
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        modifier
            .heroInset(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MiuixTheme.textStyles.footnote2, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        if (number != null) {
            com.xjtu.toolbox.ui.components.RollingNumberText(
                value = number,
                format = { "¥%.2f".format(it) },
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
        } else {
            Text(
                value,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 子系统连接明细。原来挂在首页那排小胶囊上，现在由 Hero 的状态行打开。 */
@Composable
private fun SubsystemStatusSheet(loginState: AppLoginState, show: MutableState<Boolean>) {
    if (!show.value) return
    BackHandler { show.value = false }
    OverlayBottomSheet(
        show = show.value,
        title = "子系统连接状态",
        onDismissRequest = { show.value = false }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())   // 子系统较多，弹窗内容需要可滚动。
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            LoginType.entries.forEach { t ->
                val ready = loginState.sessionManager?.getSiteOrNull(t.siteKey())?.hasLogin == true
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = if (ready) STATUS_GREEN else MiuixTheme.colorScheme.onSurfaceVariantSummary
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

private val STATUS_GREEN = Color(0xFF2E7D32)
private val STATUS_BLUE = Color(0xFF1565C0)

@Composable
internal fun HomeTab(
    loginState: AppLoginState,
    isRestoring: Boolean = false,
    onNavigate: (String) -> Unit,
    onNavigateWithLogin: (String, LoginType) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCourses: () -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    /** 悬浮底栏的总占位高度（= MainScreen 的 floatingBarReserve）：底栏浮在内容之上，页面末尾得自己留出来。 */
    extraBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    homeTheme: String = CredentialStore.THEME_CARD,
    showQuickActions: Boolean = true,
    bulletins: List<Bulletin> = emptyList(),
    onBulletinTap: (Bulletin) -> Unit = {},
    onBulletinDismiss: (Bulletin) -> Unit = {},
    /** 玻璃顶栏的高度：内容铺到顶栏下面，这段留白放进滚动内容里。经典风格为 0。 */
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
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
                // 本学期统一认 readCurrentTerm：学期列表第一个可能是教务已挂出的下学期
                val termCode = com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTerm(dataCache, gson)
                    ?: return@withContext Pair(null, 0)
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
                    // 放假停的是教务的课，自建日程照常提醒
                    val isHoliday = holidayDates.containsKey(targetDate)

                    val targetWeek = com.xjtu.toolbox.schedule.TermWeeks.weekOf(startDate, targetDate)
                    if (targetWeek <= 0) continue
                    val daySchedules = allSchedules
                        .filter { it.dayOfWeek == targetDate.dayOfWeek.value && it.isInWeek(targetWeek) }
                        .filter { !isHoliday || it.isUserCreated }
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

    // 各功能的当前状态，两个主题共用：Hero 的考试倒计时、卡片主题的数据格都读它。
    // 全部读**本地缓存**，首页不发任何网络请求（详见 HomeStats）。刷新在 MainScreen 层跑，
    // 这里跟着 statsVersion 走：每跑完一轮就自增，拿到的永远是刚落盘的那份。
    val statsCtx = LocalContext.current
    var homeStats by remember { mutableStateOf<Map<String, com.xjtu.toolbox.home.HomeStat>>(emptyMap()) }
    LaunchedEffect(
        loginState.accountId,
        loginState.campusCardCacheVersion,
        com.xjtu.toolbox.home.HomeSignals.statsVersion,
    ) {
        val term = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dc = com.xjtu.toolbox.util.DataCache(statsCtx)
                com.xjtu.toolbox.schedule.ScheduleCache.readCurrentTerm(dc, com.google.gson.Gson())
            }.getOrNull()
        }
        homeStats = com.xjtu.toolbox.home.HomeStats.collect(statsCtx, term)
        // 校园卡由 refresher 写进 CampusCardCache 的 prefs，不经过 homeStats，单独重读一次。
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedTodaySpend = cardPrefs.getFloat("card_today_spend_cache", -1f)
    }

    // 常用入口：按使用频率取 4 个，放进 Hero 卡底部。它是**额外**的入口，分类里照常保留。
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
    )
    val quickKeys = if (showQuickActions) {
        remember(quickCandidateKeys) {
            com.xjtu.toolbox.util.ServiceUsageTracker.topKeys(
                ctx,
                quickCandidateKeys,
                n = 4,
                fallback = listOf(Routes.CAMPUS_CARD, Routes.EMPTY_ROOM, Routes.NOTIFICATION) + quickCandidateKeys
            ).filter { it in quickCandidateKeys }.distinct().take(4)
        }
    } else emptyList()
    val quickActions = servicesByKeys(quickKeys).map { svc ->
        val colored = if (homeTheme == CredentialStore.THEME_ICON) coloredForIconTheme(svc) else svc
        HeroQuickAction(svc.key, svc.icon, svc.title, colored.color, trackedAction(svc))
    }

    val showStatusSheet = remember { mutableStateOf(false) }
    SubsystemStatusSheet(loginState, showStatusSheet)
    val heroStatus: HeroStatus? = if (loginState.isLoggedIn) {
        val (netLabel, netColor) = when (loginState.isOnCampus) {
            true -> "校园网" to STATUS_GREEN
            false -> "校外 · WebVPN" to STATUS_BLUE
            null -> "网络检测中" to MiuixTheme.colorScheme.onSurfaceVariantSummary
        }
        val types = LoginType.entries
        val ok = types.count { loginState.sessionManager?.getSiteOrNull(it.siteKey())?.hasLogin == true }
        HeroStatus(
            label = netLabel,
            detail = when {
                isRestoring -> "正在连接…"
                ok > 0 -> "$ok/${types.size} 子系统就绪"
                else -> "子系统未连接"
            },
            color = netColor,
        )
    } else null

    val headerSection: @Composable () -> Unit = {
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
                Spacer(Modifier.height(SECTION_GAP))
            }
            HomeHero(
                modifier = Modifier.enterOnce(0),
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
                exam = homeStats[com.xjtu.toolbox.home.EXAM_KEY],
                status = heroStatus,
                quickActions = quickActions,
                solidQuickIcons = homeTheme == CredentialStore.THEME_ICON,
                onOpenCourses = onNavigateToCourses,
                onOpenCard = { onNavigateWithLogin(Routes.CAMPUS_CARD, LoginType.CAMPUS_CARD) },
                onOpenProfile = onNavigateToProfile,
                onOpenStatus = { showStatusSheet.value = true },
            )
        }
    }

    val categorySection: @Composable () -> Unit = {
        val categories = ServiceCategory.entries.mapNotNull { category ->
            val items = allServices.filter { it.category == category }
            if (items.isEmpty()) null else category to items
        }
        when (homeTheme) {
            CredentialStore.THEME_ICON -> {
                // 彩虹主题：实心渐变的 App 式图标 + 4 列宫格，回答「有哪些功能」。
                CategoryCards(count = categories.size, spacing = SECTION_GAP) { index ->
                    val (category, items) = categories[index]
                    HomeCategoryCard(
                        title = category.title,
                        subtitle = category.subtitle,
                        accent = com.xjtu.toolbox.ui.theme.legacyColor(categoryAccentKey(category)),
                        rows = items.map { coloredForIconTheme(it) }.map { svc ->
                            HomeServiceRow(svc.key, svc.icon, svc.title, svc.color, trackedAction(svc))
                        },
                    )
                }
            }
            else -> {
                // 卡片主题：有状态的功能出数据格，其余收成一排紧凑入口，回答「这一块现在怎么样」。
                // Hero 已经给了的（下节课、余额、教学周）这里不再重复。
                val statOf: (String) -> Pair<String, String?>? = { key ->
                    when (key) {
                        Routes.CAMPUS_CARD, Routes.SCHOOL_CALENDAR -> null
                        // 快速考勤流水不再单列在首页，今天刷过卡就借新版考勤这一格露出来
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
                CategoryCards(count = categories.size, spacing = SECTION_GAP) { index ->
                    val (category, items) = categories[index]
                    HomeSceneCard(
                        title = category.title,
                        subtitle = category.subtitle,
                        icon = categoryIcon(category),
                        accent = com.xjtu.toolbox.ui.theme.legacyColor(categoryAccentKey(category)),
                        rows = items.map { svc ->
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
                        },
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
                Spacer(Modifier.height(contentTopPadding))
                headerSection()
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
                Spacer(Modifier.height(contentTopPadding + 8.dp))
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
            Spacer(Modifier.height(contentTopPadding))
            headerSection()
            Spacer(Modifier.height(SECTION_GAP))
            categorySection()
            Spacer(Modifier.height(SECTION_GAP + extraBottomPadding))
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
    // 每张分类卡接在 Hero 后面依次登场（Hero 是第 0 拍）
    if (!com.xjtu.toolbox.ui.isWideLayout()) {
        for (i in 0 until count) {
            Box(Modifier.enterOnce(i + 1)) { card(i) }
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
                    Box(Modifier.enterOnce(i + 1)) { card(i) }
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
 * 彩虹主题的分类卡。
 *
 * 图标做成实心渐变 + 白色字形的 App 图标，而不是浅色底上的彩色字形——后者一排排摆出来
 * 像网页上的功能列表；前者一眼就是「手机上的一屏应用」。卡片本身只在左上角晕一团分类色，
 * 没有描边、没有色条，让彩色图标自己说话。
 */
@Composable
private fun HomeCategoryCard(
    title: String,
    subtitle: String,
    accent: Color,
    rows: List<HomeServiceRow>,
) {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    val glow = accent.copy(alpha = if (dark) 0.16f else 0.10f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS))
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(glow, Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(0f, 0f),
                        radius = size.maxDimension * 0.75f,
                    )
                )
            }
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        rows.chunked(4).forEach { group ->
            Row(Modifier.fillMaxWidth()) {
                group.forEach { row ->
                    HomeServiceTile(row, Modifier.weight(1f), solidIcon = true)
                }
                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * App 式图标：分类色的对角渐变铺满超椭圆，上半截一层柔和高光，字形用白色。
 * 不加投影：一屏二十几个图标，每个一层 dropShadow，滚动时帧率会掉。
 */
@Composable
internal fun GradientAppIcon(
    icon: ImageVector,
    color: Color,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val radius = size * 0.3f
    val top = androidx.compose.ui.graphics.lerp(color, Color.White, 0.22f)
    val bottom = androidx.compose.ui.graphics.lerp(color, Color.Black, 0.10f)
    Box(
        Modifier
            .size(size)
            .squircleClip(radius)
            .background(Brush.linearGradient(listOf(top, color, bottom)))
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                        endY = this.size.height * 0.55f,
                    )
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** 宫格里的一个入口：图标 + 名称，没有独立底色，卡片是唯一容器。 */
@Composable
private fun HomeServiceTile(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
    solidIcon: Boolean = false,
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
        if (solidIcon) {
            GradientAppIcon(row.icon, row.color)
        } else {
            ExpressiveIcon(icon = row.icon, color = row.color, size = 42.dp, iconSize = 21.dp)
        }
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

/** 首页各卡之间统一的间距：Hero、公告、分类卡之间都是它，页面读起来是一整套而不是几截。 */
private val SECTION_GAP = 14.dp

/** 分类卡圆角。超椭圆下这个值可以给得比普通圆角更大而不显得"胀"。 */
private val CARD_RADIUS = 26.dp

/**
 * 首页 Hero 卡 Mesh 渐变的 3x3 顶点：每格是「主题色混进卡片底色的比例」。
 * 右上角最浓，往下渐淡，底部几乎就是卡片底色，给下面的面板留出干净的底。
 */
private val HeroMeshWeights = listOf(
    listOf(0.15f, 0.08f, 0.22f),
    listOf(0.07f, 0.11f, 0.08f),
    listOf(0.03f, 0.02f, 0.05f),
)

// ══════════════════════════════════════════
//  卡片主题：场景卡
// ══════════════════════════════════════════

/**
 * 卡片主题的分类卡：上面是有实时状态的功能（数据格），下面是其余功能的紧凑入口。
 *
 * 以前是左缘一条色条 + 双列「名称 / 数值」纯文字，没有任何面，像网页表格；
 * 没数据的功能只剩一行灰字，点都不好点。现在：
 * - 有状态的做成淡染分类色的圆角数据格，数值加粗，一眼扫出「哪里有事」；
 * - 没状态的收成一排图标入口，和彩虹主题同一套手感；
 * - 标题用浅色底的图标徽记做识别，去掉色条。
 */
@Composable
private fun HomeSceneCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    rows: List<HomeServiceRow>,
) {
    if (rows.isEmpty()) return
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    val live = rows.filter { it.hasStat }
    val rest = rows.filterNot { it.hasStat }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .appCardShadow(shape = RoundedCornerShape(CARD_RADIUS))
            .squircleClip(CARD_RADIUS)
            .background(AppCardColor)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (rest.isEmpty()) 16.dp else 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .squircleBackground(accent.copy(alpha = if (dark) 0.24f else 0.12f), 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (live.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            live.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { ServiceStatCell(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(if (live.isNotEmpty()) 8.dp else 10.dp))
            rest.chunked(4).forEach { group ->
                Row(Modifier.fillMaxWidth()) {
                    group.forEach { HomeServiceTile(it, Modifier.weight(1f)) }
                    repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 数据格：功能色淡染的面，名称在上，数值加粗，说明一行。 */
@Composable
private fun ServiceStatCell(
    row: HomeServiceRow,
    modifier: Modifier = Modifier,
) {
    val dark = com.xjtu.toolbox.ui.theme.LocalIsDarkTheme.current
    val origin = com.xjtu.toolbox.nav.rememberExpandOriginSource()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = modifier
            .expandOriginSource(origin)
            .squircleClip(18.dp)
            .background(row.color.copy(alpha = if (dark) 0.14f else 0.07f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = {
                    origin.arm(row.key, 18.dp, density)
                    row.onClick()
                }
            )
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(row.icon, contentDescription = null, tint = row.color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                row.title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            row.stat.orEmpty(),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        row.statDetail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(1.dp))
            Text(
                it,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    iconSize: androidx.compose.ui.unit.Dp = 54.dp,
    /** 彩虹主题下用实心渐变图标，和分类卡里的图标一致。 */
    solidIcon: Boolean = false,
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
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        // 唯一的调用方是首页「常用功能」，气泡搬到底栏后这里不再需要向外报告图标坐标，
        // 那个 onIconGloballyPositioned 参数已随之删掉。
        if (solidIcon) GradientAppIcon(icon, color, size = iconSize, iconSize = iconSize * 0.5f)
        else ExpressiveIcon(icon = icon, color = color, size = iconSize, iconSize = iconSize * 0.5f)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
    }
}
