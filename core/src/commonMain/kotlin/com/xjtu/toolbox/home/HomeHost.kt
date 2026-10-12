package com.xjtu.toolbox.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.nav.AppRoute
import kotlinx.datetime.LocalDateTime

/**
 * 首页上的每一块 —— 这是「首页有什么」的**清单**，也是各端如实声明自己那一份的词汇表。
 *
 * 分得比「一张卡」细一档，是因为各端缺的往往正好是其中一半：桌面有公告条与入口宫格，
 * 但没有课表缓存（于是「下一项安排」「教学周」画不出来）、没有校园卡余额缓存（速览格里
 * 那一格画不出来）。粗粒度的一张「Hero 卡」会让「画一半」变成假话。
 */
enum class HomeCard {
    /** 顶部那条公告堆叠条（`:core` 的 `BulletinNoticePanel`）。 */
    BULLETIN_BAR,

    /** 问候与日期（`:core` 的 `Greeting` + 宿主给的日期文案）。 */
    GREETING,

    /** 「第 N 周」（由开学日期本地推算，要有课表缓存/学期表）。 */
    WEEK_NUMBER,

    /** 「下一项安排」（下节课 / 下一场考试）。 */
    NEXT_UP,

    /** 速览小格（校园卡余额 / 社区 / 考试倒计时那一排）。 */
    GLANCE,

    /** 常用入口（按点击习惯排出来的 top-N）。 */
    QUICK_ACTIONS,

    /** 分类卡里每个功能此刻的状态（`:app` 那份落盘摘要 / 别的端自己算）。 */
    CATEGORY_STATS,

    /** 分类卡里的功能宫格（入口本身，目录来自 [AppServices]）。 */
    SERVICE_GRID,

    /** 「待办与消息」那一格（[HomeCards.inboxSummary]）。 */
    INBOX,
}

/**
 * 页面上的深浅一档：正常（绿）/ 主题色加重（蓝）/ 警示（红）/ 中性灰。
 *
 * 两端的配色表不同，但「这是哪一档」是数据：`:app` 的「校外」是蓝、「校园网」是绿、
 * 「检测中」是灰 —— 那一行原本在屏里算，现在由宿主算好这一档，屏只管取色。
 */
enum class HomeTone { NORMAL, ACCENT, ALERT, MUTED }

/** Hero 里那一行状态（网络环境 + 子系统就绪数），点开看明细。 */
data class HomeStatusLine(val label: String, val detail: String, val tone: HomeTone)

/** 速览格里的一项。 */
data class HomeGlanceItem(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val caption: String? = null,
    /** 金额类传数值：第一次从 0 滚上来，之后随缓存刷新从旧值滚到新值。 */
    val number: Double? = null,
    val tone: HomeTone = HomeTone.NORMAL,
    val route: AppRoute? = null,
)

/**
 * 「下一项安排」那一格的内容。
 *
 * 两种形态由 [timeLabel] 是否为空分开（与搬迁前 `HeroNextUp` 那两个分支逐字对应）：
 *  - 有 [timeLabel] = 真有下一项：左边一列是钟点 + 小字，右边是名称，[eta] 用主题色加重，
 *    [detail] 接在后面（「明天 · 主楼A101」）；
 *  - 没有 = 说不出来（没登录 / 还没读到 / 课表没同步 / 这两周真没课）：左边画 [icon]，
 *    右边只有 [title] 与 [detail] —— 四种说法的文案由宿主挑。
 */
data class HomeNextUp(
    val icon: ImageVector,
    val title: String,
    val timeLabel: String? = null,
    val timeCaption: String? = null,
    val eta: String? = null,
    /**
     * 这一项的开始时刻 —— 屏不用它（它要的那句话已经在 [timeLabel]/[eta] 里），
     * 宿主用它做别的事：`:app` 的提醒评估（「几分钟后上课」）读的就是它。
     */
    val startAt: LocalDateTime? = null,
    val detail: String? = null,
    val route: AppRoute? = null,
)

/**
 * 首页的**宿主槽位** —— 「这一端有什么」，而不是「这一端是不是 Android」。
 *
 * ## 与设置页/日程页同一套纪律
 *
 * [honoredCards] 是**这一端真会照着办**的那几块，`HomeScreen` 只画列进来的（与
 * `SettingsHost.honoredRows`、`ScheduleHost.honored` 同一条规矩：存了没人理、点了没反应的东西
 * 一个都不画）。拿不到内容的槽位返回 `null`（或空表）：那表示「这一端算不出来」，屏按不画处理，
 * 而不是画一个 `—` 占位符假装有这一格。
 *
 * ## 为什么问候/日期由宿主给
 *
 * 各端的「今天」与「叫什么名字」不是同一件事：`:app` 拿的是登录态里的昵称与 `java.time` 的中文
 * 星期，桌面端没有那两个来源。所以屏只负责排版，文案由宿主递进来。
 *
 * ## 为什么插画与状态表也在槽位里
 *
 * Hero 右上角那张校区插画是各端**自己的资源**（`:app` 是 `R.drawable.home_campus_hero`，桌面没有
 * 这张图），[heroArt] 让各端画自己的那一张、或者不画。[stats] 则是「各功能此刻的状态」——
 * `:app` 有一份落盘摘要（`HomeStats.collect`），桌面没有那份「谁有数据谁负责写」的机制，
 * 于是它交空表、只画入口不画数值（见 [HomeCard.CATEGORY_STATS]）。
 */
interface HomeHost {

    /** 这一端**真会照着办**的那些块（见 [HomeCard]）。 */
    val honoredCards: Set<HomeCard>

    /** 这一端当前身份。目录（[AppServices]）按它筛：研究生看不到成绩单那几项。 */
    val audience: AccountType

    /**
     * 首页外观：`SettingsStore.THEME_CARD` / `THEME_ICON`。
     *
     * 用字符串而不是枚举：这两个值已经写进用户数据（设置页那十项之一），两头同一个键同一个值，
     * 本轮搬迁不另立第二套拼法。
     */
    val homeTheme: String

    /** 问候里的名字（空 = 只问候，不写名字）。 */
    val greetingName: String

    /** 日期那一行的文案（例如「10月12日 · 星期一」）。 */
    val dateLabel: String

    /** 教学周；0 = 算不出来（只有 [HomeCard.WEEK_NUMBER] 荣列时才画）。 */
    val weekNumber: Int

    /** Hero 上那一行状态（网络环境 + 子系统就绪数）；null = 这一端不判定，不画。 */
    val statusLine: HomeStatusLine? get() = null

    /** 顶部公告条的内容（空 = 不画）。 */
    val bulletins: List<Bulletin> get() = emptyList()

    /** 「下一项安排」的内容；null = 这一端算不出来（不画）。 */
    val nextUp: HomeNextUp? get() = null

    /** 速览格的内容（空 = 不画这一排）。 */
    val glances: List<HomeGlanceItem> get() = emptyList()

    /** 常用入口（空 = 不画这一排）。 */
    val quickRoutes: List<AppRoute> get() = emptyList()

    /** 各功能此刻的状态表（键 = [AppRoute.id]；空表 = 只有入口、没有状态）。 */
    val stats: Map<String, HomeStat> get() = emptyMap()

    /** 「待办与消息」那一格；null = 这一端不画（`:app` 的首页不画它，消息角标在顶栏）。 */
    val inbox: HomeInboxSummary? get() = null

    /**
     * 这一端**真能打开**的路由 id；`null` = 不筛（每一项都能打开）。
     *
     * 宫格按它过滤：打不开的入口不画，而不是画一个点了落占位页的格子
     *（见 `HomeCards.groups` 的 `onlyRouteIds`）。
     */
    val openableRouteIds: Set<String>? get() = null

    /**
     * Hero 右上角那张插画（各端自己的资源）；null = 不画。
     *
     * 是 `BoxScope` 的 lambda：各端要把它摆在自己挑的角上（`:app` 那张校区图在右上角、
     * 给文字列让开 104dp），位置属于画法，所以连「摆哪儿」一起交给宿主。
     */
    val heroArt: (@Composable BoxScope.() -> Unit)? get() = null

    /**
     * 打开某个入口。落到哪一屏由各端的外壳决定（`:app` 上「日程」是切 tab，不是压栈）。
     *
     * 传进来的路由都是 `AppRoute` 里真有的那一批 —— 「我的」不在其中（它是 `:app` 的一个
     * tab，不是路由），那一个走 [onProfile]。
     */
    fun onRoute(route: AppRoute) {}

    /**
     * 「我的」那一屏 —— `:app` 是切到「我的」tab，桌面端没有那个 tab（最接近的是账号管理，
     * 但那是「换账号」不是「我」）。只有「没登录时 Hero 那句话」会走到这里。
     */
    fun onProfile() {}
    /** 点一下公告条里的某条公告。 */
    fun onBulletinTap(bulletin: Bulletin) {}

    /** 关掉公告条里的某条公告。 */
    fun onBulletinDismiss(bulletin: Bulletin) {}

    /** 点开「子系统连接状态」的明细（那一层弹窗是各端自己的画法，所以是槽位）。 */
    fun onOpenStatus() {}
}
