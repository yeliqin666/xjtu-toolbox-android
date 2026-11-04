package com.xjtu.toolbox.platform

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/** 复制文本到剪贴板，并提示「已复制：xxx」。 */
@Suppress("DEPRECATION")
@Composable
fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        { text ->
            clipboard.setText(AnnotatedString(text))
            showBriefMessage("已复制：$text")
        }
    }
}

/** 仅长按复制 [text]（带触感反馈）：单击无涟漪、无点击语义，子节点合并为一个无障碍节点。 */
@Composable
fun Modifier.copyOnLongPress(text: String, copy: (String) -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    return semantics(mergeDescendants = true) { onLongClick(label = "复制") { copy(text); true } }
        .pointerInput(text, copy) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                copy(text)
            })
        }
}
