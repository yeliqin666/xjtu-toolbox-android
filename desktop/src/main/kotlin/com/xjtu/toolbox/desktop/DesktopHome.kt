package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.home.EXAM_KEY
import com.xjtu.toolbox.home.HomeCard
import com.xjtu.toolbox.home.HomeCards
import com.xjtu.toolbox.home.HomeGlanceItem
import com.xjtu.toolbox.home.HomeHero
import com.xjtu.toolbox.home.HomeHost
import com.xjtu.toolbox.home.HomeIcons
import com.xjtu.toolbox.home.HomeInboxSummary
import com.xjtu.toolbox.home.HomeNextUp
import com.xjtu.toolbox.home.HomeScheduleStats
import com.xjtu.toolbox.home.HomeScreen
import com.xjtu.toolbox.home.HomeStat
import com.xjtu.toolbox.home.HomeTone
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleCacheStore
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** 桌面端的首页（`AppRoute.Main`）—— 与 Android 的首页是同一份屏（`:core` 的 [HomeScreen]）。 */
@Composable
internal fun DesktopHomeScreen(
    auth: DesktopAuth,
    bulletins: DesktopBulletins,
    onNavigate: (DesktopTarget) -> Unit,
) {
    val accountId = AccountContext.activeAccountId
    val host = remember(auth) { DesktopScheduleHost(auth) }
    val cache: ScheduleCacheStore = remember(accountId) { host.cache(accountId) }
    val state = remember(accountId) { DesktopHomeState() }

    // ── 课表族缓存里那几样（下一项安排 / 教学周 / 各功能此刻的状态）──
    // 只在「进这一屏」「切账号」「课表同步过」时读一次；读的都是本机缓存，一个请求都不发。
    LaunchedEffect(accountId, DesktopHomeSignals.scheduleVersion) {
        val data = withContext(Dispatchers.IO) {
            val today = todayOnCampus()
            val term = ScheduleCache.readCurrentTerm(cache)
            val schedule = ScheduleCache.readCurrentTermSchedule(cache)
            val start = schedule?.start
            // 开学日期缺失（或还没同步过课表）与「接下来两周真没课」不是一件事，
            // 屏上那两句说法不同 —— 判据与 `:app` 的 `AppHomeHost` 逐条一致。
            val missing = schedule == null || start == null
            DesktopHomeData(
                nextUp = if (missing) null else HomeHero.find(
                    courses = schedule.courses,
                    startOfTerm = start,
                    holidayDates = host.holidays.peekCached(cache).keys,
                    route = AppRoute.Schedule,
                ),
                scheduleDataMissing = missing,
                weekNumber = HomeHero.weekNumber(start, today),
                // 各功能此刻的状态：本端只有「课表族缓存本地推算」那几格（见文件头那张表）
                stats = HomeScheduleStats.of(cache, term, today),
            )
        }
        state.nextUp = data.nextUp
        state.scheduleDataMissing = data.scheduleDataMissing
        state.weekNumber = data.weekNumber
        state.stats = data.stats
        state.isLoaded = true
    }

    val inboxVersion = InboxStore.version
    val inbox = remember(inboxVersion, accountId) {
        HomeCards.inboxSummary(InboxStore.load(accountId), Clock.System.now().toEpochMilliseconds())
            .takeUnless { it.isEmpty }
    }

    HomeScreen(
        host = DesktopHomeHost(
            bulletinHost = bulletins,
            state = state,
            inboxSummary = inbox,
            // 「今天」每次重组现取：跨零点还开着的窗口，日期那一行自己就跟上了
            today = { todayOnCampus() },
            onNavigate = onNavigate,
        ),
    )
}

/** 首页那一次读盘的落点：屏（[DesktopHomeScreen]）往里写，宿主面（[DesktopHomeHost]）读。 */
@Stable
internal class DesktopHomeState {
    /** 下一项安排；null = 还没算出来或真没有（由 [scheduleDataMissing] / [isLoaded] 分开说）。 */
    var nextUp by mutableStateOf<HomeNextUp?>(null)

    /** 第一次读盘回来了没有（没回来时说「正在读取本机课表…」）。 */
    var isLoaded by mutableStateOf(false)

    /** true = 课表数据不可用（没同步过、或缓存里缺开学日期），不是「真没课」。 */
    var scheduleDataMissing by mutableStateOf(false)

    /** 教学周；0 = 算不出来。 */
    var weekNumber by mutableIntStateOf(0)

    /** 各功能此刻的状态（键 = `AppRoute.id`），只含课表族缓存算得出来的那几格。 */
    var stats by mutableStateOf<Map<String, HomeStat>>(emptyMap())
}

/** 桌面端首页的宿主面 —— 「这一端有什么」，与 `DesktopScheduleHost` / `DesktopSettingsHost`同一套纪律。 */
internal class DesktopHomeHost(
    private val bulletinHost: DesktopBulletins,
    private val state: DesktopHomeState,
    /** 「待办与消息」那一格；null = 不画（没有待办也没有未读时就不画，免得画个 0）。 */
    private val inboxSummary: HomeInboxSummary?,
    /** 「今天」由调用方给：屏上那行日期跟着重组走，测试里要能钉死。 */
    private val today: () -> LocalDate,
    private val onNavigate: (DesktopTarget) -> Unit,
) : HomeHost {

    /** 八块（九块里不列 `QUICK_ACTIONS`）。 */
    override val honoredCards: Set<HomeCard> = setOf(
        HomeCard.BULLETIN_BAR,
        HomeCard.GREETING,
        HomeCard.WEEK_NUMBER,
        HomeCard.NEXT_UP,
        HomeCard.GLANCE,
        HomeCard.INBOX,
        HomeCard.CATEGORY_STATS,
        HomeCard.SERVICE_GRID,
    )

    override val audience: AccountType = AccountContext.activeAccountType

    /** 首页两套布局：读的是本端落盘的那一份（与 `:app` 同一个键）。 */
    override val homeTheme: String get() = desktopSettings.homeTheme

    override val greetingName: String = ""

    override val dateLabel: String get() = dateLabel(today())

    override val weekNumber: Int get() = state.weekNumber

    override val bulletins: List<Bulletin> get() = bulletinHost.heroBulletins

    override val nextUp: HomeNextUp?
        get() = state.nextUp ?: when {
            !state.isLoaded -> HomeNextUp(
                icon = HomeHero.LOADING_ICON,
                title = "正在读取本机课表…",
            )
            state.scheduleDataMissing -> HomeNextUp(
                icon = HomeHero.MISSING_ICON,
                title = "课表还没同步",
                detail = "点这里去同步",
                route = AppRoute.Schedule,
            )
            else -> HomeNextUp(
                icon = HomeHero.EMPTY_ICON,
                title = "接下来两周都没课",
                detail = "点这里看整学期",
                route = AppRoute.Schedule,
            )
        }

    override val glances: List<HomeGlanceItem>
        get() = buildList {
            add(
                HomeGlanceItem(
                    icon = HomeIcons.of(AppRoute.Community),
                    label = "社区",
                    value = "聊两句",
                    caption = "提建议 · 报问题",
                    route = AppRoute.Community,
                )
            )
            // 考试那一格：表里有才画（读的是缓存里的考试表，与 `:app` 同一个键）
            state.stats[EXAM_KEY]?.let { exam ->
                add(
                    HomeGlanceItem(
                        icon = HomeHero.EXAM_ICON,
                        label = exam.detail?.substringBefore(" · ")?.ifBlank { null } ?: "下一场考试",
                        value = exam.value,
                        caption = exam.detail?.substringAfter(" · ", "")?.ifBlank { null },
                        tone = HomeTone.ACCENT,
                        route = AppRoute.Schedule,
                    )
                )
            }
        }

    override val stats: Map<String, HomeStat> get() = state.stats

    override val inbox: HomeInboxSummary? get() = inboxSummary

    /** 这一端真能打开的路由（见 [DESKTOP_OPENABLE_ROUTE_IDS]）—— 打不开的入口不画。 */
    override val openableRouteIds: Set<String> = DESKTOP_OPENABLE_ROUTE_IDS

    override fun onRoute(route: AppRoute) {
        onNavigate(DesktopTarget.App(route))
    }

    /** 桌面没有「我的」那一屏（`:app` 的第五个 tab）。 */
    override fun onProfile() {
        onNavigate(DesktopTarget.App(AppRoute.Settings))
    }

    override fun onBulletinTap(bulletin: Bulletin) = bulletinHost.onTap(bulletin)

    override fun onBulletinDismiss(bulletin: Bulletin) = bulletinHost.dismissHero(bulletin)
}

/** 这一端真能打开的路由 id —— 首页宫格按它筛：打不开的入口不画 （与「点了会失败的按钮一个都不画」同一条纪律）。 */
internal val DESKTOP_OPENABLE_ROUTE_IDS: Set<String> = buildSet {
    DESKTOP_SUPPORTED_ROUTES.forEach { add(it.first.id) }
    add(AppRoute.Settings.id)
    add(AppRoute.Main.id)
}

/** 首页那一次读盘的结果（几样一起算完再上屏，免得一次重组里重读几遍）。 */
private data class DesktopHomeData(
    val nextUp: HomeNextUp?,
    val scheduleDataMissing: Boolean,
    val weekNumber: Int,
    val stats: Map<String, HomeStat>,
)

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 「10月12日 · 周一」那一段（与 `:app` 的 `dateLabel` 同一套说法，只是这一端从 `kotlinx` 拿）。 */
private fun dateLabel(today: LocalDate): String =
    "${today.month.ordinal + 1}月${today.day}日 · ${WEEKDAY_LABELS[today.dayOfWeek.ordinal]}"

/** 「课表落地了」这条跨层信号在桌面上的落点 —— `:app` 那份是 `HomeSignals`（首页 Hero 靠它重读课表缓存）。 */
internal object DesktopHomeSignals {

    var scheduleVersion by mutableIntStateOf(0)
        private set

    fun onScheduleSynced() {
        scheduleVersion++
    }
}
