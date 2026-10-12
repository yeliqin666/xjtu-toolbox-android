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
import com.xjtu.toolbox.util.todayInSystemZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * 桌面端的**首页**（`AppRoute.Main`）—— 与 Android 的首页是**同一份屏**（`:core` 的 [HomeScreen]）。
 *
 * ## 它是什么形态
 *
 * 这一端不另造一套壳：首页就是外壳（`ToolboxDesktopApp`）底栏里的第一格（「首页」），
 * 与「校历 / 图书馆 / 黄页 / 游戏 / 全部」那几格平级；`AppRoute.Main` 这个路由在桌面上落到这里，
 * 不再落 `NotPortedScreen`。`:app` 那边首页是「Tab 1」、消息角标在玻璃顶栏上；桌面的底栏没有消息角标，
 * 所以这一端把「待办与消息」那一格画在首页里（[HomeCard.INBOX]，见下）。
 *
 * ## 真出数的那几样（都不是夹具）
 *
 * | 那一块 | 桌面上从哪来 |
 * |---|---|
 * | 「第 N 周」 | [HomeHero.weekNumber]（`:core`）+ 本端的课表缓存（[DesktopScheduleHost.cache]，键值文件） |
 * | 「下一项安排」 | [HomeHero.find]（`:core`，同一段 15 天扫描）+ 同一份缓存里的课表与开学日期；节假日走 [DesktopScheduleHost.holidays]（`:data` 的两个公开接口，只读缓存） |
 * | 「考试倒计时」那一格 | [HomeScheduleStats]（`:core`，本轮从 `:app` 的 `HomeStats` 里搬出来的那几格）读缓存里的考试表 |
 * | 分类卡里每个功能此刻的状态 | 同上：`HomeScheduleStats` 那份表（教学周 / 一场考试 / 课数 / 教材册数）—— 其余功能这一端没有「谁有数据谁负责写」的那份落盘摘要 ⇒ 退回纯入口，不显示占位符 |
 * | 「入口宫格」 | `:core` 的服务目录（`AppServices`）× **这一端真能打开的那些路由**（[DESKTOP_OPENABLE_ROUTE_IDS]） |
 * | 「待办与消息」那一格 | `:core` 的 [InboxStore] + [HomeCards.inboxSummary]（与消息收纳那一屏同一份数据、同两条规则） |
 * | 顶部公告条 / 冷启动公告弹框 | `:core` 的 `BulletinNoticePanel` / `BulletinLaunchDialog` + 本端的 [DesktopBulletins] |
 *
 * ## 如实降级（做到哪一步、缺的是哪一环）
 *
 * - **不发任何请求**：首页只在「进这一屏」「课表同步过」（[DesktopHomeSignals]）「切了账号」时
 *   重读本地缓存，一个网络请求都不发 —— 与 `:app` 的首页同一条纪律（那边主动刷新在
 *   `HomeStatsRefresher` 里跑，桌面还没有那一层，所以这一端没填的格子就是空的）。
 * - **速览格只有两格**：校园卡余额那一格**不画** —— `:app` 读的是 `CampusCardCache` 的
 *   SharedPreferences（这一端没有那份缓存，校园卡屏自己是现取的）。考试与社区那两格是真的。
 * - **Hero 右上的校区插画不画**：那是 `:app` 的 `R.drawable.home_campus_hero`，
 *   桌面这一端没有那份资源（与跳一跳的远景地标图同一条口径：不画，不用别的东西冒充）。
 * - **没有那一行「校园网 · 3/14 就绪」**：桌面只按直连走（`DesktopAuth` 的已知缺口），
 *   既没有校内外判定，也没有 `:app` 那层「子系统连接明细」弹窗 ⇒ `statusLine` 传 `null`，不画。
 * - **没有「常用功能」那一排**：`:app` 按点击习惯排（`ServiceUsageTracker`），桌面没有那份记录
 *   ⇒ `HomeCard.QUICK_ACTIONS` 不列进 [HomeHost.honoredCards]，屏上就不画那一块
 *   （设置页那行「显示常用功能」也因此不列，见 `DesktopSettingsHost.honoredRows`）。
 * - **问候里不写名字**：一网通办那份昵称/姓名属 `:app` 的学工系统缓存（`DesktopAccounts` 的
 *   「如实降级」同一条：账号列表里显示的是学号）⇒ `greetingName` 空串，只问候。
 * - **首页两套布局跟着设置项走**：`homeTheme` 读本端落盘的 `app_settings.properties`
 *   （与 `:app` 同一个键 `home_theme`），设置页那一行这一轮也接上了。
 *
 * ## TODO（这一端还缺的，写清楚免得被当成坏掉）
 *
 * 1. **桌面小组件**：`:app` 有课表/通知/待办/校园卡四个 AppWidget，这一端**没有小组件**
 *    —— 设计文档 §6 Stage C 的口径是「小组件用系统通知替代」，那条路在桌面上还没做
 *    （没有托盘/通知中心宿主，`ScheduleCapability.WIDGET_REFRESH` 因此不列）。所以
 *    「课表落地了就刷一下小组件」这件事在桌面上不存在，只发首页信号。
 * 2. **速览格的校园卡余额**：要把 `CampusCardCache` 那一份（余额 + 最近一笔消费）搬进共享层
 *    （`:core` 的键值缝，与 `VenueFavorites` / `BulletinStore` 同一个搬法），这一格才能画。
 * 3. **冷启动主动刷一轮消息收纳**：`:app` 冷启动会拉一轮（`HomeStatsRefresher` 那一层），
 *    桌面的收纳只有「进消息收纳那一屏」时才拉（`AppInboxSource.isDue`）⇒ 没进过那一屏时
 *    首页这一格**不画**（不是画个 0）。
 * 4. **右上角那张校区插画**：需要把图搬进 `:core` 的资源（Compose Multiplatform 的
 *    `compose.components.resources`），与跳一跳那 9 张地标图同一件活。
 *
 * @param auth 会话 + 凭据（首页要用它的课表缓存宿主面与节假日源）
 * @param bulletins 公告宿主面（顶部那条 + 冷启动弹框；由外壳持有，因为弹框挂在壳上）
 * @param onNavigate 打开某个功能；落到哪一屏由外壳决定
 */
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
            val today = todayInSystemZone()
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

    // 「待办与消息」那一格：读的是 `:core` 的消息收纳（与那一屏同一份数据、同两条规则）。
    // `InboxStore.version` 是那个 store 的可观察版本号 —— 谁写进去了（消息收纳那一屏、
    // 学校那四路）这一格就跟着重算。
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
            today = { todayInSystemZone() },
            onNavigate = onNavigate,
        ),
    )
}

/**
 * 首页那一次读盘的落点：屏（[DesktopHomeScreen]）往里写，宿主面（[DesktopHomeHost]）读。
 *
 * 抽成一个对象而不是四个 `remember { mutableStateOf(...) }`，是为了让宿主面能单独构造
 * —— 于是「这一端声明自己有什么」（[HomeHost.honoredCards]）与那四种「说不出来」的说法
 * 可以在 `:desktop:test` 里钉住，不必先把屏渲染出来（见 `DesktopHomeJvmTest`）。
 */
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

/**
 * 桌面端首页的**宿主面** —— 「这一端有什么」，与 `DesktopScheduleHost` / `DesktopSettingsHost`
 * 同一套纪律：真会照着办的才列进 [honoredCards]，拿不到的槽位返回 `null`/空表，屏按不画处理。
 *
 * 它没有状态：数据在 [state]（那一次读盘的结果）与公告宿主面（[bulletinHost]）里，画法在 `:core` 的屏里。
 */
internal class DesktopHomeHost(
    private val bulletinHost: DesktopBulletins,
    private val state: DesktopHomeState,
    /** 「待办与消息」那一格；null = 不画（没有待办也没有未读时就不画，免得画个 0）。 */
    private val inboxSummary: HomeInboxSummary?,
    /** 「今天」由调用方给：屏上那行日期跟着重组走，测试里要能钉死。 */
    private val today: () -> LocalDate,
    private val onNavigate: (DesktopTarget) -> Unit,
) : HomeHost {

    /**
     * 八块（九块里不列 `QUICK_ACTIONS`）。
     *
     * 不列的那一块是「常用功能」：`:app` 按点击习惯排（`ServiceUsageTracker` 那份记录），
     * 桌面没有它 ⇒ 屏上不画那一排（不是画一排随机的入口冒充）。
     */
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

    /**
     * 桌面没有「我的」那一屏（`:app` 的第五个 tab）。最接近的是**设置页**
     * —— 它也是这一端唯一能到设置页的入口（底栏六格里没有它）。
     *
     * 只有「下一项安排」算不出来又没给路由时屏才会走到这里；本端那四种说法都带路由
     *（去「我的课表」），所以正常路径上不会触发。
     */
    override fun onProfile() {
        onNavigate(DesktopTarget.App(AppRoute.Settings))
    }

    override fun onBulletinTap(bulletin: Bulletin) = bulletinHost.onTap(bulletin)

    override fun onBulletinDismiss(bulletin: Bulletin) = bulletinHost.dismissHero(bulletin)
}

/**
 * 这一端**真能打开**的路由 id —— 首页宫格按它筛：打不开的入口**不画**
 *（与「点了会失败的按钮一个都不画」同一条纪律）。
 *
 * 表就是索引页那张 `DESKTOP_SUPPORTED_ROUTES`（「真数据路由」），再加上设置页：它是这一端真有的屏，
 * 只是不取数所以没进那张表。`AppRoute.Main` 自己也放进来 —— 它在服务目录里没有格子
 *（首页是一个 tab、不是一条功能），列进来只是为了让「这一端能开的路由」这张表自洽。
 */
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

/**
 * 「课表落地了」这条跨层信号在桌面上的落点 —— `:app` 那份是 `HomeSignals`
 *（首页 Hero 靠它重读课表缓存；那边还挂着桌面小组件刷新与提醒评估）。
 *
 * 桌面这一端只有一件事要通知：首页重读一次缓存（[DesktopHomeScreen] 的 `LaunchedEffect`
 * 盯着这个版本号）。桌面上没有小组件、也没有 `:app` 那层提醒评估，所以这里不抄那两样。
 */
internal object DesktopHomeSignals {

    var scheduleVersion by mutableIntStateOf(0)
        private set

    fun onScheduleSynced() {
        scheduleVersion++
    }
}
