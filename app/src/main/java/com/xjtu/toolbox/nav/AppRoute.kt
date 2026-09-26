package com.xjtu.toolbox.nav

import com.xjtu.toolbox.Routes
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * miuix-nav 的返回栈里放的是这些对象，不是路由字符串。
 *
 * [Routes] 里的字符串仍然是各处认的稳定 ID：服务表、首页统计键、登录拦截、深链、
 * 桌面快捷方式和小组件都存的是它，有些已经写进了用户的本地数据，所以不能删。
 * 这里只在导航这一层把字符串转成对象（[appRouteOf]），需要字符串时再转回去（[id]）。
 *
 * 规矩（miuix-nav 文档「Save and restore」一节）：
 * - 每一个都必须 `@Serializable`：返回栈跟着 rememberSaveable 存盘，漏一个就在切到后台时崩；
 * - 必须是 `data object` / `data class`：每页的可保存状态以 `toString()` 为键，
 *   默认那种带对象地址的 toString 在进程被杀后会变，状态就悄悄丢了。
 *
 * 付款码不在这里：它原来是一个对话框目的地，盖在当前页上面，
 * 换成 miuix-nav 以后改由导航层外面单独显示，见 [AppNavigator.navigate]。
 */
@Serializable
sealed interface AppRoute : NavKey {
    /** 对应的路由字符串，和改造前 navigate() 收到的字符串完全一致。 */
    val id: String

    @Serializable data object Main : AppRoute { override val id get() = Routes.MAIN }
    @Serializable data object EmptyRoom : AppRoute { override val id get() = Routes.EMPTY_ROOM }
    @Serializable data object Notification : AppRoute { override val id get() = Routes.NOTIFICATION }
    @Serializable data object Attendance : AppRoute { override val id get() = Routes.ATTENDANCE }

    /** 日程是底栏 tab，这一条只是给旧入口兜底：进来就立刻切到日程 tab。 */
    @Serializable data object Schedule : AppRoute { override val id get() = Routes.SCHEDULE }
    @Serializable data object Judge : AppRoute { override val id get() = Routes.JUDGE }
    @Serializable data object JwappScore : AppRoute { override val id get() = Routes.JWAPP_SCORE }
    @Serializable data object Library : AppRoute { override val id get() = Routes.LIBRARY }
    @Serializable data object CampusCard : AppRoute { override val id get() = Routes.CAMPUS_CARD }
    @Serializable data object Coupon : AppRoute { override val id get() = Routes.COUPON }
    @Serializable data object ScoreReport : AppRoute { override val id get() = Routes.SCORE_REPORT }
    @Serializable data object Transcript : AppRoute { override val id get() = Routes.TRANSCRIPT }
    @Serializable data object Venue : AppRoute { override val id get() = Routes.VENUE }
    @Serializable data object DownloadManager : AppRoute { override val id get() = Routes.DOWNLOAD_MANAGER }

    /** 思源学堂；带 courseId 时直接落到那门课。 */
    @Serializable data class Lms(val courseId: Int? = null) : AppRoute {
        override val id get() = courseId?.let { Routes.lmsCourse(it) } ?: Routes.LMS
    }
    @Serializable data object Jiaocai : AppRoute { override val id get() = Routes.JIAOCAI }
    @Serializable data object Jiaocai1 : AppRoute { override val id get() = Routes.JIAOCAI1 }
    @Serializable data class Jiaocai1Reader(val ssno: String, val title: String = "") : AppRoute {
        override val id get() = Routes.jiaocai1Reader(ssno, title)
    }
    @Serializable data object SchoolCourse : AppRoute { override val id get() = Routes.SCHOOL_COURSE }
    @Serializable data object SchoolCalendar : AppRoute { override val id get() = Routes.SCHOOL_CALENDAR }
    @Serializable data object YellowPage : AppRoute { override val id get() = Routes.YELLOW_PAGE }
    @Serializable data object Fitness : AppRoute { override val id get() = Routes.FITNESS }
    @Serializable data object Iclassface : AppRoute { override val id get() = Routes.ICLASSFACE }
    @Serializable data class Browser(val url: String = "") : AppRoute {
        override val id get() = Routes.browser(url)
    }
    @Serializable data object Settings : AppRoute { override val id get() = Routes.SETTINGS }
    @Serializable data object Feedback : AppRoute { override val id get() = Routes.FEEDBACK }
    @Serializable data object Community : AppRoute { override val id get() = Routes.COMMUNITY }
    @Serializable data object Faculty : AppRoute { override val id get() = Routes.FACULTY }
    @Serializable data object Games : AppRoute { override val id get() = Routes.GAMES }
    @Serializable data object Game2048 : AppRoute { override val id get() = Routes.GAME_2048 }
    @Serializable data object GameMerge : AppRoute { override val id get() = Routes.GAME_MERGE }
    @Serializable data object GameGomoku : AppRoute { override val id get() = Routes.GAME_GOMOKU }
    @Serializable data object GameGo : AppRoute { override val id get() = Routes.GAME_GO }
    @Serializable data object GameXiangqi : AppRoute { override val id get() = Routes.GAME_XIANGQI }
    @Serializable data object Match : AppRoute { override val id get() = Routes.MATCH }
    @Serializable data object Accounts : AppRoute { override val id get() = Routes.ACCOUNTS }
    @Serializable data object WebVpnConverter : AppRoute { override val id get() = Routes.WEBVPN_CONVERTER }
}

/** 不带参数的路由：字符串和对象一一对应。 */
private val simpleRoutes: Map<String, AppRoute> = listOf(
    AppRoute.Main, AppRoute.EmptyRoom, AppRoute.Notification, AppRoute.Attendance,
    AppRoute.Schedule, AppRoute.Judge, AppRoute.JwappScore, AppRoute.Library,
    AppRoute.CampusCard, AppRoute.Coupon, AppRoute.ScoreReport, AppRoute.Transcript,
    AppRoute.Venue, AppRoute.DownloadManager, AppRoute.Jiaocai, AppRoute.Jiaocai1,
    AppRoute.SchoolCourse, AppRoute.SchoolCalendar, AppRoute.YellowPage, AppRoute.Fitness,
    AppRoute.Iclassface, AppRoute.Settings, AppRoute.Feedback, AppRoute.Community, AppRoute.Faculty,
    AppRoute.Games, AppRoute.Game2048, AppRoute.GameMerge, AppRoute.GameGomoku,
    AppRoute.GameGo, AppRoute.GameXiangqi, AppRoute.Match, AppRoute.Accounts,
    AppRoute.WebVpnConverter,
).associateBy { it.id }

/**
 * 把路由字符串解析成 [AppRoute]。解析不了返回 null，调用方负责兜底（写日志、退回首页），不能闪退。
 *
 * 带参数的三种写法必须认：深链、桌面快捷方式、通知存的都是这种字符串。
 * - `lms` / `lms?courseId=123`
 * - `browser?url=<URL 编码>`（也认不带参数的 `browser`）
 * - `jiaocai1_reader/<ssno>?title=<URL 编码>`
 *
 * 参数值是 URL 编码的，这里解码后放进对象，[AppRoute.id] 再编码回去，两边对称。
 */
fun appRouteOf(id: String): AppRoute? {
    simpleRoutes[id]?.let { return it }
    val path = id.substringBefore('?')
    val query = id.substringAfter('?', missingDelimiterValue = "")
    fun param(name: String): String? = query.split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.let { raw -> runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) }
    return when {
        path == Routes.LMS -> AppRoute.Lms(param("courseId")?.toIntOrNull())
        path == "browser" -> AppRoute.Browser(param("url").orEmpty())
        path.startsWith("jiaocai1_reader/") -> {
            val ssno = path.removePrefix("jiaocai1_reader/")
            if (ssno.isBlank()) null else AppRoute.Jiaocai1Reader(ssno, param("title").orEmpty())
        }
        else -> null
    }
}
