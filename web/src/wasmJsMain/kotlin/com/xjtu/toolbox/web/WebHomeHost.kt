package com.xjtu.toolbox.web

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
import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.core.net.CampusInboxApi
import com.xjtu.toolbox.home.HomeCard
import com.xjtu.toolbox.home.HomeCards
import com.xjtu.toolbox.home.HomeHero
import com.xjtu.toolbox.home.HomeHost
import com.xjtu.toolbox.home.HomeInboxSummary
import com.xjtu.toolbox.home.HomeNextUp
import com.xjtu.toolbox.home.HomeScheduleStats
import com.xjtu.toolbox.home.HomeScreen
import com.xjtu.toolbox.home.HomeStat
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.shell.ShellPageInsets
import com.xjtu.toolbox.util.todayOnCampus
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** Web 这一端的首页（`AppRoute.Main`，也就是底栏第一格）—— 屏是 `:core` 的 [HomeScreen]，本端只交宿主面。 */
@Composable
internal fun WebHomeScreen(
    client: HttpClient,
    mode: ApiMode,
    insets: ShellPageInsets,
    onNavigate: (WebTarget) -> Unit,
) {
    // 课表族那一份缓存：与日程屏**同一个宿主实现、同一组键**（`schedule_cache` 命名空间）。
    // 首页与日程读的是同一份 localStorage，所以日程同步过之后首页立刻算得出「下一项安排」。
    val campusNow = com.xjtu.toolbox.ui.rememberCampusNow()
    val scheduleHost = remember(client, mode) { WebScheduleHost(client, mode) }
    val cache = remember(scheduleHost) { scheduleHost.cache(null) }
    val state = remember { WebHomeState() }

    LaunchedEffect(scheduleHost, campusNow.date, campusNow.hour, campusNow.minute) {
        // wasm 是单线程、localStorage 是同步的 ⇒ 这一段不需要切线程（桌面那一份要 withContext，
        // 因为它的键值缝是文件）。读的还是「进这一屏」这一下，不发请求。
        val today = todayOnCampus()
        val term = ScheduleCache.readCurrentTerm(cache)
        val schedule = ScheduleCache.readCurrentTermSchedule(cache)
        val start = schedule?.start
        // 「课表还没同步 / 缓存里缺开学日期」与「接下来两周真没课」不是一件事：屏上那两句说法不同。
        // 判据与桌面那份、以及 `:app` 的 `AppHomeHost` 逐条一致。
        val missing = schedule == null || start == null
        state.nextUp = if (missing) null else HomeHero.find(
            courses = schedule.courses,
            startOfTerm = start,
            holidayDates = scheduleHost.holidays.peekCached(cache).keys,
            route = AppRoute.Schedule,
        )
        state.scheduleDataMissing = missing
        state.weekNumber = HomeHero.weekNumber(start, today)
        // 各功能此刻的状态：本端只有「课表族缓存本地推算」那几格（见文件头那张表）
        state.stats = HomeScheduleStats.of(cache, term, today)
        state.isLoaded = true
    }

    val inboxVersion = InboxStore.version
    val inbox = remember(inboxVersion) {
        HomeCards.inboxSummary(InboxStore.load(), Clock.System.now().toEpochMilliseconds())
            .takeUnless { it.isEmpty }
    }

    // 见文件头第 1 条：只在「该刷了」时发一发，而且失败**不算错**（那一格不画就是了）。
    LaunchedEffect(mode) {
        val account = AccountContext.activeAccountId
        val source = CampusInboxApi(client, API_BASE, mode)
        val now = Clock.System.now().toEpochMilliseconds()
        if (source.isDue(account, now)) runCatching { source.refresh(account) }
    }

    val settings = remember { SettingsStore() }
    HomeScreen(
        host = WebHomeHost(
            state = state,
            inboxSummary = inbox,
            // 「今天」每次重组现取：跨零点还开着的页面，日期那一行自己就跟上了
            today = { todayOnCampus() },
            onNavigate = onNavigate,
        ),
        // 底栏浮在内容之上（壳的 `ShellPageInsets.bottomBarReserve`），页面末尾得自己留出来。
        // 玻璃顶栏那一段本端恒为 0（`glassStyle = false`），所以 `contentTopPadding` 跟着壳走。
        extraBottomPadding = insets.bottomBarReserve,
        contentTopPadding = insets.contentTopPadding,
    )
}

/** 首页那一次读盘的落点：屏（[WebHomeScreen]）往里写，宿主面（[WebHomeHost]）读。 */
@Stable
internal class WebHomeState {
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

/** Web 这一端的首页宿主面。 */
internal class WebHomeHost(
    private val state: WebHomeState,
    private val inboxSummary: HomeInboxSummary?,
    private val today: () -> LocalDate,
    private val onNavigate: (WebTarget) -> Unit,
) : HomeHost {

    /** 六块（九块里不列 `BULLETIN_BAR` / `GLANCE` / `QUICK_ACTIONS`）。 */
    override val honoredCards: Set<HomeCard> = setOf(
        HomeCard.GREETING,
        HomeCard.WEEK_NUMBER,
        HomeCard.NEXT_UP,
        HomeCard.INBOX,
        HomeCard.CATEGORY_STATS,
        HomeCard.SERVICE_GRID,
    )

    /**
     * 首页底部那一句（见 `HomeHost.gapNote`）：少的两块合成一句。
     *
     * 不列 QUICK_ACTIONS（常用功能）：那是丁批要做的事，做完这句自然过时——与其手改文案，不如不说。
     */
    override val gapNote: String =
        "网页版首页少两块：公告堆叠条和速览小格。公告要读交大那边的公告接口（serve 端还没转发这一路），" +
            "速览要各服务此刻的状态（本端只有课表族缓存算得出来）。这两块 App 首页有。"

    /** 目录按本科生那一份筛。 */
    override val audience: AccountType = AccountType.UNDERGRADUATE


    /** 问候里不写名字：本端没有账号与昵称这回事（`:app` 那一位来自登录态）。 */
    override val greetingName: String = ""

    override val dateLabel: String get() = dateLabel(today())

    override val weekNumber: Int get() = state.weekNumber

    /** 「下一项安排」：真算得出就给真的，算不出就给三种说法之一（与桌面那份逐条一致） */
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
                title = HomeHero.NOTHING_TITLE,
                detail = "点这里看整学期",
                route = AppRoute.Schedule,
            )
        }

    override val stats: Map<String, HomeStat> get() = state.stats

    override val inbox: HomeInboxSummary? get() = inboxSummary

    /** 这一端真能打开的路由（[WebSupport] 那份唯一真源）—— 打不开的入口不画。 */
    override val openableRouteIds: Set<String> = WebSupport.openableRouteIds

    override fun onRoute(route: AppRoute) {
        onNavigate(WebTarget.App(route))
    }

    /** 「我的」那一屏：本端是底栏第五格（共享设置屏）。 */
    override fun onProfile() {
        onNavigate(WebTarget.Profile)
    }
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 「10月12日 · 周一」那一段（与 `:app` 的 `dateLabel` 同一套说法，只是这一端从 `kotlinx` 拿）。 */
private fun dateLabel(today: LocalDate): String =
    "${today.month.ordinal + 1}月${today.day}日 · ${WEEKDAY_LABELS[today.dayOfWeek.ordinal]}"
