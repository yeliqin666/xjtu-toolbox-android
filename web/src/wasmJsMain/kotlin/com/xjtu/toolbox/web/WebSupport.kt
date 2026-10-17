package com.xjtu.toolbox.web

import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.nav.AppRoute

/**
 * 「本端真能打开的路由」—— 全 `:web` 唯一一处这样的清单（首页宫格、学辅目录、`AppPage` 的兜底都按它）。
 *
 * 加一屏：`AppPage` 加分支 + 这里的 `openableRouteIds` 加一条。
 *
 * ## 什么不在清单里
 *
 * - `Browser`：有分支但不是一屏，直接 `navigateSameTab` 交给浏览器；
 * - `DownloadManager` / `Jiaocai1` / `Jiaocai1Reader`：本端没有屏，落 `NotPortedScreen`。
 */
internal object WebSupport {

    /** 本端 `AppPage` 里落到真屏的那些路由（见文件头：加一条屏要同时改哪两处）。 */
    val openableRouteIds: Set<String> = listOf(
        // 首页：`:core` 的 `HomeScreen` + 本端 `WebHomeHost`（就是画这份宫格的那一屏本身）
        AppRoute.Main,
        AppRoute.Schedule,
        // 下面这几条就是被上一轮关在门后的那批（它们的屏一直在，门没了）
        AppRoute.SchoolCalendar,
        AppRoute.Fitness,
        AppRoute.ScoreReport,
        AppRoute.YellowPage,
        AppRoute.Notification,
        AppRoute.Faculty,
        AppRoute.SchoolCourse,
        AppRoute.Judge,
        AppRoute.Inbox,
        AppRoute.EmptyRoom,
        AppRoute.CampusCard,
        AppRoute.Venue,
        AppRoute.Library,
        AppRoute.Community,
        AppRoute.Settings,
        AppRoute.Transcript,
        AppRoute.Attendance,
        AppRoute.Coupon,
        AppRoute.DormPower,
        // 第二批只读域（2026-10-14 接线，契约 §5.6）：思源学堂 / 教材查询 / 成绩 / 快速考勤流水。
        // 与上面那四条同一条规矩：**「本端有没有这一屏」与「此刻后端是哪一形态」是两件事** ——
        // 取数只在 serve 形态下存在（campus-api 里没有这四族的端点），但那四屏就在 `:core`，
        // 所以门照旧列在这里，campus-api 形态下 `AppPage` 画的是「这一屏要 serve 模式」那张如实页。
        //
        // ⚠️ `AppRoute.Lms` 是带参数的 data class（`courseId`）：这里要的是**没有课程号那一条**
        // （`AppRoute.Lms()` ⇒ id = `lms`），带 courseId 的形态是深链（`?route=lms?courseId=…`）
        // 与日程页下钻用的，不进宫格的清单。
        AppRoute.Lms(),
        AppRoute.Jiaocai,
        AppRoute.JwappScore,
        AppRoute.Iclassface,
        AppRoute.PaymentCode,
        AppRoute.Feedback,
        AppRoute.Match,
        AppRoute.WebVpnConverter,
        // 游戏：合集 + 合集里那六屏（它们在目录里只有「小游戏」一条，子路由由合集屏自己往下走）
        AppRoute.Games,
        AppRoute.Game2048,
        AppRoute.GameBlocks,
        AppRoute.GameHop,
        AppRoute.GameGomoku,
        AppRoute.GameGo,
        AppRoute.GameXiangqi,
        AppRoute.GameMerge,
        // 账号管理（D9 / 工单 S7a）：**屏就是 `:core` 那份 `AccountManagerScreen`**（三端同一套层级），
        // 取数是 serve 的 `GET /api/accounts`（契约 §5.10）。这一端**只读** —— 切换 / 新增 / 删除 / 改密
        // 要在 App 或桌面端做（serve 只端了读那一半），页顶那一行与动作失败的 toast 说的都是这件事
        // （见 `WebAccountsScreen` / `WebGapNote`）。与 `AppRoute.Match` 同理：门照旧开着，
        // 「点了会有个说法」——而且这一条能看见的是**真数据**（账号表与当前账号）。
        AppRoute.Accounts,
        AppRoute.Agent,
    ).mapTo(mutableSetOf()) { it.id }

    /** 这一端真能打开 [route] 吗（见文件头：清单与 `AppPage` 的分支必须同时改）。 */
    fun isOpenable(route: AppRoute): Boolean = route.id in openableRouteIds

    /** 服务目录里真能点进去的那些（学辅目录的可点行 + `?route=probe` 自检屏的第一张表）。 */
    val openableCatalog: List<AppService> = AppServices.all.filter { isOpenable(it.route) }

    /** 目录里有、本端**还没有屏**的那些（同一份推导的另一半，见文件头末段）。 */
    val unsupportedCatalog: List<AppService> = AppServices.all.filterNot { isOpenable(it.route) }
}
