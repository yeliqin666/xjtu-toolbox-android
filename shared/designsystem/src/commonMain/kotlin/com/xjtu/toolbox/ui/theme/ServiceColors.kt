package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val R_SCHEDULE = "schedule"
private const val R_EMPTY_ROOM = "empty_room"
private const val R_LMS = "lms"
private const val R_SCHOOL_COURSE = "school_course"
private const val R_NEW_ATTENDANCE = "new_attendance"
private const val R_ICLASSFACE = "iclassface"
private const val R_JWAPP_SCORE = "jwapp_score"
private const val R_JUDGE = "judge"
private const val R_JIAOCAI = "jiaocai"
private const val R_JIAOCAI1 = "jiaocai1"
private const val R_LIBRARY = "library"
private const val R_ZYXF = "zyxf"
private const val R_TRANSCRIPT = "transcript"
private const val R_NOTIFICATION = "notification"
private const val R_FACULTY = "faculty"
private const val R_CAMPUS_CARD = "campus_card"
private const val R_PAYMENT_CODE = "payment_code"
private const val R_COUPON = "coupon"
private const val R_SCHOOL_CALENDAR = "school_calendar"
private const val R_VENUE = "venue"
private const val R_FITNESS = "fitness"
private const val R_YELLOW_PAGE = "yellow_page"
private const val R_WEBVPN_CONVERTER = "webvpn_converter"
private const val R_AGENT = "agent"
private const val R_COMMUNITY = "community"
private const val R_GAMES = "games"
private const val R_MATCH = "schedule_match"

/** 插件公开页的路由 id 前缀（`plugin/<插件>/<页>`、`plugin-web/<插件>`）。 */
private const val PLUGIN_ROUTE_PREFIX = "plugin/"

/** dex 插件页的默认色 = 列表里 dex 徽章那支橙。 */
private val PLUGIN_DEX_COLOR = Color(0xFFF97316)

/** web 插件页的默认色 = 列表里 web 徽章那支蓝。 */
private val PLUGIN_WEB_COLOR = Color(0xFF3B82F6)

/**
 * 插件页自己声明的颜色（清单里的 `pages[].iconColor`，`#RRGGBB`）。
 * 解析不了就返回 null，调用方退回默认色——清单写错不该让首页掉一个格子。
 */
fun serviceColorOverride(hex: String): Color? {
    val text = hex.trim().removePrefix("#")
    if (text.length != 6) return null
    val rgb = text.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

/**
 * 功能入口的固定配色。
 * [通知公告] 保留主题 error 色以保证警示语义。
 *
 * 插件公开页不在目录里，路由 id 是运行时拼出来的，所以先按前缀判一次。
 */
@Composable
fun serviceColor(key: String): Color = when {
    key.startsWith("plugin-web/") -> PLUGIN_WEB_COLOR
    key.startsWith(PLUGIN_ROUTE_PREFIX) -> PLUGIN_DEX_COLOR
    else -> builtinColor(key)
}

@Composable
private fun builtinColor(key: String): Color = when (key) {
    // 上课
    R_SCHEDULE -> Color(0xFF315FD4)
    R_EMPTY_ROOM -> Color(0xFF283593)
    R_LMS -> Color(0xFF1565C0)
    R_SCHOOL_COURSE -> Color(0xFF00838F)
    R_NEW_ATTENDANCE -> Color(0xFF37474F)
    R_ICLASSFACE -> Color(0xFF00695C)
    // 学业
    R_JWAPP_SCORE -> Color(0xFF7B1FA2)
    R_JUDGE -> Color(0xFF6A1B9A)
    R_JIAOCAI -> Color(0xFFAD1457)
    R_JIAOCAI1 -> Color(0xFF880E4F)
    R_LIBRARY -> Color(0xFFE65100)
    R_ZYXF -> Color(0xFF5D4037)
    R_TRANSCRIPT -> Color(0xFF283593)
    R_NOTIFICATION -> MiuixTheme.colorScheme.error
    R_FACULTY -> Color(0xFF455A64)
    // 校园生活
    R_CAMPUS_CARD -> Color(0xFF2E7D32)
    R_PAYMENT_CODE -> Color(0xFF00796B)
    R_COUPON -> Color(0xFFF9A825)
    R_SCHOOL_CALENDAR -> Color(0xFF00796B)
    R_VENUE -> Color(0xFF00838F)
    R_FITNESS -> Color(0xFF00897B)
    R_YELLOW_PAGE -> Color(0xFF1565C0)
    // 工具与助手
    R_WEBVPN_CONVERTER -> Color(0xFF4E342E)
    R_AGENT -> Color(0xFF00695C)
    R_COMMUNITY -> Color(0xFFF9A825)
    // 课余
    R_GAMES -> Color(0xFFEF6C00)
    R_MATCH -> Color(0xFFD81B60)
    else -> Color(0xFF757575)
}
