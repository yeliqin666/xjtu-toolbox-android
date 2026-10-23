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
 * - `Browser`：有分支但不是一屏，直接 `navigateSameTab` 交给浏览器。
 *   ⚠️ **只认 `url`，`then` 一律丢掉（如实说明）**：`then` 的约定是「先加载 url、走完登录，再自动跳下一站」，
 *   那第二跳要有内置浏览器（`:app` 的 `isAuthHop`）才做得到。浏览器这一端把整个标签页交给目标站，
 *   之后就什么都不剩下了（没有回调、也无法跟地址栏对话）⇒ **不保证跳到 `then`，也不假装会**。
 *   用户会在那个站里看到登录页，登录完自行继续（与桌面端 `desktopBrowserTarget` 同一条口径：只交第一跳）。
 * - `Jiaocai1Reader`：**带参数的深链**（`jiaocai1_reader/<ssno>?title=…`），它的门在 `Jiaocai1` 那一屏
 *   内部（书架与检索结果里的「阅读全文」），首页宫格与学辅目录里**没有**这一条（`AppServices.all` 里
 *   只有不带参数的 `AppRoute.Jiaocai1`）。⚠️ 它进不了上面的清单**不是「本端做不到」**，而是这份清单
 *   只认**定值 id**：`AppRoute.Jiaocai1Reader.id` 恒带 ssno，没有「无参数」的形态（`AppRoute.Lms()` 那条
 *   能进清单正是因为有；带 courseId 的深链形态同样不进）。`AppPage` 里那一支是**真屏**，照旧先按形态分：
 *   campus-api 形态下画「这一屏要 serve 模式」那张如实页（与上面那几条只读域同一条规矩）。
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
        // 教材全文库（2026-10-22）：屏就是 `:feature:jiaocai` 那份 `Jiaocai1LibraryScreen`（与 Android /
        // 桌面同一份），宿主走契约 §5.14 的 `/api/jiaocai1/*` —— 检索 / 页表 / 页图 / 书架真的在
        // **运行 serve 那台机器**上（会议 #9 = 丙「做法①」）。三处如实差异见 `WebJiaocai1Source`：
        // 页图是一发一发同源 fetch（不做本机预取窗口）· 书架落在 serve 侧（跟着账号，换浏览器还在）·
        // 取不到就画 `ErrorState` 而不是「书架还是空的」。
        AppRoute.Jiaocai1,
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
        // 下载管理（2026-10-21）：屏是 `:core` 那份 `DownloadManagerScreen`，宿主走契约 §5.13 的
        // `/api/downloads` —— 队列 / 进度 / 落盘真的在**运行 serve 那台机器**上（会议 #9 = 丙「做法①」）。
        // 三处如实差异见 `WebDownloadsHost`：没有推送（轮询）、目录那一格要等第一次列表、不画播放按钮。
        AppRoute.DownloadManager,
    ).mapTo(mutableSetOf()) { it.id }

    /** 这一端真能打开 [route] 吗（见文件头：清单与 `AppPage` 的分支必须同时改）。 */
    fun isOpenable(route: AppRoute): Boolean = route.id in openableRouteIds

    /** 服务目录里真能点进去的那些（学辅目录的可点行 + `?route=probe` 自检屏的第一张表）。 */
    val openableCatalog: List<AppService> = AppServices.all.filter { isOpenable(it.route) }

    /** 目录里有、本端**还没有屏**的那些（同一份推导的另一半，见文件头末段）。 */
    val unsupportedCatalog: List<AppService> = AppServices.all.filterNot { isOpenable(it.route) }
}
