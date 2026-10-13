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
 * 四张页的分工：
 * - [WebUnavailableScreen]：通用降级页（屁岱，以及壳里认不出的自定义目标）；
 * - [WebSupportMismatchScreen]：**这一端的 bug 页** —— 支持清单说有、`AppPage` 却没有分支时画它
 *   （见 `WebSupport` 的 KDoc：清单与分支必须同时改）；
 * - [WebServicesScreen]：「学辅」那一格 —— **可点目录**（`:app` 那格是学业服务目录 + 每项此刻的
 *   状态摘要，本端没有后一半，于是列同一份目录里本端真能点开的那些，点一行进一屏）；
 * - [WebScheduleGapNote]：**日程页顶部那一行**。日程是本端真接上的共享屏，
 *   但它缺三个端点（见那段 KDoc），这一行把它写在屏幕上而不是只写在代码注释里。
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
 * 日程页顶部那一行**如实说明**（不是装饰：它说的是这一页少掉的三样）。
 *
 * 为什么要有它：日程是本端唯一接上共享屏的一格，`ScheduleHost` 的三条缝里
 * [com.xjtu.toolbox.schedule.ScheduleHost.cache] / `customCourses` / `login` 都是真的
 *（前两条用 `:core` 的 `keyValueStore` 缝，在 wasm 上是 **localStorage**，见
 * `core/src/wasmJsMain/.../KeyValueStore.wasmJs.kt`），但 `source`（教务取数）只能靠
 * **服务端契约里真有的那四个端点**拼：`/api/jwxt/term`、`/api/jwxt/term-start`、
 * `/api/jwxt/terms`、`/api/jwxt/schedule`。另外三个端点（调停补课 / 考试 / 教材）在
 * `docs/api-contract.md` §5 的八个端点清单里**不存在**（实测 serve 直接 404），
 * campus-api 那边虽然有 `/api/jwxt/changes` 与 `/api/jwxt/exams`，但本端没有对应客户端，
 * 而 `/api/jwxt/schedule` 端出去的是**排课原样**（契约里写明：调停补课没有合进这些行）。
 *
 * 于是这一页显示的是**排课原样**：调课 / 停课 / 换教室不会体现在格子上，考试与教材两栏空着。
 * 这一句不能只写在代码里 —— 用户看着一张"没有调课的课表"会以为教务就长这样。
 */
@Composable
internal fun WebScheduleGapNote() {
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), insideMargin = PaddingValues(10.dp)) {
        Text(
            "Web 这一份的日程只显示排课原样：调停补课 / 考试 / 教材三个端点不在服务端契约里" +
                "（`docs/api-contract.md` §5 只有八个端点，实测 serve 对它们答 404），" +
                "所以调课、停课、换教室不体现在格子上，考试与教材两栏空着。",
            color = cs.onBackgroundVariant,
            fontSize = 11.sp,
        )
    }
}
