package com.xjtu.toolbox.nav

import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.XJTULogin
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 应用里所有能去的地方，全应用唯一的一套路由。打开一律走 [com.xjtu.toolbox.main.AppRouter.open]。
 *
 * [id] 已写进用户数据（服务表、首页统计、深链、快捷方式、小组件、通知），**不能改**；
 * 外面来的字符串用 [appRouteOf] 解析。
 *
 * miuix-nav 要求：每个都 `@Serializable`（返回栈要存盘），且是 `data object/class`
 * （页面状态以 toString 为键）。属性都写成 getter，不占序列化字段。
 */
@Serializable
sealed interface AppRoute : NavKey {
    val id: String

    /** 进入前要先登录的站点。null = 无需登录可直达。 */
    val loginType: LoginType? get() = null

    /** 不用登录、但没网打开也没用的页面（纯网络功能）。 */
    val needsNetwork: Boolean get() = false

    /** 有本地缓存，断网或登录失败时仍可打开。 */
    val offlineCapable: Boolean get() = false

    @Serializable data object Main : AppRoute { override val id get() = "main" }

    // ── 底栏 tab：不进返回栈，导航层把它们转成「切到那个 tab」 ──

    @Serializable data object Schedule : AppRoute {
        override val id get() = "schedule"
        override val loginType get() = LoginType.JWXT
        override val offlineCapable get() = true
    }

    /** 屁岱 tab，没网也能进。 */
    @Serializable data object Agent : AppRoute { override val id get() = "agent" }

    // ── 覆盖层：付款码盖在当前页上面，不进返回栈 ──

    @Serializable data object PaymentCode : AppRoute {
        override val id get() = "payment_code"
        override val loginType get() = LoginType.CAMPUS_CARD
    }

    // ── 普通页面 ──

    @Serializable data object EmptyRoom : AppRoute {
        override val id get() = "empty_room"
        // 按数据源不同要登的站点不同，由页面自己登
        override val needsNetwork get() = true
    }
    @Serializable data object Notification : AppRoute {
        override val id get() = "notification"
        override val needsNetwork get() = true
    }
    @Serializable data object Attendance : AppRoute {
        override val id get() = "new_attendance"
        override val loginType get() = LoginType.ATTENDANCE
    }
    @Serializable data object Judge : AppRoute {
        override val id get() = "judge"
        // 研究生评教走 gste + gmis，由页面自己登
        override val loginType get() = if (isPostgraduateSession()) null else LoginType.JWXT
    }
    @Serializable data object JwappScore : AppRoute {
        override val id get() = "jwapp_score"
        override val loginType get() = LoginType.JWAPP
        override val offlineCapable get() = true
    }
    @Serializable data object Library : AppRoute {
        override val id get() = "library"
        override val loginType get() = LoginType.LIBRARY
    }
    @Serializable data object CampusCard : AppRoute {
        override val id get() = "campus_card"
        override val loginType get() = LoginType.CAMPUS_CARD
    }
    @Serializable data object Coupon : AppRoute {
        override val id get() = "coupon"
        override val loginType get() = LoginType.COUPON
    }
    @Serializable data object ScoreReport : AppRoute {
        override val id get() = "score_report"
        override val loginType get() = LoginType.JWXT
    }
    @Serializable data object Transcript : AppRoute {
        override val id get() = "transcript"
        override val loginType get() = LoginType.DZPZ
    }
    @Serializable data object Venue : AppRoute {
        override val id get() = "venue"
        override val loginType get() = LoginType.VENUE
    }
    @Serializable data object DownloadManager : AppRoute { override val id get() = "download_manager" }

    /** 思源学堂；带 courseId 时直接落到那门课（LMS 自己的课程 ID）。 */
    @Serializable data class Lms(val courseId: Int? = null) : AppRoute {
        override val id get() = if (courseId == null) "lms" else "lms?courseId=$courseId"
        override val loginType get() = LoginType.LMS
    }
    @Serializable data object Jiaocai : AppRoute {
        override val id get() = "jiaocai"
        override val loginType get() = LoginType.JIAOCAI
    }

    /** 教材全文库（jiaocai1.lib），借教材中心的会话。 */
    @Serializable data object Jiaocai1 : AppRoute {
        override val id get() = "jiaocai1"
        override val loginType get() = LoginType.JIAOCAI
    }
    @Serializable data class Jiaocai1Reader(val ssno: String, val title: String = "") : AppRoute {
        override val id get() = "jiaocai1_reader/$ssno?title=${encode(title)}"
        override val loginType get() = LoginType.JIAOCAI
    }
    @Serializable data object SchoolCourse : AppRoute {
        override val id get() = "school_course"
        override val loginType get() = LoginType.JWXT
    }
    @Serializable data object SchoolCalendar : AppRoute { override val id get() = "school_calendar" }
    @Serializable data object YellowPage : AppRoute {
        override val id get() = "yellow_page"
        override val needsNetwork get() = true
    }
    @Serializable data object Fitness : AppRoute {
        override val id get() = "fitness"
        override val loginType get() = LoginType.FITNESS
    }
    @Serializable data object Iclassface : AppRoute {
        override val id get() = "iclassface"
        override val loginType get() = LoginType.ICLASSFACE
    }
    @Serializable data class Browser(val url: String = "") : AppRoute {
        override val id get() = "browser?url=${encode(url)}"
    }
    @Serializable data object Settings : AppRoute { override val id get() = "settings" }
    @Serializable data object Feedback : AppRoute { override val id get() = "feedback" }
    @Serializable data object Community : AppRoute { override val id get() = "community" }

    /** 教师主页检索，公开站点。 */
    @Serializable data object Faculty : AppRoute { override val id get() = "faculty" }
    @Serializable data object Accounts : AppRoute { override val id get() = "accounts" }
    @Serializable data object WebVpnConverter : AppRoute { override val id get() = "webvpn_converter" }

    // ── 小游戏：各游戏单独一条路由，全局搜索能直达 ──
    @Serializable data object Games : AppRoute { override val id get() = "games" }
    @Serializable data object Game2048 : AppRoute { override val id get() = "game_2048" }
    @Serializable data object GameMerge : AppRoute { override val id get() = "game_merge" }
    @Serializable data object GameGomoku : AppRoute { override val id get() = "game_gomoku" }
    @Serializable data object GameGo : AppRoute { override val id get() = "game_go" }
    @Serializable data object GameXiangqi : AppRoute { override val id get() = "game_xiangqi" }

    /** 匹配交友，只读本地缓存。ID 是历史遗留的服务表键，不能改。 */
    @Serializable data object Match : AppRoute { override val id get() = "schedule_match" }
}

/** 不带参数的路由：字符串和对象一一对应。 */
private val simpleRoutes: Map<String, AppRoute> = listOf(
    AppRoute.Main, AppRoute.Schedule, AppRoute.Agent, AppRoute.PaymentCode,
    AppRoute.EmptyRoom, AppRoute.Notification, AppRoute.Attendance, AppRoute.Judge,
    AppRoute.JwappScore, AppRoute.Library, AppRoute.CampusCard, AppRoute.Coupon,
    AppRoute.ScoreReport, AppRoute.Transcript, AppRoute.Venue, AppRoute.DownloadManager,
    AppRoute.Jiaocai, AppRoute.Jiaocai1, AppRoute.SchoolCourse, AppRoute.SchoolCalendar,
    AppRoute.YellowPage, AppRoute.Fitness, AppRoute.Iclassface, AppRoute.Settings,
    AppRoute.Feedback, AppRoute.Community, AppRoute.Faculty, AppRoute.Accounts,
    AppRoute.WebVpnConverter, AppRoute.Games, AppRoute.Game2048, AppRoute.GameMerge,
    AppRoute.GameGomoku, AppRoute.GameGo, AppRoute.GameXiangqi, AppRoute.Match,
).associateBy { it.id }

/**
 * 把 [AppRoute.id] 解析回路由；认不出返回 null（可能是旧版本存下的快捷方式），调用方别闪退。
 * 带参数的：`lms?courseId=`、`browser?url=`、`jiaocai1_reader/<ssno>?title=`，参数 URL 编码。
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
        path == "lms" -> AppRoute.Lms(param("courseId")?.toIntOrNull())
        path == "browser" -> AppRoute.Browser(param("url").orEmpty())
        path.startsWith("jiaocai1_reader/") -> {
            val ssno = path.removePrefix("jiaocai1_reader/")
            if (ssno.isBlank()) null else AppRoute.Jiaocai1Reader(ssno, param("title").orEmpty())
        }
        else -> null
    }
}

/** 学校系统维护中的页面 → 显示名。命中时入口直接提示，不登录也不跳转（免得批量 401）。 */
val maintenanceRoutes: Map<AppRoute, String> = mapOf()

/** 当前账号是否研究生。 */
fun isPostgraduateSession(): Boolean =
    SessionManager.active?.accountType == XJTULogin.AccountType.POSTGRADUATE

private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
