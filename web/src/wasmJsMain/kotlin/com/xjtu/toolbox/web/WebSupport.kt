package com.xjtu.toolbox.web

import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.AppServices
import com.xjtu.toolbox.nav.AppRoute

/**
 * 「本端真能打开的路由」—— **全 `:web` 唯一一处这样的清单**。
 *
 * ## 为什么它必须只有一处（上一轮的漏就是这么来的）
 *
 * 阶段 C 把 `:web` 换成共享壳之后，底栏只剩五格（与 `:app` 的 `BottomTab` 逐字一致），
 * 于是「校历 / 成绩 / 黄页 / 游戏 / 通知 / 图书馆 / 场馆 / 空闲教室 / 校园卡 / 全校课表 /
 * 教师检索 / 评教」这些屏**代码全在 `AppPage` 的 `when` 里**，却因为首页与学辅两格是降级页
 * 而没有入口 —— 用户在浏览器里只看到几页提示，以为这些功能没搬过来。
 *
 * 所以「这一端支持哪些路由」这件事只能有一个真源：就是本文件。它有三个消费方：
 *
 * | 消费方 | 用它做什么 |
 * |---|---|
 * | `WebHomeHost.openableRouteIds` | 首页宫格按它筛：打不开的入口**不画**（`HomeCards.groups(onlyRouteIds = …)`） |
 * | `WebServicesScreen`（学辅那一格） | 可点目录：列出来的每一行都能点进去，点不动的一个字都不列 |
 * | `ToolboxWebApp.AppPage` 的 `else ->` | 降级判据：兜底分支发现「清单说有、这里却没有分支」时**大声报错**，而不是静默落一张"还没搬到 Web" |
 *
 * ## 加一屏要改哪一行（写在这里，免得下次又漏一遍）
 *
 * 1. `AppPage`（`ToolboxWebApp.kt` 的 `when (route)`）加一条分支 —— 它才是真渲染那一屏的地方；
 * 2. **本文件的 `openableRouteIds` 加一条 `AppRoute.Xxx`** —— 否则那一屏在首页宫格与学辅目录上
 *    都不会出现（用户看不到门），而 `?route=xxx` 深链照样能开（分支在、门不在，正是最难看的那种漏）；
 * 3. 若那条路由在 `:core` 的服务目录（`AppServices`）里有条目，第 2 步之后首页宫格与学辅目录
 *    自动就有它了（标题/分类/图标都来自目录，不另写一份）。
 *
 * 反过来（清单里有、`AppPage` 没有分支）由 `AppPage` 的 `else ->` 兜住：那一支会画一张
 * **指名道姓**的"清单与分支对不上"页，而不是"还没搬到 Web" —— 前者是这一端的 bug，后者是事实。
 *
 * ## 什么不在清单里（如实，不是漏）
 *
 * - `AppRoute.Agent`（屁岱）：它在 `AppPage` 里是一条**降级页**（`AgentHost` 三份落盘 + 流式 +
 *   工具层，本端一件都没有 ⇒ 不是"能打开的一屏"）；
 * - `is AppRoute.Browser`：它有分支，但它**不是一屏** —— 它把 URL 交给浏览器本身
 *   （`navigateSameTab`）。清单的口径是"点了会落在本端的一张真屏上"，所以它不在这里；
 * - 其余 `:core` 路由（`DownloadManager` / `Jiaocai1` / `Jiaocai1Reader` / `GameMerge` …）：
 *   本端没有它们的屏，`AppPage` 落 `NotPortedScreen`。
 *
 * ⚠️ 清单里**不等于** "那一屏在本端真能用"：第三批的 `AppRoute.Match` 就在清单里，而它在浏览器里
 * 落的是**如实说明页**（wasm 上没有 deflate / Base64 ⇒ 分享码生成与解析都不可用，见 `WebMatchGapScreen`）。
 * 清单管的是"点了会有个说法"，那一屏到底真能用还是如实说明，由 `AppPage` 那条分支自己说。
 *
 * ## 与目录的另一半：还没有屏的那些
 *
 * [unsupportedCatalog] 是同一份推导的另一半（目录里有、本端还没有屏），学辅那一格与
 * `?route=probe` 的自检屏都用它 —— 于是「哪些能点、哪些还没有」在页面上也是同一份说法。
 */
internal object WebSupport {

    /**
     * 本端 `AppPage` 里**落到真屏**的那些路由（见文件头：加一条屏要同时改哪两处）。
     *
     * 存的是 `AppRoute.id`（`HomeHost.openableRouteIds` 就是 `Set<String>`：宫格按 id 对齐目录），
     * 从 `AppRoute` 对象推出来 —— 于是「清单里写的是不是真路由」由编译器管，手写字符串不行。
     */
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
        // 只读四域（2026-10-13 接线）：成绩单 / 考勤 / 加餐券 / 宿舍电费。它们的取数只在 serve
        // 形态下存在（契约 §5.5）—— campus-api 形态下这几条分支画的是「这一屏要 serve 模式」那张
        // 如实页（`WebServeOnlyScreen`）。门照旧列在这里：**「本端有没有这一屏」与「此刻后端是哪一
        // 形态」是两件事**（深链与首页入口不该随探测结果变来变去）。
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
        // 第三批（2026-10-14 接线，契约 §5.7）：付款码 / 反馈与建议 / 匹配交友 / WebVPN 转换器。
        // 四条里三种落法，但门都列在这里（「本端有没有这一屏」与「此刻后端是哪一形态」是两件事）：
        //  - 付款码 / 反馈 / WebVPN：屏在 `:core`，取数只认 serve 契约 ⇒ campus-api 形态下画
        //    `WebServeOnlyScreen`（说清「切到 serve 模式就能用」，不是「还没搬到 Web」）；
        //  - 匹配交友：**两种形态下都用不了**（wasm 上没有 deflate / Base64 ⇒ 分享码生成与解析都
        //    不可用）⇒ `AppPage` 落一张**如实说明页**（`WebMatchGapScreen`）—— 那也是「本端能打开
        //    这一条路由」的一种真相，而且比藏起来强：用户点进去看得到为什么。
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
        // 账号管理（D9 / 工单 S7a）：**屏就是 `:core` 那份 `AccountManagerScreen`**（三端同一套层级），
        // 取数是 serve 的 `GET /api/accounts`（契约 §5.10）。这一端**只读** —— 切换 / 新增 / 删除 / 改密
        // 要在 App 或桌面端做（serve 只端了读那一半），页顶那一行与动作失败的 toast 说的都是这件事
        // （见 `WebAccountsScreen` / `WebGapNote`）。与 `AppRoute.Match` 同理：门照旧开着，
        // 「点了会有个说法」——而且这一条能看见的是**真数据**（账号表与当前账号）。
        AppRoute.Accounts,
    ).mapTo(mutableSetOf()) { it.id }

    /** 这一端真能打开 [route] 吗（见文件头：清单与 `AppPage` 的分支必须同时改）。 */
    fun isOpenable(route: AppRoute): Boolean = route.id in openableRouteIds

    /**
     * 服务目录里**真能点进去**的那些（学辅目录的可点行 + `?route=probe` 自检屏的第一张表）。
     *
     * 取 `AppServices.all` 而不是 `AppServices.homeFor(...)`：目录里有一条 `showOnHome = false`
     * 但本端真有屏的（成绩报表 `ScoreReport`），学辅那一格该列它 —— 首页宫格管的是"首页上摆哪几格"，
     * 学辅管的是"这一端有什么"，两者的筛法不同，但**支持与否只有这一份**。
     */
    val openableCatalog: List<AppService> = AppServices.all.filter { isOpenable(it.route) }

    /** 目录里有、本端**还没有屏**的那些（同一份推导的另一半，见文件头末段）。 */
    val unsupportedCatalog: List<AppService> = AppServices.all.filterNot { isOpenable(it.route) }
}
