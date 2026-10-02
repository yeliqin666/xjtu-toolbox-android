package com.xjtu.toolbox.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.bot.BOT_COLORS
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback

/**
 * 屁岱形象选择：颜色。移植自 bloub 的定制器，UI 语言换成本项目的 miuix 卡片。
 *
 * 改动即时生效且设备级持久化（[PidaiAppearanceHost]）：不进 [AgentConfig]，因为形象
 * 是外观偏好而不是某个账号的业务数据；也不做「保存」按钮，选完就该看见底栏变了。
 */
@Composable
fun PidaiAppearancePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colorId = PidaiAppearanceHost.colorId
    val plain = PidaiAppearanceHost.plain
    val proactiveLevel = ProactiveRules.proactiveLevel
    // 面板一打开就把落盘的挡位读进内存缓存，保证显示的是用户上次实际选的那一档。
    LaunchedEffect(Unit) { ProactiveRules.loadProactiveLevel(context) }

    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = com.xjtu.toolbox.ui.components.AppCardColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("屁岱形象", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)

            // 朴素图标：颜色选择照样保留在存档里，只是暂时不画。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("朴素图标", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                    Text(
                        "不播动画、没有装饰，只显示一个静态图标",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = plain, onCheckedChange = { PidaiAppearanceHost.setPlain(context, it) })
            }

            Text(
                "颜色",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            BOT_COLORS.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { def ->
                        ColorDot(
                            label = def.label,
                            selected = def.id == colorId,
                            // 跟随主题没有固定色值，用底栏前景色示意
                            swatch = def.argb?.let { Color(it) } ?: MiuixTheme.colorScheme.onSurface,
                            auto = def.argb == null,
                            onClick = { PidaiAppearanceHost.set(context, color = def.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(6 - row.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }

            Text(
                "主动提醒",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 三选一，不给用户填分钟数——普通人不会去算冷却时长该设多少。
            ProactiveLevelRow(
                title = "关",
                summary = "屁岱不会冒泡，点它也不说话",
                selected = proactiveLevel == ProactiveLevel.OFF,
                onClick = { ProactiveRules.setProactiveLevel(context, ProactiveLevel.OFF) },
            )
            ProactiveLevelRow(
                title = "少",
                summary = "只提醒考试、上课、余额这类正事，不闲聊",
                selected = proactiveLevel == ProactiveLevel.LOW,
                onClick = { ProactiveRules.setProactiveLevel(context, ProactiveLevel.LOW) },
            )
            ProactiveLevelRow(
                title = "标准",
                summary = "偶尔也会闲聊几句",
                selected = proactiveLevel == ProactiveLevel.STANDARD,
                onClick = { ProactiveRules.setProactiveLevel(context, ProactiveLevel.STANDARD) },
            )
        }
    }
}

@Composable
private fun ColorDot(
    label: String,
    selected: Boolean,
    swatch: Color,
    auto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
            ) { onClick() }
            .semantics {
                contentDescription = "颜色：$label"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        val ringColor =
            if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
        Box(
            Modifier
                .size(32.dp)
                .squircleBorder(
                    width = { if (selected) 2.dp else 1.dp },
                    color = { ringColor },
                    cornerRadius = 16.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (auto) {
                // 跟随主题：左半 = 前景色、右半 = 背景色，一眼看出「随明暗自动」
                Row(Modifier.size(22.dp).clip(CircleShape)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MiuixTheme.colorScheme.onSurface)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MiuixTheme.colorScheme.surface)
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(swatch)
                )
            }
        }
    }
}

/** 「主动提醒」三选一里的一行：单选样式，选中态是一个实心圆点。 */
@Composable
private fun ProactiveLevelRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = SinkFeedback(),
                onClick = onClick,
            )
            .semantics { this.selected = selected; contentDescription = "主动提醒：$title" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ringColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
        Box(
            Modifier
                .size(18.dp)
                .squircleBorder(width = { 1.5.dp }, color = { ringColor }, cornerRadius = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body2, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(summary, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
