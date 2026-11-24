package com.xjtu.toolbox.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.SmallTitle
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.rememberReportedScrollState
import com.xjtu.toolbox.ui.glass.withoutTop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

private val PANEL_RADIUS = 18.dp

/**
 * 首页公告条位置的小贴士：和公告条同一副卡面，标签用中性色。默认收起只露标题，点开看正文和「去试试」。
 * 今天没有该出的（关了、今天刚关过一条、全看完了）就什么都不画，连 [bottomGap] 也不留。
 */
@Composable
fun HomeTipPanel(onRoute: (AppRoute) -> Unit, bottomGap: Dp) {
    val tip = remember(TipsStore.revision) { TipsStore.today() } ?: return
    var expanded by remember(tip.id) { mutableStateOf(false) }
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = PANEL_RADIUS,
            colors = CardDefaults.defaultColors(color = AppCardColor),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, SinkFeedback()) { expanded = !expanded }
                    .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("💡", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "小贴士 · ${tip.kind.label}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = muted,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tip.title,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = muted,
                    modifier = Modifier.size(22.dp),
                )
                IconButton(onClick = { TipsStore.dismiss(tip) }) {
                    Icon(Icons.Default.Close, contentDescription = "不再显示这条", tint = muted, modifier = Modifier.size(16.dp))
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 36.dp, end = 14.dp, bottom = 14.dp)) {
                    Text(tip.body, style = MiuixTheme.textStyles.footnote1, color = muted)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        tip.route?.let { route -> TipAction("去试试 →") { onRoute(route) } }
                        Spacer(Modifier.weight(1f))
                        TipAction("更多小贴士", primary = false) { onRoute(AppRoute.Tips) }
                    }
                }
            }
        }
        Spacer(Modifier.height(bottomGap))
    }
}

@Composable
private fun TipAction(text: String, primary: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = if (primary) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.footnote1,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        color = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.clickable(remember { MutableInteractionSource() }, SinkFeedback(), onClick = onClick),
    )
}

/** 「小贴士与常见问题」：全部小贴士按类分组，顶上是首页开关。 */
@Composable
fun TipsScreen(onBack: () -> Unit, onRoute: (AppRoute) -> Unit) {
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val glass = rememberPageGlass()
    val revision = TipsStore.revision
    val enabled = remember(revision) { TipsStore.enabled }
    val dismissed = remember(revision) { TipsStore.dismissedCount }
    Scaffold(
        topBar = { GlassTopAppBar(title = "小贴士与常见问题", glass = glass, scrollBehavior = scrollBehavior, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberReportedScrollState(scrollBehavior)),
        ) {
            Spacer(Modifier.height(padding.glassTop(glass)))
            CouiSettingsCard {
                SwitchPreference(
                    title = "首页显示小贴士",
                    summary = "每天一条，有公告时让位",
                    checked = enabled,
                    onCheckedChange = { TipsStore.enabled = it },
                )
                if (dismissed > 0) {
                    ArrowPreference(title = "恢复已关掉的 $dismissed 条", onClick = TipsStore::restore)
                }
            }
            TipKind.entries.forEach { kind ->
                val items = Tips.all.filter { it.kind == kind }
                if (items.isEmpty()) return@forEach
                SmallTitle(kind.label)
                CouiSettingsCard {
                    items.forEach { tip ->
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(tip.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(tip.body, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            tip.route?.let { route ->
                                Spacer(Modifier.height(8.dp))
                                TipAction("去试试 →") { onRoute(route) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
