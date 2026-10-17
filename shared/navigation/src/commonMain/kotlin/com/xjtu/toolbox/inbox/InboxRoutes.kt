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
    else -> null
}