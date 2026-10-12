package com.xjtu.toolbox.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector
import com.xjtu.toolbox.nav.AppRoute

/**
 * 首页「哪个功能配哪个图标」的那张表（从 `:app` 的 `main/HomeTab.kt` 原样搬来）：
 * 目录里没有图标字段 —— 图标是画出来的东西，而这张表由图标的**语义**决定（考勤用打勾、
 * 成绩用报表、电费用闪电…），所以它属于「卡片模型」这一层，而不是某一端的主题。
 *
 * 搬走之后两头共用：`:app` 的首页宫格与 `:core` 的共享首页 [HomeScreen] 都读它，
 * 于是「同一个功能在两端是不是同一个图标」不再靠人眼对齐。查不到的退回 `Icons.Default.Apps`
 * （与搬迁前 `homeIcons[route] ?: Icons.Default.Apps` 逐字对应）。
 *
 * [category] / [accentKey] 是同一次搬迁里跟着过来的那两个 `when`：分类卡的视觉标识，
 * 以及「这个分类借哪个功能的配色」。
 */
object HomeIcons {

    fun of(route: AppRoute): ImageVector = when (route) {
        AppRoute.Schedule -> Icons.Default.CalendarMonth
        AppRoute.EmptyRoom -> Icons.Default.MeetingRoom
        is AppRoute.Lms -> Icons.Default.School
        AppRoute.SchoolCourse -> Icons.Default.TravelExplore
        AppRoute.Attendance -> Icons.Default.AssignmentTurnedIn
        AppRoute.Iclassface -> Icons.Default.Face
        AppRoute.JwappScore -> Icons.Default.Assessment
        AppRoute.Judge -> Icons.Default.RateReview
        AppRoute.Jiaocai -> Icons.AutoMirrored.Filled.MenuBook
        AppRoute.Jiaocai1 -> Icons.AutoMirrored.Filled.LibraryBooks
        AppRoute.Library -> Icons.Default.Chair
        AppRoute.Transcript -> Icons.Default.Description
        AppRoute.Notification -> Icons.Default.Notifications
        AppRoute.Faculty -> Icons.Default.PersonSearch
        AppRoute.CampusCard -> Icons.Default.CreditCard
        AppRoute.PaymentCode -> Icons.Default.QrCode
        AppRoute.Coupon -> Icons.Default.Restaurant
        AppRoute.SchoolCalendar -> Icons.AutoMirrored.Filled.EventNote
        AppRoute.Venue -> Icons.Default.Stadium
        AppRoute.DormPower -> Icons.Default.Bolt
        AppRoute.Fitness -> Icons.AutoMirrored.Filled.DirectionsRun
        AppRoute.YellowPage -> Icons.Default.ContactPhone
        AppRoute.WebVpnConverter -> Icons.Default.VpnKey
        AppRoute.Agent -> Icons.Default.SmartToy
        AppRoute.Community -> Icons.Default.Forum
        AppRoute.Games -> Icons.Default.SportsEsports
        AppRoute.Match -> Icons.Default.Groups
        else -> Icons.Default.Apps
    }

    /**
     * 分类卡的图标。用 `when` 而不是 `mapOf` + `getValue`（搬迁前那句注释仍在理）：
     * 以后加分类漏写了，编译就不过 —— 不会像「课余」（PLAY）那次一样，到首页渲染分类卡时
     * 才抛 `NoSuchElementException` 闪退。
     */
    fun category(category: ServiceCategory): ImageVector = when (category) {
        ServiceCategory.CLASS -> Icons.Default.School
        ServiceCategory.STUDY -> Icons.Default.Assessment
        ServiceCategory.LIFE -> Icons.Default.Restaurant
        ServiceCategory.TOOL -> Icons.Default.SmartToy
        ServiceCategory.PLAY -> Icons.Default.SportsEsports
    }

    /** 分类借用的配色键（= 该分类里那个代表性功能的 [AppRoute.id]）。 */
    fun accentKey(category: ServiceCategory): String = when (category) {
        ServiceCategory.CLASS -> AppRoute.Schedule
        ServiceCategory.STUDY -> AppRoute.JwappScore
        ServiceCategory.LIFE -> AppRoute.CampusCard
        ServiceCategory.TOOL -> AppRoute.Agent
        ServiceCategory.PLAY -> AppRoute.Games
    }.id
}
