package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页图标主题使用的低饱和彩色盘。
 *
 * @param index 当前服务在扁平列表中的下标（从 0 开始）
 * @param total 扁平列表中服务总数
 */
@Composable
fun serviceColor(index: Int, total: Int): Color {
    val isDark = LocalIsDarkTheme.current
    val lightPalette = listOf(
        Color(0xFF315FD4),
        Color(0xFF00796B),
        Color(0xFF7B1FA2),
        Color(0xFFE65100),
        Color(0xFF2E7D32),
        Color(0xFF1565C0),
        Color(0xFFAD1457),
        Color(0xFF5D6D7E),
    )
    val darkPalette = listOf(
        Color(0xFF82A8FF),
        Color(0xFF6FD6C8),
        Color(0xFFD0A2FF),
        Color(0xFFFFB46B),
        Color(0xFF8FD694),
        Color(0xFF7BB7FF),
        Color(0xFFFF94B4),
        Color(0xFFB4C0CC),
    )
    val palette = if (isDark) darkPalette else lightPalette
    val safeTotal = total.coerceAtLeast(1)
    val offset = index / safeTotal
    return palette[(index + offset) % palette.size]
}

// ══════════════════════════════════════════
//  旧配色（"启用彩虹配色"关闭时使用）
// ══════════════════════════════════════════

private const val R_SCHEDULE = "schedule"
private const val R_EMPTY_ROOM = "empty_room"
private const val R_LMS = "lms"
private const val R_CLASS_REPLAY = "class_replay"
private const val R_SCHOOL_COURSE = "school_course"
private const val R_ATTENDANCE = "attendance"
private const val R_POSTGRADUATE_ATTENDANCE = "postgraduate_attendance"
private const val R_JWAPP_SCORE = "jwapp_score"
private const val R_JUDGE = "judge"
private const val R_JIAOCAI = "jiaocai"
private const val R_LIBRARY = "library"
private const val R_TRANSCRIPT = "transcript"
private const val R_NOTIFICATION = "notification"
private const val R_CAMPUS_CARD = "campus_card"
private const val R_PAYMENT_CODE = "payment_code"
private const val R_COUPON = "coupon"
private const val R_SCHOOL_CALENDAR = "school_calendar"
private const val R_VENUE = "venue"
private const val R_FITNESS = "fitness"
private const val R_YELLOW_PAGE = "yellow_page"
private const val R_WEBVPN_CONVERTER = "webvpn_converter"
private const val R_MOBILE_JIAODA = "mobile_jiaoda"
private const val R_JIAOXIAOZHI = "jiaoxiaozhi"
private const val R_AGENT = "agent"

/**
 * 彩虹关闭时使用的旧硬编码配色。
 * [通知公告] 保留主题 error 色以保证警示语义。
 */
@Composable
fun legacyColor(key: String): Color = when (key) {
    // 上课
    R_SCHEDULE -> Color(0xFF315FD4)
    R_EMPTY_ROOM -> Color(0xFF283593)
    R_LMS -> Color(0xFF1565C0)
    R_CLASS_REPLAY -> Color(0xFF512DA8)
    R_SCHOOL_COURSE -> Color(0xFF00838F)
    R_ATTENDANCE -> Color(0xFF4E342E)
    R_POSTGRADUATE_ATTENDANCE -> Color(0xFF4E342E)
    // 学业
    R_JWAPP_SCORE -> Color(0xFF7B1FA2)
    R_JUDGE -> Color(0xFF6A1B9A)
    R_JIAOCAI -> Color(0xFFAD1457)
    R_LIBRARY -> Color(0xFFE65100)
    R_TRANSCRIPT -> Color(0xFF283593)
    R_NOTIFICATION -> MiuixTheme.colorScheme.error
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
    R_MOBILE_JIAODA -> Color(0xFF005BAC)
    R_JIAOXIAOZHI -> Color(0xFF6750A4)
    R_AGENT -> Color(0xFF00695C)
    else -> Color(0xFF757575)
}
