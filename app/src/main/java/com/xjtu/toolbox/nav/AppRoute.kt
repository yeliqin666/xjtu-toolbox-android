package com.xjtu.toolbox.nav

import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SessionManager
import com.xjtu.toolbox.auth.XJTULogin
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 应用里所有能去的地方。
 *
 * 全应用只有这一套路由：返回栈里放的是它，页面之间跳转传的是它，首页服务表、统计、
 * 登录拦截认的也是它。字符串只出现在两个边界上——
 * - [id]：稳定 ID。服务表、首页统计键、深链、桌面快捷方式、小组件、通知都存的是它，
 *   有些已经写进了用户的本地数据，所以**一个字都不能改**（例如考勤仍是 `new_attendance`、
 *   匹配交友仍是 `schedule_match`）；
 * - [appRouteOf]：把外面来的字符串（深链、快捷方式、通知、屁岱给的跳转建议）解析回对象。
 *
 * 每个路由自己声明进入前的要求：要登录哪个站点（[loginType]）、没网能不能进
 * （[needsNetwork] / [offlineCapable]）。统一入口见 MainScreen 的 `open(route)`。
 *
 * 规矩（miuix-nav 文档「Save and restore」一节）：
 * - 每一个都必须 `@Serializable`：返回栈跟着 rememberSaveable 存盘，漏一个就在切到后台时崩；
 * - 必须是 `data object` / `data class`：每页的可保存状态以 `toString()` 为键，
 *   默认那种带对象地址的 toString 在进程被杀后会变，状态就悄悄丢了；
 * - [id] 和几个声明都写成 getter，不占序列化字段。
 */
@Serializable
sealed interface AppRoute : NavKey {
    val id: String

    /** 进入前要先登录的站点。null = 无需登录可直达。 */
    val loginType: LoginType? get() = null

    /** 不用登录、但没网打开也没用的页面（纯网络功能）。 */
    val needsNetwork: Boolean get() = false

    /** 有本地缓存、断网或登录失败时仍可以打开的页面。 */
    val offlineCapable: Boolean get() = false

    @Serializable data object Main : AppRoute { override val id get() = "main" }

    // ── 底栏 tab：不进返回栈，导航层把它们转成「切到那个 tab」 ──

    @Serializable data object Schedule : AppRoute {
        override val id get() = "schedule"
        override val loginType get() = LoginType.JWXT
        override val offlineCapable get() = true
    }

    /** 屁岱。0 级 tab，没网也能进（进去看到的是空对话 + 提示）。 */
    @Serializable data object Agent : AppRoute { override val id get() = "agent" }

    // ── 覆盖层：付款码盖在当前页上面，不进返回栈 ──

    @Serializable data object PaymentCode : AppRoute {
        override val id get() = "payment_code"
        override val loginType get() = LoginType.CAMPUS_CARD
    }

    // ── 普通页面 ──

    @Serializable data object EmptyRoom : AppRoute {
        override val id get() = "empty_room"
        // 不在入口登录：默认的实时状态要登智慧教室，直查才要教务，CDN 不用登，由页面按数据源自己登
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
        // 研究生评教走 gste + gmis，由评教页自己按需登录，不在入口先登教务
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

    /** 教材全文库（jiaocai1.lib）。只认 IP、不做 CAS，借教材中心的会话拿它的 OkHttp 客户端。 */
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

    /** 教师主页检索。faculty.xjtu.edu.cn 与 gr.xjtu.edu.cn 都是公开站点，无需登录。 */
    @Serializable data object Faculty : AppRoute { override val id get() = "faculty" }
    @Serializable data object Accounts : AppRoute { override val id get() = "accounts" }
    @Serializable data object WebVpnConverter : AppRoute { override val id get() = "webvpn_converter" }

    // ── 小游戏 ──
    // Games 是合集页，各游戏自己一条路由：合集页只是最常见的入口，
    // 不该是唯一入口——全局搜索搜「五子棋」应该能直接进去，而不是先落到合集页。
    @Serializable data object Games : AppRoute { override val id get() = "games" }
    @Serializable data object Game2048 : AppRoute { override val id get() = "game_2048" }
    @Serializable data object GameMerge : AppRoute { override val id get() = "game_merge" }
    @Serializable data object GameGomoku : AppRoute { override val id get() = "game_gomoku" }
    @Serializable data object GameGo : AppRoute { override val id get() = "game_go" }
    @Serializable data object GameXiangqi : AppRoute { override val id get() = "game_xiangqi" }

    /**
     * 匹配交友。全程读本地缓存，不碰任何校园系统，所以不要求登录。
     * ID 沿用 #72 删掉之前的 "schedule_match"：它是服务表里的键，改掉的话
     * 老用户固定在首页的入口会对不上。
     */
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
 * 把路由字符串解析成 [AppRoute]。解析不了返回 null，调用方负责兜底（写日志、原地不动），不能闪退：
 * 它可能来自旧版本存下的快捷方式或者一条过时的深链。
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
        path == "lms" -> AppRoute.Lms(param("courseId")?.toIntOrNull())
        path == "browser" -> AppRoute.Browser(param("url").orEmpty())
        path.startsWith("jiaocai1_reader/") -> {
            val ssno = path.removePrefix("jiaocai1_reader/")
            if (ssno.isBlank()) null else AppRoute.Jiaocai1Reader(ssno, param("title").orEmpty())
        }
        else -> null
    }
}

/**
 * 学校系统维护中的页面 → 提示里用的名字。命中 → 入口处直接提示，不触发任何登录或界面跳转，
 * 保护账号免遭批量 401。平时为空，出事时往里加一项即可。
 */
val maintenanceRoutes: Map<AppRoute, String> = mapOf()

/** 当前账号是否研究生身份（一网通办判定，见 [com.xjtu.toolbox.auth.AccountType.fromIdentityName]）。 */
fun isPostgraduateSession(): Boolean =
    SessionManager.active?.accountType == XJTULogin.AccountType.POSTGRADUATE

private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
