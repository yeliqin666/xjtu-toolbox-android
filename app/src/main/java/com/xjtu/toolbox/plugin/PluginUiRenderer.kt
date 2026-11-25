package com.xjtu.toolbox.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
        ) {
            node.children.forEach { PluginUiTree(it, onAction) }
        }

        is UiRowNode -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(node.spacingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { PluginUiTree(it, onAction) }
        }

        is UiTextNode -> Text(
            text = node.text,
            modifier = modifier,
            style = node.style.toMiuixStyle(),
            color = if (node.secondary) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = node.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )

        is UiCardNode -> Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) { PluginUiTree(node.child, onAction) }
        }

        is UiButtonNode -> when (node.style) {
            UiButtonStyle.Filled -> Button(
                onClick = { onAction(node.actionId, null) },
                enabled = node.enabled,
                modifier = modifier,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(node.label, color = MiuixTheme.colorScheme.onPrimary) }

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

        is UiDividerNode -> HorizontalDivider(
            modifier = modifier,
            color = MiuixTheme.colorScheme.dividerLine,
        )

        is UiSpacerNode -> Column(modifier.height(node.heightDp.dp)) {}

        is UiLoadingNode -> LoadingState(message = node.text, modifier = modifier)

        is UiEmptyNode -> EmptyState(title = node.title, subtitle = node.subtitle, modifier = modifier)
    }
}

@Composable
private fun UiTextStyle.toMiuixStyle(): TextStyle = when (this) {
    UiTextStyle.Display -> MiuixTheme.textStyles.title1
    UiTextStyle.Title -> MiuixTheme.textStyles.title2
    UiTextStyle.Section -> MiuixTheme.textStyles.title3
    UiTextStyle.Body -> MiuixTheme.textStyles.body2
    UiTextStyle.Caption -> MiuixTheme.textStyles.footnote2
}
