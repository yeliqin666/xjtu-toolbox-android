package com.xjtu.toolbox.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.R
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.card.CampusCardCache
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.util.todayInSystemZone
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.HolidayApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.TermWeeks
import com.xjtu.toolbox.util.toJavaTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** 首页在 `:app` 这一端的两半：宿主面（[host]）+ 它自己的那一层弹窗（[showStatusSheet]）。 */
@Stable
internal class AppHome(
    val host: HomeHost,
    /** 「子系统连接状态」弹窗的开关，由调用方（[HomeTab]）在组合里读并画那个弹窗。 */
    val showStatusSheet: MutableState<Boolean>,
)

/** 首页的宿主面（`:app` 这一端）—— 把「首页要的数据从哪来」收成一个对象。 */
@Composable
internal fun rememberAppHome(
    loginState: AppLoginState,
    isRestoring: Boolean = false,
    theme: String,
    showQuickActions: Boolean = true,
    bulletinItems: List<Bulletin> = emptyList(),
    onNavigate: (AppRoute) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    onNavigateToCourses: () -> Unit = {},
    onBulletinTap: (Bulletin) -> Unit = {},
    onBulletinDismiss: (Bulletin) -> Unit = {},
): AppHome {
    val context = LocalContext.current
    // ── 仪表盘数据：下一节日程 + 校园卡余额缓存（供 Hero 重点信息区使用）──
    var scheduleReminder by remember { mutableStateOf<HomeNextUp?>(null) }
    var isScheduleReminderLoaded by remember { mutableStateOf(false) }
    // true 表示「下一项安排」算不出来是因为课表数据不可用（学期代码 / 开学日期缺失、
    // 读缓存抛异常），而不是真的未来两周没课——两种情况在 Hero 卡上要分开说话。
    var scheduleDataMissing by remember { mutableStateOf(false) }
    var currentWeekNumber by remember { mutableIntStateOf(0) }
    val cardPrefs = remember(AccountContext.activeAccountId) { CampusCardCache.cardPrefs(context) }
    var cachedBalance by remember { mutableStateOf(cardPrefs.getFloat("card_balance_cache", -1f)) }
    var cachedDailyRate by remember { mutableStateOf(cardPrefs.getFloat("card_daily_rate_cache", -1f)) }

    LaunchedEffect(loginState.campusCardCacheVersion) {
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedDailyRate = cardPrefs.getFloat("card_daily_rate_cache", -1f)
    }

    // key 上 HomeSignals.scheduleVersion：日程页同步/下拉刷新落了新课后会 bump 它，
    // 这里跟着重读课表缓存，Hero 的「下一项安排」不用退出重登才更新。
    LaunchedEffect(loginState.accountId, HomeSignals.scheduleVersion) {
        if (loginState.accountId.isEmpty()) return@LaunchedEffect
        // 账号切换：先清旧账号的提醒与校园卡缓存内存态，再从新账号命名空间重读
        isScheduleReminderLoaded = false
        scheduleReminder = null
        scheduleDataMissing = false
        currentWeekNumber = 0
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedDailyRate = cardPrefs.getFloat("card_daily_rate_cache", -1f)
        // 返回 Triple(下一项安排, 当前周次, 课表数据是否不可用)。第三位 true 时
        // Hero 卡显示「课表还没同步」而不是「接下来两周都没课」，见 scheduleDataMissing。
        val loadedFocus = withContext(Dispatchers.IO) {
            try {
                val dataCache = DataCache(context)
                // 本学期统一认 readCurrentTerm：学期列表第一个可能是教务已挂出的下学期
                val schedule = ScheduleCache.readCurrentTermSchedule(dataCache)
                    ?: return@withContext Triple(null, 0, true)
                val termCode = schedule.code
                val apiCourses = schedule.courses
                val customCourses = try {
                    com.xjtu.toolbox.data.AppDatabase.getInstance(context)
                        .customCourseDao().getByTerm(AccountContext.activeAccountId ?: "", termCode)
                        .map { it.toCourseItem() }
                } catch (_: Exception) { emptyList() }
                val allSchedules = apiCourses + customCourses
                val startDate = schedule.start
                val today = com.xjtu.toolbox.util.todayOnCampus()
                val weekNumber = HomeHero.weekNumber(startDate, today)
                if (startDate == null) {
                    return@withContext Triple(null, weekNumber, true)
                }
                // 只读缓存：首页不该为了节假日去发网络请求，日程页会负责拉取
                val holidayDates = HolidayApi.peekCached(dataCache).keys
                val nextUp = HomeHero.find(
                    courses = allSchedules,
                    startOfTerm = startDate,
                    holidayDates = holidayDates,
                    route = AppRoute.Schedule,
                )
                Triple(nextUp, weekNumber, false)
            } catch (_: Exception) {
                Triple(null, 0, true)
            }
        }
        scheduleReminder = loadedFocus.first
        currentWeekNumber = loadedFocus.second
        scheduleDataMissing = loadedFocus.third
        isScheduleReminderLoaded = true
        // 提醒评估在 MainScreen 层跑，够不到这里的状态，用共享信号带过去。
        val focus = loadedFocus.first
        HomeSignals.scheduleReminder = focus?.startAt?.let { HomeSignals.ScheduleFocus(focus.title, it.toJavaTime()) }
    }

    var homeStats by remember { mutableStateOf<Map<String, HomeStat>>(emptyMap()) }
    LaunchedEffect(
        loginState.accountId,
        loginState.campusCardCacheVersion,
        HomeSignals.statsVersion,
    ) {
        val term = withContext(Dispatchers.IO) {
            runCatching { ScheduleCache.readCurrentTerm(DataCache(context)) }.getOrNull()
        }
        homeStats = HomeStats.collect(context, term)
        // 校园卡由 refresher 写进 CampusCardCache 的 prefs，不经过 homeStats，单独重读一次。
        cachedBalance = cardPrefs.getFloat("card_balance_cache", -1f)
        cachedDailyRate = cardPrefs.getFloat("card_daily_rate_cache", -1f)
    }

    val audience = loginState.accountType
    val isLoggedIn = loginState.isLoggedIn
    val showStatusSheet = remember { mutableStateOf(false) }

    // 常用入口：按使用频率取 4 个，放进 Hero 卡底部。它是**额外**的入口，分类里照常保留。
    val quickCandidateKeys = remember {
        listOf(
            AppRoute.CampusCard,
            AppRoute.EmptyRoom,
            AppRoute.PaymentCode,
            AppRoute.Notification,
            AppRoute.JwappScore,
            AppRoute.Coupon,
            AppRoute.Library,
            AppRoute.Lms(),
            AppRoute.Agent,
        ).map { it.id }
    }
    val quickRoutes = if (showQuickActions) {
        remember(quickCandidateKeys) {
            ServiceUsageTracker.topKeys(
                context,
                quickCandidateKeys,
                n = 4,
                fallback = listOf(AppRoute.CampusCard.id, AppRoute.EmptyRoom.id, AppRoute.Notification.id) + quickCandidateKeys
            ).filter { it in quickCandidateKeys }.distinct().take(4)
        }.mapNotNull { key -> AppServices.homeFor(audience).firstOrNull { it.route.id == key }?.route }
    } else {
        emptyList()
    }

    // 一行要放在右上角插画旁边，写短：「校外 · 12/14 就绪」。走不走 WebVPN、哪些子系统没连上，点开看明细
    val heroStatus: HomeStatusLine? = if (isLoggedIn) {
        val (netLabel, netTone) = when (loginState.isOnCampus) {
            true -> "校园网" to HomeTone.NORMAL
            false -> "校外" to HomeTone.ACCENT
            null -> "检测中" to HomeTone.MUTED
        }
        val types = LoginType.entries
        val ok = types.count { loginState.sessionManager?.getSiteOrNull(it.siteKey())?.hasLogin == true }
        HomeStatusLine(
            label = netLabel,
            detail = when {
                isRestoring -> "连接中…"
                ok > 0 -> "$ok/${types.size} 就绪"
                else -> "未连接"
            },
            tone = netTone,
        )
    } else null

    val today = LocalDate.now(com.xjtu.toolbox.util.CampusZoneId)
    val weekDay = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.CHINESE)

    val host = object : HomeHost {
        // 九块里列了八块（`HomeCard.INBOX` 由顶栏那一枚角标负责，见这个函数的 KDoc）
        override val honoredCards: Set<HomeCard> = setOf(
            HomeCard.BULLETIN_BAR,
            HomeCard.GREETING,
            HomeCard.WEEK_NUMBER,
            HomeCard.NEXT_UP,
            HomeCard.GLANCE,
            HomeCard.QUICK_ACTIONS,
            HomeCard.CATEGORY_STATS,
            HomeCard.SERVICE_GRID,
        )

        override val audience = loginState.accountType
        override val homeTheme: String = theme
        override val greetingName: String = loginState.cachedNickname.orEmpty()
            .ifBlank { loginState.ywtbUserInfo?.userName.orEmpty() }
            .ifBlank { loginState.activeUsername }
        override val dateLabel: String = "${today.monthOfMonth()} · $weekDay"
        override val weekNumber: Int get() = currentWeekNumber
        override val statusLine: HomeStatusLine? get() = heroStatus
        override val bulletins: List<Bulletin> get() = bulletinItems
        override val stats: Map<String, HomeStat> get() = homeStats
        override val quickRoutes: List<AppRoute> get() = quickRoutes

        override val nextUp: HomeNextUp?
            get() = scheduleReminder ?: when {
                !isLoggedIn -> HomeNextUp(
                    icon = HomeHero.LOGIN_ICON,
                    title = "登录后查看课表和余额",
                    detail = "课表、校园卡会显示在这里",
                )
                !isScheduleReminderLoaded -> HomeNextUp(
                    icon = HomeHero.LOADING_ICON,
                    title = "正在读取今日安排…",
                )
                // 学期代码 / 开学日期缺失或缓存读挂了，和「真没课」不是一回事：
                // 这里说「去同步」，真没课才说「接下来两周都没课」。点击都是进课表页。
                scheduleDataMissing -> HomeNextUp(
                    icon = HomeHero.MISSING_ICON,
                    title = "课表还没同步",
                    detail = "点这里去同步",
                    route = AppRoute.Schedule,
                )
                else -> HomeNextUp(
                    icon = HomeHero.EMPTY_ICON,
                    title = "接下来两周都没课",
                    detail = "问问屁岱怎么安排",
                    route = AppRoute.Schedule,
                )
            }

        override val glances: List<HomeGlanceItem>
            get() {
                if (!isLoggedIn) return emptyList()
                val runway = com.xjtu.toolbox.card.runwayDays(
                    cachedBalance.toDouble(),
                    cachedDailyRate.takeIf { it > 0f }?.toDouble(),
                )
                // 有日均时按「还够几天」判断，没有才退回按金额
                val lowBalance = if (runway != null) runway <= 3 else cachedBalance in 0f..30f
                val exam = homeStats[EXAM_KEY]
                return buildList {
                    add(
                        HomeGlanceItem(
                            icon = Icons.Default.CreditCard,
                            label = if (lowBalance) "余额不多了" else "校园卡余额",
                            value = if (cachedBalance >= 0f) "¥${"%.2f".format(cachedBalance)}" else "—",
                            caption = runway?.let { if (it <= 2) "撑不过 3 天" else "约够 $it 天" },
                            number = cachedBalance.takeIf { it >= 0f }?.toDouble(),
                            tone = if (lowBalance) HomeTone.ALERT else HomeTone.NORMAL,
                            route = AppRoute.CampusCard,
                        )
                    )
                    add(
                        HomeGlanceItem(
                            icon = Icons.Default.Forum,
                            label = "社区",
                            value = "聊两句",
                            caption = "提建议 · 报问题",
                            route = AppRoute.Community,
                        )
                    )
                    if (exam != null) {
                        add(
                            HomeGlanceItem(
                                icon = Icons.AutoMirrored.Filled.EventNote,
                                label = exam.detail?.substringBefore(" · ")?.ifBlank { null } ?: "下一场考试",
                                value = exam.value,
                                // 考试速览格多一行：时间、考场、座号
                                caption = exam.detail?.substringAfter(" · ", "")?.ifBlank { null },
                                tone = HomeTone.ACCENT,
                                route = AppRoute.Schedule,
                            )
                        )
                    }
                }
            }

        override val heroArt: (@Composable BoxScope.() -> Unit) = {
            Image(
                painter = painterResource(R.drawable.home_campus_hero),
                contentDescription = "兴庆校区主楼",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .size(104.dp),
                contentScale = ContentScale.Fit,
            )
        }

        override fun onRoute(route: AppRoute) {
            // 日程格子直接切 tab，不先登教务：日程页自己有缓存，也会自己登
            if (route == AppRoute.Schedule) onNavigateToCourses() else onNavigate(route)
        }

        override fun onProfile() = onNavigateToProfile()
        override fun onBulletinTap(bulletin: Bulletin) = onBulletinTap(bulletin)
        override fun onBulletinDismiss(bulletin: Bulletin) = onBulletinDismiss(bulletin)
        override fun onOpenStatus() {
            showStatusSheet.value = true
        }
    }

    return AppHome(host, showStatusSheet)
}

/** 「10月12日」那一段。`:app` 这一端读的是 `java.time` 的月份与日。 */
private fun LocalDate.monthOfMonth(): String = "${monthValue}月${dayOfMonth}日"
