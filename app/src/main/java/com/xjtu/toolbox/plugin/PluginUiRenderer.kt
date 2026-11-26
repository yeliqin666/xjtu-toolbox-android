package com.xjtu.toolbox.plugin

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.xjtu.toolbox.plugin.api.UiAlign
import com.xjtu.toolbox.plugin.api.UiFlowRowNode
import com.xjtu.toolbox.plugin.api.UiScrollRowNode
import com.xjtu.toolbox.plugin.api.UiWeightNode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.xjtu.toolbox.plugin.api.UiBadgeNode
import com.xjtu.toolbox.plugin.api.UiBadgeTone
import com.xjtu.toolbox.plugin.api.UiGridNode
import com.xjtu.toolbox.plugin.api.UiKeyValueNode
import com.xjtu.toolbox.plugin.api.UiProgressNode
import com.xjtu.toolbox.plugin.api.UiRichTextNode
import com.xjtu.toolbox.plugin.api.UiSwitchNode
import com.xjtu.toolbox.plugin.api.UiTextColor
import com.xjtu.toolbox.ui.components.SwitchPreference
import com.xjtu.toolbox.ui.components.AppDropdownPreference
import com.xjtu.toolbox.plugin.api.UiSelectNode
import com.xjtu.toolbox.plugin.api.UiIconNode
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xjtu.toolbox.plugin.api.UiButtonNode
import com.xjtu.toolbox.plugin.api.UiButtonStyle
import com.xjtu.toolbox.plugin.api.UiCardNode
import com.xjtu.toolbox.plugin.api.UiColumnNode
import com.xjtu.toolbox.plugin.api.UiDividerNode
import com.xjtu.toolbox.plugin.api.UiEmptyNode
import com.xjtu.toolbox.plugin.api.UiFieldNode
import com.xjtu.toolbox.plugin.api.UiImageNode
import com.xjtu.toolbox.plugin.api.UiListItemNode
import com.xjtu.toolbox.plugin.api.UiLoadingNode
import com.xjtu.toolbox.plugin.api.UiNode
import com.xjtu.toolbox.plugin.api.UiRowNode
import com.xjtu.toolbox.plugin.api.UiSpacerNode
import com.xjtu.toolbox.plugin.api.UiTextNode
import com.xjtu.toolbox.plugin.api.UiTextStyle
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 把插件返回的界面树画出来。
 *
 * 这里是插件系统**唯一**碰 Compose 的地方，而且只碰宿主的 Compose——插件那边一个类都没有。
 * 所有组件都用盒子自己的（miuix + designsystem），所以插件页面和 App 内页看起来是一套东西，
 * 深色模式、莫奈取色、字阶也都自动跟着走，插件不用管。
 */
@Composable
internal fun PluginUiTree(
    node: UiNode,
    onAction: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is UiColumnNode -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(node.spacingDp.dp),
            horizontalAlignment = node.alignment.toHorizontalAlignment(),
        ) {
            node.children.forEach { PluginUiTree(it, onAction) }
        }

        is UiRowNode -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(
                node.spacingDp.dp,
                node.horizontal.toHorizontalAlignment(),
            ),
            verticalAlignment = node.vertical.toVerticalAlignment(),
        ) {
            node.children.forEach { child ->
                // 权重节点只在 Row 里有意义：分到 weight 份宽度
                if (child is UiWeightNode) {
                    Box(Modifier.weight(child.weight.coerceAtLeast(0.01f))) {
                        PluginUiTree(child.child, onAction)
                    }
                } else {
                    PluginUiTree(child, onAction)
                }
            }
        }

        is UiWeightNode -> PluginUiTree(node.child, onAction, modifier)

        // 横滑行：子节点按内容宽度排，给个上限免得一张卡占满整行
        is UiScrollRowNode -> Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(node.spacingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { child ->
                Box(Modifier.widthIn(max = 280.dp)) { PluginUiTree(child, onAction) }
            }
        }

        is UiFlowRowNode -> FlowRowLayout(spacing = node.spacingDp.dp, modifier = modifier) {
            node.children.forEach { PluginUiTree(it, onAction) }
        }

        is UiTextNode -> Text(
            text = node.text,
            modifier = if (node.actionId.isNotEmpty()) {
                modifier.clickable { onAction(node.actionId, null) }
            } else {
                modifier
            },
            style = node.style.toMiuixStyle(),
            color = resolveTextColor(
                color = node.color,
                argb = node.argb,
                secondary = node.secondary,
                // 可点但没指定颜色：默认主色，用户才看得出能点
                fallbackPrimary = node.actionId.isNotEmpty() &&
                    node.color == null && node.argb == null && !node.secondary,
            ),
            fontWeight = if (node.bold) FontWeight.Bold else null,
            fontStyle = if (node.italic) FontStyle.Italic else null,
            textDecoration = textDecorationOf(node.underline, node.strikeThrough),
            maxLines = node.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )

        is UiRichTextNode -> Text(
            text = buildAnnotatedString {
                node.spans.forEach { span ->
                    val spanStyle = SpanStyle(
                        color = resolveTextColor(
                            color = span.color,
                            argb = span.argb,
                            secondary = false,
                            fallbackPrimary = span.actionId.isNotEmpty() &&
                                span.color == null && span.argb == null,
                        ),
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        textDecoration = textDecorationOf(span.underline, span.strikeThrough),
                    )
                    if (span.actionId.isEmpty()) {
                        withStyle(spanStyle) { append(span.text) }
                    } else {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = span.actionId,
                                linkInteractionListener = { onAction(span.actionId, null) },
                            ),
                        ) {
                            withStyle(spanStyle) { append(span.text) }
                        }
                    }
                }
            },
            modifier = modifier,
            style = node.style.toMiuixStyle(),
        )

        is UiCardNode -> Card(
            modifier = if (node.actionId.isEmpty()) {
                modifier.fillMaxWidth()
            } else {
                modifier.fillMaxWidth().clickable { onAction(node.actionId, null) }
            },
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(node.paddingDp.dp)) { PluginUiTree(node.child, onAction) }
        }

        is UiButtonNode -> when (node.style) {
            UiButtonStyle.Filled -> Button(
                onClick = { onAction(node.actionId, null) },
                enabled = node.enabled,
                modifier = modifier,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    node.label,
                    color = if (node.enabled) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }

            UiButtonStyle.Tonal -> Button(
                onClick = { onAction(node.actionId, null) },
                enabled = node.enabled,
                modifier = modifier,
            ) { Text(node.label) }

            UiButtonStyle.Plain -> TextButton(
                text = node.label,
                onClick = { onAction(node.actionId, null) },
                enabled = node.enabled,
                modifier = modifier,
            )
        }

        is UiSwitchNode -> SwitchPreference(
            title = node.label,
            summary = node.summary.takeIf { it.isNotBlank() },
            checked = node.checked,
            enabled = node.enabled,
            modifier = modifier.fillMaxWidth(),
            onCheckedChange = { checked -> onAction(node.actionId, if (checked) "true" else "false") },
        )

        is UiKeyValueNode -> Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = node.label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = node.value,
                style = MiuixTheme.textStyles.body2,
                color = if (node.secondary) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }

        is UiProgressNode -> {
            val target = node.value.coerceIn(0f, 1f)
            // 数值变化平滑过去，别一跳一跳的
            val fraction by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 420),
                label = "pluginProgress",
            )
            Column(modifier.fillMaxWidth()) {
                if (node.label.isNotEmpty()) {
                    Text(
                        text = node.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                ) {
                    if (fraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MiuixTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }

        is UiGridNode -> {
            val columns = node.columns.coerceIn(1, 4)
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                node.children.chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { child ->
                            Box(Modifier.weight(1f)) { PluginUiTree(child, onAction) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        is UiBadgeNode -> {
            val background = when (node.tone) {
                UiBadgeTone.Accent -> MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                UiBadgeTone.Warning -> WarningColor.copy(alpha = 0.18f)
                UiBadgeTone.Neutral -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            }
            val foreground = when (node.tone) {
                UiBadgeTone.Accent -> MiuixTheme.colorScheme.primary
                UiBadgeTone.Warning -> WarningColor
                UiBadgeTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(text = node.text, style = MiuixTheme.textStyles.footnote2, color = foreground)
            }
        }

        is UiListItemNode -> {
            val clickable = node.actionId.isNotEmpty()
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .then(if (clickable) Modifier.clickable { onAction(node.actionId, null) } else Modifier)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = node.title,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (node.subtitle.isNotEmpty()) {
                        Text(
                            text = node.subtitle,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (node.trailing.isNotEmpty()) {
                    Text(
                        text = node.trailing,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        is UiFieldNode -> TextField(
            value = node.value,
            onValueChange = { onAction(node.actionId, it) },
            label = node.label,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
        )

        is UiImageNode -> AsyncImage(
            model = node.url,
            contentDescription = null,
            modifier = modifier
                .fillMaxWidth()
                .height(node.heightDp.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )

        is UiIconNode -> {
            val known = PluginIconSet.containsKey(node.name)
            Icon(
                imageVector = PluginIconSet[node.name] ?: PluginIconFallback,
                contentDescription = null,
                modifier = modifier.size(node.sizeDp.dp),
                tint = if (known) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }

        is UiSelectNode -> AppDropdownPreference(
            title = node.label,
            items = node.options.map { it.label },
            selectedIndex = node.options.indexOfFirst { it.key == node.value },
            onSelectedIndexChange = { index ->
                node.options.getOrNull(index)?.let { onAction(node.actionId, it.key) }
            },
            modifier = modifier.fillMaxWidth(),
            summary = node.summary.takeIf { it.isNotBlank() }
                ?: if (node.options.isEmpty()) "暂无可选项" else null,
            enabled = node.options.isNotEmpty(),
        )

        is UiDividerNode -> HorizontalDivider(
            modifier = modifier,
            color = MiuixTheme.colorScheme.dividerLine,
        )

        is UiSpacerNode -> Column(modifier.height(node.heightDp.dp)) {}

        is UiLoadingNode -> LoadingState(message = node.text, modifier = modifier)

        is UiEmptyNode -> EmptyState(title = node.title, subtitle = node.subtitle, modifier = modifier)
    }
}

/**
 * 插件图标白名单：语义名 → 宿主图标。
 *
 * **只增不减**：名字发出去就是承诺，删掉老插件会掉图标。加名字不用升 apiVersion，
 * 但插件用了新名字要把清单里的 `minAppVersionCode` 提上去。
 * 名字用语义（`calendar`），不用图标库类名（`Icons.Outlined.Event`），换图标集时插件不用改。
 */
private val PluginIconSet: Map<String, ImageVector> = mapOf(
    "calendar" to Icons.Outlined.CalendarMonth,
    "grade" to Icons.Outlined.School,
    "book" to Icons.Outlined.MenuBook,
    "card" to Icons.Outlined.CreditCard,
    "bell" to Icons.Outlined.Notifications,
    "school" to Icons.Outlined.AccountBalance,
    "exam" to Icons.Outlined.Assignment,
    "task" to Icons.Outlined.TaskAlt,
    "classroom" to Icons.Outlined.MeetingRoom,
    "seat" to Icons.Outlined.EventSeat,
    "canteen" to Icons.Outlined.Restaurant,
    "bus" to Icons.Outlined.DirectionsBus,
    "doc" to Icons.Outlined.Description,
    "download" to Icons.Outlined.Download,
    "upload" to Icons.Outlined.Upload,
    "user" to Icons.Outlined.Person,
    "time" to Icons.Outlined.Schedule,
    "star" to Icons.Outlined.Star,
    "warning" to Icons.Outlined.Warning,
    "check" to Icons.Outlined.Check,
)

/** 名字不在白名单里时画的占位图标（灰色），不是错误也不报警。 */
private val PluginIconFallback: ImageVector = Icons.Outlined.Extension

/** Column 的水平对齐。 */
private fun UiAlign.toHorizontalAlignment(): Alignment.Horizontal = when (this) {
    UiAlign.Start -> Alignment.Start
    UiAlign.Center -> Alignment.CenterHorizontally
    UiAlign.End -> Alignment.End
}

/** Row 的垂直对齐：Start 是顶部，End 是底部。 */
private fun UiAlign.toVerticalAlignment(): Alignment.Vertical = when (this) {
    UiAlign.Start -> Alignment.Top
    UiAlign.Center -> Alignment.CenterVertically
    UiAlign.End -> Alignment.Bottom
}

/**
 * 流式换行布局：一行放不下就换行，每行按最高的那个定行高。
 *
 * 没用 Compose 的 `FlowRow`（还是实验 API），自己算一个，行为可预期：
 * 子节点按自身宽度排，超宽就换行，行内左对齐。
 */
@Composable
private fun FlowRowLayout(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val maxWidth = if (constraints.maxWidth == Int.MAX_VALUE) 0 else constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }

        val rows = mutableListOf<MutableList<Placeable>>()
        var current = mutableListOf<Placeable>()
        var lineWidth = 0
        placeables.forEach { placeable ->
            val extra = if (current.isEmpty()) placeable.width else placeable.width + gap
            if (current.isNotEmpty() && maxWidth > 0 && lineWidth + extra > maxWidth) {
                rows += current
                current = mutableListOf()
                lineWidth = 0
            }
            current += placeable
            lineWidth += if (current.size == 1) placeable.width else placeable.width + gap
        }
        if (current.isNotEmpty()) rows += current

        val rowHeights = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
        val height = rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        val width = if (maxWidth > 0) maxWidth else (rowHeights.maxOrNull() ?: 0)

        layout(width, height) {
            var y = 0
            rows.forEachIndexed { index, row ->
                var x = 0
                row.forEach { placeable ->
                    placeable.place(x, y)
                    x += placeable.width + gap
                }
                y += rowHeights[index] + gap
            }
        }
    }
}

/** 提示色（徽章、进度条、警告文字共用）。 */
private val SuccessColor = Color(0xFF3FA45B)

/** 警告色。 */
private val WarningColor = Color(0xFFE08A00)

/** 按插件给的颜色枚举/ARGB 解析出实际颜色；都没给就跟主题走。 */
@Composable
private fun resolveTextColor(
    color: UiTextColor?,
    argb: Int?,
    secondary: Boolean,
    fallbackPrimary: Boolean = false,
): Color {
    if (argb != null) return Color(argb)
    if (color == null && fallbackPrimary) return MiuixTheme.colorScheme.primary
    return when (color) {
        UiTextColor.Default -> MiuixTheme.colorScheme.onSurface
        UiTextColor.Secondary -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        UiTextColor.Primary -> MiuixTheme.colorScheme.primary
        UiTextColor.Success -> SuccessColor
        UiTextColor.Warning -> WarningColor
        UiTextColor.Error -> MiuixTheme.colorScheme.error
        null -> if (secondary) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.onSurface
        }
    }
}

private fun textDecorationOf(underline: Boolean, strikeThrough: Boolean): TextDecoration? = when {
    underline && strikeThrough -> TextDecoration.combine(
        listOf(TextDecoration.Underline, TextDecoration.LineThrough),
    )
    underline -> TextDecoration.Underline
    strikeThrough -> TextDecoration.LineThrough
    else -> null
}

@Composable
private fun UiTextStyle.toMiuixStyle(): TextStyle = when (this) {
    UiTextStyle.Display -> MiuixTheme.textStyles.title1
    UiTextStyle.Title -> MiuixTheme.textStyles.title2
    UiTextStyle.Section -> MiuixTheme.textStyles.title3
    UiTextStyle.Body -> MiuixTheme.textStyles.body2
    UiTextStyle.Caption -> MiuixTheme.textStyles.footnote2
}
