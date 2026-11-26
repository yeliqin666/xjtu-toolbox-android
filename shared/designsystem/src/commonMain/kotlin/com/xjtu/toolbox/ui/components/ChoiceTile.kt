package com.xjtu.toolbox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 投票的选择状态。[answers] 是要提交的答案：选中的选项按原顺序，加上「其他」里自己填的。
 * 单选时选项和「其他」只留一个；自己填的「、」换成「，」——提交时用「、」连接各项，统计端按它切分。
 */
@Stable
class ChoiceState(private val options: List<String>, val multiple: Boolean, previous: List<String> = emptyList()) {
    var picked by mutableStateOf(previous.filter { it in options }.toSet()); private set
    var otherOn by mutableStateOf(previous.any { it !in options }); private set
    // 旧版首页弹窗存的自填答案带「其他：」前缀
    var otherText by mutableStateOf(previous.firstOrNull { it !in options }.orEmpty().removePrefix("其他：")); private set

    fun toggle(option: String) {
        val on = option in picked
        picked = when {
            multiple -> if (on) picked - option else picked + option
            on -> emptySet()
            else -> setOf(option)
        }
        if (!multiple && picked.isNotEmpty()) otherOn = false
    }

    fun toggleOther() {
        otherOn = !otherOn
        if (!multiple && otherOn) picked = emptySet()
    }

    fun editOther(text: String) {
        otherText = text.replace("、", "，")
    }

    /** 勾了「其他」却没填：不能提交。 */
    val otherMissing: Boolean get() = otherOn && otherText.isBlank()

    val answers: List<String>
        get() = picked.sortedBy { options.indexOf(it) } + listOfNotNull(otherText.trim().takeIf { otherOn && it.isNotEmpty() })
}

@Composable
fun rememberChoiceState(key: Any, options: List<String>, multiple: Boolean, previous: List<String> = emptyList()): ChoiceState =
    remember(key) { ChoiceState(options, multiple, previous) }

/**
 * 投票的选项列表：每项一张 [ChoiceTile]；[allowOther] 时最后一项是「其他」，选中后在它下面展开输入框。
 */
@Composable
fun ChoiceList(state: ChoiceState, options: List<String>, allowOther: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            ChoiceTile(option, option in state.picked, state.multiple, onClick = { state.toggle(option) })
        }
        if (allowOther) {
            ChoiceTile(OTHER_CHOICE, state.otherOn, state.multiple, onClick = state::toggleOther)
            AnimatedVisibility(visible = state.otherOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                TextField(
                    value = state.otherText,
                    onValueChange = state::editOther,
                    label = "写下你的选项",
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
    }
}

/** 与统计端（`announce.py` 的 `OTHER`）同一个字。 */
const val OTHER_CHOICE = "其他"

/**
 * 投票、问卷的一个选项：整块圆角卡片可点，文字占满左侧、长了自然折行，选中标记在右边，
 * 单选圆、多选圆角方。选中时底色、描边和文字一起换成主色。
 */
@Composable
fun ChoiceTile(
    text: String,
    selected: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(if (selected) colors.primary.copy(alpha = 0.10f) else colors.surfaceContainer)
    val edge by animateColorAsState(if (selected) colors.primary.copy(alpha = 0.6f) else Color.Transparent)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .clickable(role = if (multiple) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        val markShape = if (multiple) RoundedCornerShape(6.dp) else CircleShape
        Box(
            Modifier
                .size(20.dp)
                .clip(markShape)
                .background(if (selected) colors.primary else Color.Transparent)
                .border(1.5.dp, if (selected) colors.primary else colors.onSurfaceVariantSummary.copy(alpha = 0.5f), markShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}
