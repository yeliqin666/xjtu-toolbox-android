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
import com.xjtu.toolbox.ui.components.TextLink
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
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
            Text("暂不支持：", color = cs.primary, fontSize = 12.sp)
            missing.forEach { line ->
                Text("· $line", color = cs.onBackgroundVariant, fontSize = 12.sp)
            }
        }

        actions()
    }
}

/** 「支持清单上有这一条、`AppPage` 却没有它的分支」—— 这一端的 bug 页（不是降级页）。 */
@Composable
internal fun WebSupportMismatchScreen(route: AppRoute, onNavigate: (WebTarget) -> Unit) {
    val name = route::class.simpleName ?: "?"
    WebUnavailableScreen(
        title = "页面暂时打不开",
        why = "请返回首页重试；仍有问题可提交反馈。",
        missing = emptyList(),
    ) {
        Button(
            onClick = { onNavigate(WebTarget.App(AppRoute.Main)) },
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) { Text("回首页", color = MiuixTheme.colorScheme.onPrimary) }
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
        Text("学辅", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "选择要使用的功能。",
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
            Text("请使用 App 或桌面版", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "以下功能暂未开放：" +
                    WebSupport.unsupportedCatalog.joinToString(" · ") { it.title },
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }

        Text("账号登录", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (serveMode) {
            Text(
                "管理校园账号登录和短信验证。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
            TextLink(text = "登录管理", onClick = { onNavigate(WebTarget.Serve) }, chevron = true)
        } else {
            Text(
                "当前服务由管理员管理登录信息。",
                color = cs.onBackgroundVariant,
                fontSize = 12.sp,
            )
        }
        TextLink(text = "后端自检", onClick = { onNavigate(WebTarget.Probe) }, chevron = true)
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
        Text(title, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("当前服务暂不支持此功能，请使用 App 或桌面版。", color = cs.onSurface, fontSize = 13.sp)

    }
}

/** 日程页顶部那一行缺口说明。 */
@Composable
internal fun WebScheduleGapNote(mode: ApiMode) {
    if (mode != ApiMode.SERVE) WebGapNote("课表暂不含调停课信息、考试和教材，请以教务系统为准。")

}
