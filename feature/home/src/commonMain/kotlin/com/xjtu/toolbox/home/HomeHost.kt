package com.xjtu.toolbox.home

import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.nav.AppRoute
import kotlinx.datetime.LocalDateTime

/** 首页上的每一块：各端声明自己那一份的词汇表。 */
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

/** 页面上的深浅一档：正常（绿）/ 主题色加重（蓝）/ 警示（红）/ 中性灰。 */
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

/** 「下一项安排」那一格的内容。 */
data class HomeNextUp(
    val icon: ImageVector,
    val title: String,
    val timeLabel: String? = null,
    val timeCaption: String? = null,
    val eta: String? = null,
    /** 这一项的开始时刻（`:app` 的提醒评估用它）。 */
    val startAt: LocalDateTime? = null,
    val detail: String? = null,
    val route: AppRoute? = null,
)

/** 首页的宿主槽位 —— 「这一端有什么」，而不是「这一端是不是 Android」。 */
interface HomeHost {

    /** 这一端**真会照着办**的那些块（见 [HomeCard]）。 */
    val honoredCards: Set<HomeCard>

    /**
     * 首页底部那一句「这一端首页缺了哪几块」的汇总说明（`null` = 不画，也是默认值 ⇒ 不填的端行为不变）。
     *
     * 块与块之间在屏上并不相邻，逐条贴说明会变成满屏小字；首页的缺口本来就该一句话说完：
     * 缺哪几块 / 为什么缺 / 想看那几块去哪儿。
     */
    val gapNote: String? get() = null

    /** 这一端当前身份。目录（[AppServices]）按它筛：研究生看不到成绩单那几项。 */
    val audience: AccountType

    /** 问候里的名字（空 = 只问候，不写名字）。 */
    val greetingName: String

    /** 日期那一行的文案（例如「10月12日 星期一」）。 */
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

    /** 常用入口能否在首页上编辑（排序、增删）；能的端在 [onQuickRoutesChange] 里落盘。 */
    val quickEditable: Boolean get() = false
    fun onQuickRoutesChange(routes: List<AppRoute>) {}

    /** 各功能此刻的状态表（键 = [AppRoute.id]；空表 = 只有入口、没有状态）。 */
    val stats: Map<String, HomeStat> get() = emptyMap()

    /** 「待办与消息」那一格；null = 这一端不画（`:app` 的首页不画它，消息角标在顶栏）。 */
    val inbox: HomeInboxSummary? get() = null

    /** 这一端真能打开的路由 id；`null` = 不筛（每一项都能打开）。 */
    val openableRouteIds: Set<String>? get() = null

    /** 打开某个入口。 */
    fun onRoute(route: AppRoute) {}

    /** 「我的」那一屏。 */
    fun onProfile() {}
    /** 点一下公告条里的某条公告。 */
    fun onBulletinTap(bulletin: Bulletin) {}

    /** 关掉公告条里的某条公告。 */
    fun onBulletinDismiss(bulletin: Bulletin) {}

    /** 点开「子系统连接状态」的明细（那一层弹窗是各端自己的画法，所以是槽位）。 */
    fun onOpenStatus() {}
}
