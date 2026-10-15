package com.xjtu.toolbox.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.core.net.ApiMode
import com.xjtu.toolbox.home.AppService
import com.xjtu.toolbox.home.ServiceCategory
import com.xjtu.toolbox.nav.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Web 这一端**做不到的那几格**的落点，以及「学辅」那一格自己的页。
 *
 * ## 为什么要单写一张降级页（而不是藏掉格子）
 *
 * 阶段 C 的验收点是「三端同壳」：web 的底栏与 `:app` 的 `BottomTab` **逐字一致**（见 `WebShell.kt`）。
 * 一致之后仍有格子在这一端**没有对应的屏**（屁岱要 `AgentHost`：配置 / 会话 / 记忆三份落盘、
 * 流式传输、工具层，本端一件都没有）。把格子藏掉就又变成"另一套层级"，所以照桌面 `HostUnavailableScreen` /
 * `NotPortedScreen` 的口径**落一张写清原因与缺哪一件的页**：用户看得见这一格、也看得见为什么。
 *
 * 五张页的分工：
 * - [WebUnavailableScreen]：通用降级页（屁岱，以及壳里认不出的自定义目标）；
 * - [WebSupportMismatchScreen]：**这一端的 bug 页** —— 支持清单说有、`AppPage` 却没有分支时画它
 *   （见 `WebSupport` 的 KDoc：清单与分支必须同时改）；
 * - [WebServicesScreen]：「学辅」那一格 —— **可点目录**（`:app` 那格是学业服务目录 + 每项此刻的
 *   状态摘要，本端没有后一半，于是列同一份目录里本端真能点开的那些，点一行进一屏）；
 * - [WebScheduleGapNote]：**日程页顶部那一行**。日程是本端真接上的共享屏，但它残余的缺口
 *（排课原样、campus-api 形态下那两栏）不能只写在代码注释里 —— 这一行把它写在屏幕上；
 * （原来这里还有一张「匹配交友说明页」`WebMatchGapScreen`：那一屏的落点。2026-10-15（D12 ②）把 deflate 与
 * URL-safe Base64 两条缝补进 `:core` 的 commonMain 之后，那一屏在浏览器里**真能用**了 ⇒ 那张说明页删掉，
 * `AppPage` 直接挂 `:core` 的 `MatchScreen` + 本端 `WebMatchHost`。）
 */

/**
 * 「这一端没有这一屏」——标题 + 为什么 + 缺哪几件 + 可选的动作。
 *
 * 口径与桌面那两张插屏一致：宁可写清楚，也不拿别的屏冒充（搬迁前 `?route=notification` 会静默
 * 显示课表 —— 看着像 bug，也分不清「没实现」与「坏了」）。
 */
@Composable
internal fun WebUnavailableScreen(
    title: String,
    why: String,
    missing: List<String>,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(why, color = cs.onSurface, fontSize = 13.sp)
        if (missing.isNotEmpty()) {
            Text("这一端缺的东西：", color = cs.primary, fontSize = 12.sp)
            missing.forEach { line ->
                Text("· $line", color = cs.onBackgroundVariant, fontSize = 12.sp)
            }
        }
        Text(
            "Web 只渲染 :core/commonMain 里的屏：一屏搬过来 = 屏 + 模型 + 取数端口进 :core，" +
                "两端各自注入宿主面。搬过来的那些可以在底栏或 ?route= 里直接看。",
            color = cs.onBackgroundVariant,
            fontSize = 12.sp,
        )
        actions()
    }
}

/**
 * 「支持清单上有这一条、`AppPage` 却没有它的分支」—— **这一端的 bug 页**（不是降级页）。
 *
 * 为什么要单写一张：上一轮的漏正是「入口与分屏各说各的」那一类 —— 十几条路由的屏一直在
 * `AppPage` 里，而它们唯一的入口（首页宫格 / 学辅目录）当时是降级页。现在那份清单只有一处
 * （[WebSupport]），而 `AppPage` 的 `else ->` 会拿它当场对一次账：对不上就画这一页，
 * **指名道姓地说要改哪两处**。落一张「还没搬到 Web」会把自家漏掉的屏说成"本来就搬不过来"。
 */
@Composable
internal fun WebSupportMismatchScreen(route: AppRoute, onNavigate: (WebTarget) -> Unit) {
    val name = route::class.simpleName ?: "?"
    WebUnavailableScreen(
        title = "这一屏的清单与分支对不上（Web 这一端的 bug）",
        why = "`WebSupport.openableRouteIds` 里写着 AppRoute.$name（路由 id `${route.id}`）这一端能打开，" +
            "但 `ToolboxWebApp.kt` 的 `AppPage(route, …)` 里没有它的分支，于是刚才那一跳落到了兜底。" +
            "清单与 `when` 分支必须同时改 —— 见 `WebSupport` 的 KDoc「加一屏要改哪一行」。",
        missing = listOf(
            "`AppPage` 的 `when (route)` 里补一条分支：去渲染那一屏（它才是真画屏的地方）",
            "或者把 AppRoute.$name 从 `WebSupport.openableRouteIds` 里去掉（本端确实没有这一屏）",
        ),
    ) {
        TextButton(
            text = "回首页",
            onClick = { onNavigate(WebTarget.App(AppRoute.Main)) },
            minWidth = 128.dp,
        )
    }
}

/**
 * 「学辅」那一格：**web 这一端的可点目录**。
 *
 * `:app` 的第四格是学业服务目录（`:core` 的 `AppServices` 宫格 + 每个功能此刻的状态摘要）。
 * 本端没有「每项此刻的状态摘要」那一半，于是这一格列的是**同一份目录里本端真能点开的那些**：
 * 一行一屏，点进去就是那一屏 —— 与首页宫格共用同一个真源（[WebSupport]，宫格再按
 * `showOnHome` 筛一道），所以两处不会各说各的。
 *
 * 目录里有、本端还没有屏的那些**也如实列出来**（一行小字，点不动）：用户分得清「本端没有这一屏」
 * 与「这一屏坏了」—— 那正是把清单写在同一处的意义。
 *
 * 已登录还想登出（serve 模式）：会话屏是 Web 专有的一屏，不进五格（见 `WebShell.kt` 的 KDoc），
 * 这里的按钮就是它的一条明路（它不是 `:core` 的路由，所以不在上面那份清单里）。
 *
 * @param serveMode 这一份 `:web` 在对 serve（`:server`）说话 —— 决定会话那一行画不画
 *   （campus-api 那套没有「页面上登录」这回事，画了只会点进一个用不到的屏）。
 */
@Composable
internal fun WebServicesScreen(serveMode: Boolean, onNavigate: (WebTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("学辅（Web 这一端）", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "这一格在 App 上是学业服务目录。本端没有「每项此刻的状态摘要」那一半，" +
                "所以下面列的是同一份目录里**本端真能点开的屏** —— 点一行进那一屏。",
            color = cs.onSurface,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))

        ServiceCategory.entries.forEach { category ->
            val rows = WebSupport.openableCatalog.filter { it.category == category }
            if (rows.isEmpty()) return@forEach
            Text(category.title, color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            rows.forEach { service -> ServiceEntryRow(service, onNavigate) }
            Spacer(Modifier.height(6.dp))
        }

        if (WebSupport.unsupportedCatalog.isNotEmpty()) {
            Text("本端还没有这一屏", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "目录里有、而这一端还没有屏的：" +
                    WebSupport.unsupportedCatalog.joinToString(" · ") { it.title },
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }

        Text("Web 专有的一屏", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (serveMode) {
            Text(
                "会话（登录 / 短信二验 / 登出）：serve 模式的这一屏不进底栏（见 `WebShell.kt`），" +
                    "过不了闸门时外壳会自动落到那里；从这里也能进。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
            TextButton(
                text = "打开会话屏",
                onClick = { onNavigate(WebTarget.Serve) },
                minWidth = 128.dp,
            )
        } else {
            Text(
                "会话屏要 serve 模式（`:server` 的 `GET /api/session`）才有意义；" +
                    "当前是 campus-api 形态，凭据托管在反代进程里，页面上没有登录这回事。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        }
        TextButton(
            text = "后端自检",
            onClick = { onNavigate(WebTarget.Probe) },
            minWidth = 128.dp,
        )
    }
}

/** 可点目录里的一行：标题 + 副标题，整行点进那一屏（标题与分类都来自 `:core` 的服务目录）。 */
@Composable
private fun ServiceEntryRow(service: AppService, onNavigate: (WebTarget) -> Unit) {
    val cs = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onNavigate(WebTarget.App(service.route)) },
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(service.title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(service.subtitle, color = cs.onBackgroundVariant, fontSize = 11.sp)
    }
}

/**
 * 一屏顶部的**如实缺口**说明（通用那张；日程那一张 [WebScheduleGapNote] 就是它的第一个用户）。
 *
 * 为什么要有它：这一端接上共享屏之后，缺的往往是**服务端契约**里的东西（某几个端点没端、某几格
 * 刻意不投影）。那些话不能只写在代码注释里 —— 用户看着一屏少了半截，得能分清「本端/契约没有」
 * 与「真没有数据」，这两件事在屏幕上的说法完全不同（后者是「暂无…」，前者必须指名道姓）。
 */
@Composable
internal fun WebGapNote(text: String) {
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), insideMargin = PaddingValues(10.dp)) {
        Text(text, color = cs.onBackgroundVariant, fontSize = 11.sp)
    }
}

/**
 * 「这一屏要有 serve 模式才有取数链」—— 只读四域（成绩单 / 考勤 / 加餐券 / 宿舍电费，§5.5）与
 * 第二批那四屏（思源学堂 / 教材查询 / 成绩 / 快速考勤流水，§5.6）在 **campus-api 形态**下的落点。
 *
 * 为什么不是 `NotPortedScreen`：那些屏本端**有**（就是 `:core` 的共享屏），本端也有取数
 *（`:core` 的四个 `Campus*Api`），只是它们只认 serve 契约 —— campus-api 里没有对应端点
 *（`:app` / 桌面是走 `:data` 的 okhttp 抓的）。所以这里写的是「切到 serve 模式就能用」，
 * 而不是「这一屏还没搬到 Web」—— 后者是假话。
 *
 * @param title 那一屏的名字（“考勤”那种）
 * @param endpoints 它在 serve 契约里的端点（照 §5.5 / §5.6 的表写）
 * @param why 为什么这一端只能走 serve（一句话：哪个站/哪条会话）
 * @param section 这些端点在哪一节（`"§5.5"` / `"§5.6"` / `"§5.7"` —— 这一页三批共用一个）
 */
@Composable
internal fun WebServeOnlyScreen(
    title: String,
    endpoints: String,
    why: String,
    section: String = "§5.5",
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("$title（Web 这一端：需要 serve 模式）", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "这一屏在本端**有**（`:core` 的共享屏 + `:web` 注入的 `:core` 取数），但它只认 serve 契约的端点：" +
                "$endpoints（docs/api-contract.md $section）。$why 当前这一份 `:web` 探到的是 campus-api 形态" +
                "（凭据托管在同源反代进程里），那几个端点不存在 ⇒ 这里写清楚，不拿别的屏冒充。",
            color = cs.onSurface,
            fontSize = 13.sp,
        )
        Text("这一端缺的东西：", color = cs.primary, fontSize = 12.sp)
        listOf(
            "serve 会话：起 `:server`（它同时托管这一份 `:web`），或用 `?backend=serve` 把模式钉死",
            "campus-api 形态下这条取数链不存在 —— 这些域在 campus-api 里没有端点（不是本端没实现）",
        ).forEach { line ->
            Text("· $line", color = cs.onBackgroundVariant, fontSize = 12.sp)
        }
    }
}

/**
 * 日程页顶部那一行**如实说明**（不是装饰：它写的是这一页此刻真正拿不到的东西）。
 *
 * 为什么要有它：日程是本端接上共享屏的一格，`ScheduleHost` 的缝里 `cache` / `customCourses` /
 * `login` / `session` 都是真的（前两条用 `:core` 的 `keyValueStore` 缝，在 wasm 上是
 * **localStorage**，见 `core/src/wasmJsMain/.../KeyValueStore.wasmJs.kt`），`source`（教务取数）靠
 * **服务端契约里的端点**拼。2026-10-14（§5.8 接线与批①收尾）之后它要说的是两件事，**两种形态各说各的**：
 *
 * - **serve 形态**：格子里的课**已经合过调课 / 停课 / 补课**（`?merged=1` 那一档端的就是 `:data`
 *   的 `JwxtScheduleApi.schedule()`，契约 §5.9），考试与教材两栏也真取数 —— 与 Android / 桌面同口径；
 * - **campus-api 形态**：那三条端点在这一头没有（本端也不认它的字段名，契约 §7 第 2 条），而且
 *   那一头不认 `merged` ⇒ 考试与教材两栏空着、详情里「N座」也不画，格子仍是排课原样。
 *
 * 两种形态下还有**同样做不到的三处**（能力级，与取数无关），屏上一个都不画 —— 逐条理由写在
 * `WebScheduleHost` 的 `honored` 那一段；这几句不写在屏幕上，用户看着一张比 App 短的日程页只能猜。
 *
 * 这一句不能只写在代码里 —— 用户看着一张"没有调课的课表"会以为教务就长这样。
 */
@Composable
internal fun WebScheduleGapNote(mode: ApiMode) {
    WebGapNote(
        (if (mode == ApiMode.SERVE) {
            "这一份日程与 Android / 桌面同口径：格子里的课**已经合过调课 / 停课 / 补课**" +
                "（/api/jwxt/schedule?merged=1 端的就是 :data 已合并的那一份），考试与教材两栏也真取数。"
        } else {
            "这一份日程只有排课原样，而且后端不是 serve 模式：调停补课 / 考试 / 教材这三条端点" +
                "本端只认 serve 契约里的那一种面（campus-api 的同名路径字段名不同，本端不认，§7 第 2 条）" +
                "⇒ 考试与教材两栏空着、详情里的「N座」也不画；调课 / 停课 / 换教室同样不体现在格子上。"
        }) +
            "另外三处**这一端两种形态下都做不到**、屏上一个都不画：" +
            "① 「导出日历 (ICS)」—— 浏览器里没有系统分享面板；" +
            "② 课程详情里的「思源学堂」与「本次考勤」两项下钻 —— 要那两个站点的会话；" +
            "③ 今日栏「接下来」的作业截止 —— 要思源那份落盘缓存。",
    )
}
