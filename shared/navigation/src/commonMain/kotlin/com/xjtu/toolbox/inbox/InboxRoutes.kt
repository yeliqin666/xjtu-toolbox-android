package com.xjtu.toolbox.inbox

import com.xjtu.toolbox.nav.AppRoute

/**
 * 从系统通知点进这个页面，就等于看过这一类收纳内容；没有对应的返回 null。
 *
 * 收纳模型在 `:shared:model`，它不依赖 `:shared:navigation`（反过来才对），所以挂成扩展放在这里，
 * 调用处照旧写 `InboxCategories.ofRoute(route)`。
 */
fun InboxCategories.ofRoute(route: AppRoute): String? = when (route) {
    AppRoute.JwappScore -> GRADE
    AppRoute.Schedule -> SCHEDULE
    AppRoute.Attendance -> ATTENDANCE
    AppRoute.Library -> LIBRARY
    AppRoute.Coupon -> COUPON
    AppRoute.Judge -> JUDGE
    is AppRoute.Lms -> LMS
    is AppRoute.Community, is AppRoute.CommunityPost -> COMMUNITY
    else -> null
}
/** 升级前已缓存的作业待办仍可从稳定 ID 恢复活动深链；测试维持原有课程入口。 */
fun InboxItem.resolvedRouteId(): String? {
    if (category != InboxCategories.LMS || !id.startsWith("lms:")) return route
    val keys = id.removePrefix("lms:").split(':')
    if (keys.size != 2) return route
    val course = keys[0].toIntOrNull()?.takeIf { it > 0 } ?: return route
    val activity = keys[1].toIntOrNull()?.takeIf { it > 0 } ?: return route
    return AppRoute.Lms(course, activity).id
}
