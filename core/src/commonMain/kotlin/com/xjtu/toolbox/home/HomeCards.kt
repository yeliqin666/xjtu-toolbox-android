package com.xjtu.toolbox.home

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.inbox.InboxData
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.nav.AppRoute

/**
 * 首页**一张卡里的一行**：一个功能入口 + 它此刻的状态（拿不到状态时为 `null`）。
 *
 * [service] 是目录里那一条（`AppServices`），所以标题、分类、路由 id 都只有一个来源；
 * 图标与配色是各端的事（`:app` 那套 Material 图标表留在它自己的屏里）。
 */
data class HomeCardRow(
    val service: AppService,
    val stat: String? = null,
    val statDetail: String? = null,
) {
    /** 稳定键 = [AppRoute.id]：使用频率、首页摘要、桌面可开路由表都按它对齐。 */
    val key: String get() = service.route.id
}

/** 首页的一张分类卡：[category] 那一类下的全部行（顺序就是目录里的顺序）。 */
data class HomeCardGroup(val category: ServiceCategory, val rows: List<HomeCardRow>)

/**
 * 首页「待办与消息」那一格的聚合结果（`:app` 那套摘要在桌面没有，见 [HomeCards.inboxSummary]）。
 */
data class HomeInboxSummary(
    /** 还没办的待办条数。 */
    val todoCount: Int,
    /** 未读的消息组数。 */
    val unreadCount: Int,
    /** 最该先办的那条待办的标题（`InboxRules.todos` 的排序：快截止的在前；没有待办时为 null）。 */
    val nextTodo: String?,
) {
    val isEmpty: Boolean get() = todoCount == 0 && unreadCount == 0
}

/**
 * 首页的**卡片模型 + 聚合**（从 `:app` 的 `main/HomeTab.kt` 里那两段内联推导原样搬来）。
 *
 * ## 为什么它在 `:core`
 *
 * 首页画出来的东西 = 「服务目录（[AppServices]）× 每个功能此刻的状态（[HomeStat]）」。
 * 两半都是纯数据，`:app` 那边的屏只负责把它们摆成 Miuix 的样子。所以分组的判据、状态的取法、
 * 以及那几处「一个格子由两个功能拼出来」的特例，一次写清楚、各端同一份 ——
 * 另一端的首页只要交出 [HomeStat] 那一张表（或声明自己交不出），出来的格子就该是一样的。
 *
 * 搬进来时**一行判定都没改**，只有两处形态调整：
 * - 原来内联在屏里的是 `Pair<ServiceCategory, List<MoreSvc>>`，这里换成
 *   [HomeCardGroup] / [HomeCardRow]（`MoreSvc` 带着图标与点击行为，那是屏的事）；
 * - 原来 `statOf` 返回 `Pair<String, String?>?`，这里返回 `HomeStat?`（同一个值对象）。
 *
 * ## 各端「哪些入口真能打开」由宿主声明
 *
 * [groups] 的 [onlyRouteIds] 就是这个：`:app` 传 `null`（它每一项都能打开），桌面传自己那张
 * 真数据路由表 —— 打不开的入口**不画**（与「点了会失败的按钮一个都不画」同一条纪律）。
 */
object HomeCards {

    /**
     * 首页的分类卡。[stats] 是各功能此刻的状态表（键 = [AppRoute.id]）。
     *
     * 空分类不下发（与搬迁前那句 `if (items.isEmpty()) null` 逐字对应）：目录里某一类暂时
     * 一条都不给当前身份看时，那一张卡不该以空壳的形式出现。
     */
    fun groups(
        accountType: AccountType,
        stats: Map<String, HomeStat>,
        onlyRouteIds: Set<String>? = null,
    ): List<HomeCardGroup> {
        val services = AppServices.homeFor(accountType)
            .filter { onlyRouteIds == null || it.route.id in onlyRouteIds }
        return ServiceCategory.entries.mapNotNull { category ->
            val items = services.filter { it.category == category }
            if (items.isEmpty()) {
                null
            } else {
                HomeCardGroup(
                    category = category,
                    rows = items.map { svc ->
                        val stat = statOf(svc.route.id, stats)
                        HomeCardRow(service = svc, stat = stat?.value, statDetail = stat?.detail)
                    },
                )
            }
        }
    }

    /**
     * 某个功能此刻的状态。三处特例与搬迁前逐字一致：
     *
     * - 校园卡与校历**这一格不填**：它俩的数在 Hero（余额格 / 教学周）里，分类卡再写一遍是重复；
     * - 考勤那一格会带上「今日人脸刷卡」：快速考勤流水不再单列在首页，今天刷过卡就借考勤这格露出来。
     *   判据是「刷过卡」而不是「有记录」——`今日未刷卡` 是那条记录本身在说自己没刷；
     * - 其余就是查表：用过并有摘要的功能才有状态，没有就退回纯入口（不显示占位符）。
     */
    fun statOf(key: String, stats: Map<String, HomeStat>): HomeStat? = when (key) {
        AppRoute.CampusCard.id, AppRoute.SchoolCalendar.id -> null
        AppRoute.Attendance.id -> stats[AppRoute.Attendance.id]?.let { att ->
            val punch = stats[AppRoute.Iclassface.id]
            val detail = if (punch != null && punch.value != "今日未刷卡") {
                "今日已刷 ${punch.value}" + (punch.detail?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            } else {
                att.detail
            }
            HomeStat(att.value, detail)
        }
        else -> stats[key]
    }

    /**
     * 「待办与消息」那一格：待办条数 / 未读消息组数 / 最该先办的那条待办的标题。
     *
     * 为什么这一段在 `:core` 而不在某一端的屏里：它算的就是 `:core` 收纳层那两条规则
     * （`InboxRules.todos` 与 `InboxRules.groups`）—— 待办「办完了没有、过没过期、忽略过没有」
     * 的判据本来就只有一份，屏上再抄一遍迟早会与收纳页对不上。
     *
     * `:app` 的首页不读它（那边各功能页把摘要推给首页，见 [HomeStat]）；桌面端没有那份
     * 「谁有数据谁负责写」的落盘摘要（它的缓存层属设计文档 §5.1），所以由首页自己算这一格。
     */
    fun inboxSummary(data: InboxData, now: Long): HomeInboxSummary {
        val todos = InboxRules.todos(data, now)
        return HomeInboxSummary(
            todoCount = todos.size,
            unreadCount = InboxRules.groups(data, now).count { it.unread },
            nextTodo = todos.firstOrNull()?.title?.takeIf { t -> t.isNotBlank() },
        )
    }
}
