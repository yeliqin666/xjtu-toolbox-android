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
import com.xjtu.toolbox.util.todayInSystemZone
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Web 这一端的**首页**（`AppRoute.Main`，也就是底栏第一格）—— 屏是 `:core` 的 [HomeScreen]，
 * 本端只交宿主面。它是**这一端所有功能屏的入口**：宫格列出的每一个格子都由
 * [WebSupport.openableRouteIds] 筛过，点得进去，且点进去就是那张真屏。
 *
 * ## 上一轮的漏（这一屏就是来补它的）
 *
 * 阶段 C 换壳之后底栏只剩五格，而 `:app` 的层级里「校历 / 成绩 / 黄页 / 游戏 / 通知 / 图书馆 /
 * 场馆 / 空闲教室 / 校园卡 / 全校课表 / 教师检索 / 评教」这些屏**全挂在首页宫格与学辅目录底下**
 * —— 那两格当时是降级页，于是这些屏在 web 上「代码都在、入口被关了」。这一屏把入口还回去：
 * 宫格的来源是 `:core` 的服务目录 × 本端真能打开的路由（[WebSupport]，唯一真源）。
 *
 * ## 真算的那几样（都不是夹具）
 *
 * | 那一块 | Web 这一端从哪来 |
 * |---|---|
 * | 「第 N 周」 | [HomeHero.weekNumber]（`:core`）+ 本端的课表缓存（`WebScheduleHost.cache`，wasm 上是 **localStorage**） |
 * | 「下一项安排」 | [HomeHero.find]（`:core`，同一段 15 天扫描）+ 同一份缓存里的课表与开学日期；节假日走 `WebScheduleHost.holidays`（只读落盘那份，不联网） |
 * | 分类卡里每个功能此刻的状态 | [HomeScheduleStats]（`:core`）：本端只算得出课表族缓存那几格（教学周 / 未能取到的考试表 / 「N 门课」 / 教材册数），**其余退回纯入口，不显示占位符** |
 * | 问候与日期 | [com.xjtu.toolbox.home.Greeting]（`:core` 的纯函数）+ 本端 `kotlinx-datetime` 的今天；问候里**不写名字**（本端没有账号/昵称这回事） |
 * | 「待办与消息」那一格 | `:core` 的 [InboxStore]（wasm 上落 localStorage）+ [HomeCards.inboxSummary] —— 与消息收纳那一屏**同一份数据、同两条规则** |
 * | 入口宫格 | `:core` 的服务目录（`AppServices`）× [WebSupport.openableRouteIds]（见下） |
 *
 * ## 如实不列的那几块（[HomeHost.honoredCards] 里没有它们，屏上就一个字都不画）
 *
 * - [HomeCard.BULLETIN_BAR]：顶部公告条要 `:core` 的 `BulletinStore` + 一份公告取数（`:data` 的
 *   `BulletinApi`，挂着 okhttp）⇒ 本端没有公告源，不画一条空的；
 * - [HomeCard.GLANCE]：速览格是「校园卡余额 / 社区 / 考试倒计时」那一排 —— 余额读的是 `:app` 的
 *   `CampusCardCache` 落盘摘要（本端没有那份缓存）、考试读的是缓存里的考试表（日程页在 serve 形态下
 *   真取得到考试并会写这份缓存 —— 见 `WebScheduleSource`；campus-api 形态下那一栏仍空着）⇒
 *   三格里两格填不出来，整排仍不画；
 * - [HomeCard.QUICK_ACTIONS]：「常用功能」按点击习惯排（`:app` 的 `ServiceUsageTracker` 记在
 *   `DataCache` 里）⇒ 本端没有那份记录，不画一排随机入口冒充（与桌面同一条口径）；
 * - `heroArt`：Hero 右上角那张校区插画是 `:app` 的 `R.drawable.home_campus_hero`，本端没有那份资源
 *   ⇒ 传 null（与跳一跳那 9 张地标图同一件活：搬进 `:core` 的资源之后才画）；
 * - `statusLine`：「校园网 · 3/14 就绪」要校内外判定 + 各子系统的就绪计数（`:app` 的 `AppLoginState`）
 *   ⇒ 本端既不判校内外、也没有那层明细弹窗，传 null。
 *
 * ## 两个「与桌面那一份不同」的地方（都写在这里，不藏在代码里）
 *
 * 1. **本端首页会发一发收纳请求**：`InboxStore` 在浏览器里只有「进过消息收纳那一屏」才有人写，
 *    所以这一屏进来看一眼 `CampusInboxApi.isDue`（30 分钟 TTL，`SchoolInbox` 同一个值），
 *    该刷就刷一次（失败就算了：那一格不画，不是画个 0）。桌面那一份没有这一步，它的待办只从
 *    自己的收纳屏来 —— 本端补它是因为**web 的底栏没有消息角标**（`ShellNavState.accountBadge = null`），
 *    首页这一格是「还有几件没办」在浏览器里唯一的落点。
 * 2. **`homeTheme` 读的是本端落盘的那份**（`SettingsStore.homeTheme`，与 `:app` 同一个键）——
 *    设置页那一行因此也真会生效（改完切回首页就是新布局），见 `WebSettingsHost.honoredRows`。
 *
 * ## 没有的（不在这一屏的范围里）
 *
 * 本端首页**不画**「常用功能」「速览格」「公告条」；也没有「主动刷新」那一层（`:app` 的
 * `HomeStatsRefresher`）：它在「进这一屏」时读一次 localStorage，课表同步之后再进一次就是新的
 * —— 浏览器里每次刷新页面本来就是一次冷启动，没有第二套信号要接。
 *
 * @param client 与整页同一个客户端（serve 模式下它带着 `Authorization` 头；首页只用它刷收纳）
 * @param mode 这一份 `:web` 在对谁说话（[ApiMode]）—— 决定收纳那一发请求的信封读法
 * @param insets 壳算给当前页的留白：浮在内容上的底栏要在页面末尾留出来
 * @param onNavigate 打开某个功能；落到哪一屏由外壳决定（这里只把 [AppRoute] 递出去）
 */
@Composable
internal fun WebHomeScreen(
    client: HttpClient,
    mode: ApiMode,
    insets: ShellPageInsets,
    onNavigate: (WebTarget) -> Unit,
) {
    // 课表族那一份缓存：与日程屏**同一个宿主实现、同一组键**（`schedule_cache` 命名空间）。
    // 首页与日程读的是同一份 localStorage，所以日程同步过之后首页立刻算得出「下一项安排」。
    val scheduleHost = remember(client, mode) { WebScheduleHost(client, mode) }
    val cache = remember(scheduleHost) { scheduleHost.cache(null) }
    val state = remember { WebHomeState() }

    LaunchedEffect(scheduleHost) {
        // wasm 是单线程、localStorage 是同步的 ⇒ 这一段不需要切线程（桌面那一份要 withContext，
        // 因为它的键值缝是文件）。读的还是「进这一屏」这一下，不发请求。
        val today = todayInSystemZone()
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

    // 「待办与消息」那一格：读的是 `:core` 的收纳（与那一屏同一份数据、同两条规则）。
    // `InboxStore.version` 是那个 store 的可观察版本号 —— 谁写进去了（这一屏自己那一发刷新、
    // 或者用户进消息收纳那一屏）这一格就跟着重算。
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
            theme = settings.homeTheme,
            // 「今天」每次重组现取：跨零点还开着的页面，日期那一行自己就跟上了
            today = { todayInSystemZone() },
            onNavigate = onNavigate,
        ),
        // 底栏浮在内容之上（壳的 `ShellPageInsets.bottomBarReserve`），页面末尾得自己留出来。
        // 玻璃顶栏那一段本端恒为 0（`glassStyle = false`），所以 `contentTopPadding` 跟着壳走。
        extraBottomPadding = insets.bottomBarReserve,
        contentTopPadding = insets.contentTopPadding,
    )
}

/**
 * 首页那一次读盘的落点：屏（[WebHomeScreen]）往里写，宿主面（[WebHomeHost]）读。
 *
 * 与桌面那份 `DesktopHomeState` 同一个分工：抽成对象而不是四个 `remember { mutableStateOf(…) }`，
 * 是为了让宿主面能单独构造 —— 「这一端声明自己有什么」（[HomeHost.honoredCards]）与那三种
 * 「说不出来」的说法因此是**可读的**，而不是散在屏里的 `if`。
 */
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

/**
 * Web 这一端的首页宿主面 —— 「这一端有什么」，与 [WebScheduleHost] / [WebSettingsHost] 同一套纪律：
 * 真会照着办的才列进 [honoredCards]，拿不到的槽位返回 `null`/空表/默认值，屏按不画处理。
 *
 * 它没有状态：数据在 [state] 与 [inboxSummary] 里，画法在 `:core` 的 `HomeScreen` 里。
 *
 * @param state 那一次读盘的结果（屏写、这里读）
 * @param inboxSummary 「待办与消息」那一格；null = 不画（没有待办也没有未读时就不画，免得画个 0）
 * @param theme 首页外观（卡片 / 图标主题），来自本端落盘的那一份设置
 * @param today 「今天」由调用方给：屏上那行日期跟着重组走
 * @param onNavigate 落到哪一屏由外壳决定（这里只递 [WebTarget]）
 */
internal class WebHomeHost(
    private val state: WebHomeState,
    private val inboxSummary: HomeInboxSummary?,
    theme: String,
    private val today: () -> LocalDate,
    private val onNavigate: (WebTarget) -> Unit,
) : HomeHost {

    /**
     * 六块（九块里不列 `BULLETIN_BAR` / `GLANCE` / `QUICK_ACTIONS`）。
     *
     * 不列的那三块各自缺什么宿主面，写在 [WebHomeScreen] 的类 KDoc 里 —— 一条条都是"本端没有
     * 那个源"，不是"懒得画"（列了却空着才是假话）。
     */
    override val honoredCards: Set<HomeCard> = setOf(
        HomeCard.GREETING,
        HomeCard.WEEK_NUMBER,
        HomeCard.NEXT_UP,
        HomeCard.INBOX,
        HomeCard.CATEGORY_STATS,
        HomeCard.SERVICE_GRID,
    )

    /**
     * 目录按**本科生**那一份筛。本端没有身份这回事（凭据在 campus-api / serve 进程里），
     * 而目录里按身份筛的两条（快速考勤流水 / 成绩单）本来都是 `showOnHome = false`
     * ⇒ 这个取值在首页宫格上不产生任何差别。**不改 `AppServices`，也不编一个假身份。**
     */
    override val audience: AccountType = AccountType.UNDERGRADUATE

    /** 首页两套布局（卡片 / 图标主题）：读本端落盘那份（见 [WebHomeScreen] 文件头第 2 条）。 */
    override val homeTheme: String = theme

    /** 问候里不写名字：本端没有账号与昵称这回事（`:app` 那一位来自登录态）。 */
    override val greetingName: String = ""

    override val dateLabel: String get() = dateLabel(today())

    override val weekNumber: Int get() = state.weekNumber

    /**
     * 「下一项安排」：真算得出就给真的，算不出就给**三种说法之一**（与桌面那份逐条一致）——
     * 「正在读取」「课表还没同步」「接下来两周都没课」说的是三件不同的事，屏上那两行字也不同。
     *
     * 后两种都带 `route = AppRoute.Schedule`：点这一格去同步 / 去看整学期。
     */
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

    override val stats: Map<String, HomeStat> get() = state.stats

    override val inbox: HomeInboxSummary? get() = inboxSummary

    /** 这一端真能打开的路由（[WebSupport] 那份唯一真源）—— 打不开的入口不画。 */
    override val openableRouteIds: Set<String> = WebSupport.openableRouteIds

    override fun onRoute(route: AppRoute) {
        onNavigate(WebTarget.App(route))
    }

    /**
     * 「我的」那一屏：本端是底栏第五格（共享设置屏）。只有「下一项安排」算不出来又没给路由时
     * 屏才会走到这里；本端那三种说法都带路由（去日程），所以正常路径上不会触发。
     */
    override fun onProfile() {
        onNavigate(WebTarget.Profile)
    }
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 「10月12日 · 周一」那一段（与 `:app` 的 `dateLabel` 同一套说法，只是这一端从 `kotlinx` 拿）。 */
private fun dateLabel(today: LocalDate): String =
    "${today.month.ordinal + 1}月${today.day}日 · ${WEEKDAY_LABELS[today.dayOfWeek.ordinal]}"
