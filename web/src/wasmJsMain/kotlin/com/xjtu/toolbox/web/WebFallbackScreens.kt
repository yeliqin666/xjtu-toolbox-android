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

/** Web 这一端做不到的那几格的落点，以及「学辅」那一格自己的页。 */

/** 「这一端没有这一屏」——标题 + 为什么 + 缺哪几件 + 可选的动作。 */
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

/** 「支持清单上有这一条、`AppPage` 却没有它的分支」—— 这一端的 bug 页（不是降级页）。 */
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

/** 「学辅」那一格：web 这一端的可点目录。 */
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

/** 一屏顶部的缺口说明。 */
@Composable
internal fun WebGapNote(text: String) {
    val cs = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), insideMargin = PaddingValues(10.dp)) {
        Text(text, color = cs.onBackgroundVariant, fontSize = 11.sp)
    }
}

/** 「这一屏要有 serve 模式才有取数链」。 */
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

/** 日程页顶部那一行缺口说明。 */
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
