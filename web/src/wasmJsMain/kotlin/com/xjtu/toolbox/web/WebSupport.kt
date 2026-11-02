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
 * - `Browser`：有分支但不是一屏，直接 `navigateSameTab` 交给浏览器；
 * - `DownloadManager` / `Jiaocai1` / `Jiaocai1Reader`：本端没有屏，落 `NotPortedScreen`；
 * - `Zyxf`：资料站依赖 Android 端的下载/预览/文件能力，本端没有对应屏，落 `NotPortedScreen`。
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
        AppRoute.Settings::class,
        AppRoute.Transcript::class,
        AppRoute.Attendance::class,
        AppRoute.Coupon::class,
        // 第二批只读域（2026-10-14 接线，契约 §5.6）：思源学堂 / 教材查询 / 成绩 / 快速考勤流水。
        // 与上面那四条同一条规矩：**「本端有没有这一屏」与「此刻后端是哪一形态」是两件事** ——
        // 取数只在 serve 形态下存在（campus-api 里没有这四族的端点），但那四屏就在 `:core`，
        // 所以门照旧列在这里，campus-api 形态下 `AppPage` 画的是「这一屏要 serve 模式」那张如实页。
        //
        // `Lms` 的课程 / 活动深链同样支持；宫格是否展示入口仍由 AppServices 目录决定。
        AppRoute.Lms::class,
        AppRoute.Jiaocai::class,
        AppRoute.JwappScore::class,
        AppRoute.Iclassface::class,
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
        // 账号管理（D9 / 工单 S7a）：**屏就是 `:core` 那份 `AccountManagerScreen`**（三端同一套层级），
        // 取数是 serve 的 `GET /api/accounts`（契约 §5.10）。这一端**只读** —— 切换 / 新增 / 删除 / 改密
        // 要在 App 或桌面端做（serve 只端了读那一半），页顶那一行与动作失败的 toast 说的都是这件事
        // （见 `WebAccountsScreen` / `WebGapNote`）。与 `AppRoute.Match` 同理：门照旧开着，
        // 「点了会有个说法」——而且这一条能看见的是**真数据**（账号表与当前账号）。
        AppRoute.Accounts::class,
        AppRoute.Agent::class,
    )

    /** 这一端真能打开 [route] 吗（见文件头：清单与 `AppPage` 的分支必须同时改）。 */
    fun isOpenable(route: AppRoute): Boolean = route::class in openableRouteTypes

    /** 服务目录里真能点进去的那些（学辅目录的可点行 + `?route=probe` 自检屏的第一张表）。 */
    val openableCatalog: List<AppService> = AppServices.all.filter { isOpenable(it.route) }

    /** 首页按服务入口 id 过滤；从支持类型推导，不为帖子等深链伪造固定参数。 */
    val openableRouteIds: Set<String> = openableCatalog.mapTo(mutableSetOf()) { it.route.id }

    /** 目录里有、本端**还没有屏**的那些（同一份推导的另一半，见文件头末段）。 */
    val unsupportedCatalog: List<AppService> = AppServices.all.filterNot { isOpenable(it.route) }
}
