package com.xjtu.toolbox.home

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.inbox.InboxData
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.nav.AppRoute

/** 首页一张卡里的一行：一个功能入口 + 它此刻的状态（拿不到状态时为 `null`）。 */
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

/** 首页「待办与消息」那一格的聚合结果（`:app` 那套摘要在桌面没有，见 [HomeCards.inboxSummary]）。 */
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

/** 首页的卡片模型 + 聚合。 */
object HomeCards {

    /** 首页的分类卡。 */
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

    /** 某个功能此刻的状态。 */
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

    /** 「待办与消息」那一格：待办条数 / 未读消息组数 / 最该先办的那条待办的标题。 */
    fun inboxSummary(data: InboxData, now: Long): HomeInboxSummary {
        val todos = InboxRules.todos(data, now)
        return HomeInboxSummary(
            todoCount = todos.size,
            unreadCount = InboxRules.groups(data, now).count { it.unread },
            nextTodo = todos.firstOrNull()?.title?.takeIf { t -> t.isNotBlank() },
        )
    }
}
