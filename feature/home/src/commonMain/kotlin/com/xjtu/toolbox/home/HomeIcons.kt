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

/** 首页「哪个功能配哪个图标」的那张表（目录里没有图标字段）。 */
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

    /** 分类卡的图标。 */
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
