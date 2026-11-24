package com.xjtu.toolbox.web

import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.nav.AppRoute
import kotlin.reflect.KClass

/**
 * 「本端真能打开的路由」—— 全 `:web` 唯一一处这样的清单（首页宫格、学辅目录、`AppPage` 的兜底都按它）。
 *
 * 加一屏：`AppPage` 加分支 + 这里的 `openableRouteTypes` 加一条。
 * 按路由类型判定，帖子号、课程号等参数不影响这一端是否支持该屏。
 *
 * ## 什么不在清单里
 *
 * - `Browser`：有分支但不是一屏，直接 `navigateSameTab` 交给浏览器。只认 `url`、丢掉 `then`：
 *   第二跳要内置浏览器才做得到，用户登录完自行继续（与桌面 `desktopBrowserTarget` 同一口径）；
 * - `Zyxf`：资料站依赖 Android 端的下载/预览/文件能力，本端没有对应屏，落 `NotPortedScreen`；
 * - `NoticeDetail`：公告详情在 App 里是独立一屏（还带一镜到底），web 端在列表里内联展开，没有这一屏；
 * - `QrScan`：扫一扫是 Android 顶栏的相机浮层（金标联盟一镜到底的独立宿主），本端没有这一屏。
 */
internal object WebSupport {

    /** 本端 `AppPage` 里落到真屏的那些路由（见文件头：加一条屏要同时改哪两处）。 */
    val openableRouteTypes: Set<KClass<out AppRoute>> = setOf(
        // 首页：`:core` 的 `HomeScreen` + 本端 `WebHomeHost`（就是画这份宫格的那一屏本身）
        AppRoute.Main::class,
        AppRoute.Schedule::class,
        // 下面这几条就是被上一轮关在门后的那批（它们的屏一直在，门没了）
        AppRoute.SchoolCalendar::class,
        AppRoute.Fitness::class,
        AppRoute.YellowPage::class,
        AppRoute.CampusBus::class,
        AppRoute.Notification::class,
        AppRoute.Faculty::class,
        AppRoute.SchoolCourse::class,
        AppRoute.Judge::class,
        AppRoute.Inbox::class,
        AppRoute.EmptyRoom::class,
        AppRoute.CampusCard::class,
        AppRoute.Venue::class,
        AppRoute.Library::class,
        AppRoute.Community::class,
        AppRoute.CommunityPost::class,
        AppRoute.Feedback::class,
        AppRoute.Settings::class,
        AppRoute.Transcript::class,
        AppRoute.Attendance::class,
        AppRoute.Coupon::class,
        // 第二批只读域：思源学堂 / 教材查询 / 成绩 / 快速考勤流水。
        // 与上面那四条同一条规矩：**「本端有没有这一屏」与「此刻后端是哪一形态」是两件事** ——
        // 取数只在 serve 形态下存在（campus-api 里没有这四族的端点），但那四屏就在 `:core`，
        // 所以门照旧列在这里，campus-api 形态下 `AppPage` 画的是「这一屏要 serve 模式」那张如实页。
        //
        // `Lms` 的课程 / 活动深链同样支持；宫格是否展示入口仍由 AppServices 目录决定。
        AppRoute.Lms::class,
        AppRoute.Jiaocai::class,
        // 教材全文库与阅读器：宿主走 `/api/jiaocai1/*`，书架落在 serve 侧（见 `WebJiaocai1Source`）。
        // 阅读器的门在书架与检索结果里，目录里没有它。
        AppRoute.Jiaocai1::class,
        AppRoute.Jiaocai1Reader::class,
        AppRoute.JwappScore::class,
        AppRoute.Iclassface::class,
        // 学业进度：取数走 `/api/degree/*`，campus-api 形态下画「要 serve 模式」那张页。
        AppRoute.DegreeProgress::class,
        AppRoute.PaymentCode::class,
        AppRoute.Match::class,
        AppRoute.WebVpnConverter::class,
        // 游戏：合集 + 合集里那六屏（它们在目录里只有「小游戏」一条，子路由由合集屏自己往下走）
        AppRoute.Games::class,
        AppRoute.Game2048::class,
        AppRoute.GameBlocks::class,
        AppRoute.GameHop::class,
        AppRoute.GameGomoku::class,
        AppRoute.GameGo::class,
        AppRoute.GameXiangqi::class,
        AppRoute.GameMerge::class,
        // 账号管理：**屏就是 `:core` 那份 `AccountManagerScreen`**（三端同一套层级），走 serve 的
        // `/api/accounts`。切换 / 新增 / 删除 / 改密要求 HTTPS；明文 HTTP 下只读，页顶那一行会说明（见 `WebAccountsHost`）。
        AppRoute.Accounts::class,
        AppRoute.Agent::class,
        // 下载管理：宿主走 `/api/downloads`，队列与落盘在 serve 那台机器上（见 `WebDownloadsHost`）。
        AppRoute.DownloadManager::class,
    )

    /**
     * 只有 serve 契约才有取数的那些：campus-api 形态下 `AppPage` 画「当前服务器不支持」，
     * 所以首页宫格与学辅目录在那一形态里不列它们（深链照样能进，看到的是那张说明页）。
     */
    val serveOnlyRouteTypes: Set<KClass<out AppRoute>> = setOf(
        AppRoute.Agent::class,
        AppRoute.CampusBus::class,
        AppRoute.Transcript::class,
        AppRoute.Attendance::class,
        AppRoute.Coupon::class,
        AppRoute.Lms::class,
        AppRoute.Jiaocai::class,
        AppRoute.Jiaocai1::class,
        AppRoute.Jiaocai1Reader::class,
        AppRoute.JwappScore::class,
        AppRoute.DegreeProgress::class,
        AppRoute.Iclassface::class,
        AppRoute.PaymentCode::class,
        AppRoute.WebVpnConverter::class,
        AppRoute.Feedback::class,
        AppRoute.Accounts::class,
        AppRoute.DownloadManager::class,
    )

    /** 这一端真能打开 [route] 吗（见文件头：清单与 `AppPage` 的分支必须同时改）。 */
    fun isOpenable(route: AppRoute): Boolean = route::class in openableRouteTypes

    /** 在 [serveMode] 这一形态下 [route] 有没有取数（没有就不列入口）。 */
    fun isUsable(route: AppRoute, serveMode: Boolean): Boolean =
        isOpenable(route) && (serveMode || route::class !in serveOnlyRouteTypes)

    /** 服务目录里真能点进去的那些（学辅目录的可点行 + `?route=probe` 自检屏的第一张表）。 */
    val openableCatalog: List<AppService> = AppServices.all.filter { isOpenable(it.route) }

    /** 这一形态下可点的目录。 */
    fun usableCatalog(serveMode: Boolean): List<AppService> =
        AppServices.all.filter { isUsable(it.route, serveMode) }

    /** 首页按服务入口 id 过滤；从支持类型推导，不为帖子等深链伪造固定参数。 */
    val openableRouteIds: Set<String> = openableCatalog.mapTo(mutableSetOf()) { it.route.id }

    /** 这一形态下首页能列的入口 id。 */
    fun usableRouteIds(serveMode: Boolean): Set<String> =
        usableCatalog(serveMode).mapTo(mutableSetOf()) { it.route.id }

    /** 目录里有、本端**还没有屏**的那些（同一份推导的另一半，见文件头末段）。 */
    val unsupportedCatalog: List<AppService> = AppServices.all.filterNot { isOpenable(it.route) }

    /** 这一形态下用不了的目录（没有屏的 + campus-api 下只有 serve 才有取数的）。 */
    fun unusableCatalog(serveMode: Boolean): List<AppService> =
        AppServices.all.filterNot { isUsable(it.route, serveMode) }
}
