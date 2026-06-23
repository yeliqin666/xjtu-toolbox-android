package com.xjtu.toolbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按位置比例从 24 色彩虹色盘中取色。
 *
 * 色相 = index / total * 360°，插入或删除服务后自动重新均分，保持渐变连续。
 *
 * @param index 当前服务在扁平列表中的下标（从 0 开始）
 * @param total 扁平列表中服务总数
 */
@Composable
fun serviceColor(index: Int, total: Int): Color {
    val isDark = LocalIsDarkTheme.current
    val hue = index * 360f / total
    return if (isDark) {
        Color.hsl(hue, 0.50f, 0.70f)
    } else {
        Color.hsl(hue, 0.55f, 0.45f)
    }
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
