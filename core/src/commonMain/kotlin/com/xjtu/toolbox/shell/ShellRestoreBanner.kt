package com.xjtu.toolbox.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「正在恢复登录...」那条页脚横幅：**由壳画，因为它压在底栏上方**（`bottomBarReserve` 那一格）。
 *
 * 这一段原本在 `:app` 的 `main/MainScreen.kt` 里（`RestoreBanner` / `RestoreBannerContent`），
 * 与三端共有的东西只有「什么时候该显示、显示什么字」——那两样由端上注入（见 [AppShell] 的
 * `restoring` / `restoreStep`），横幅本身一个字都没改。
 *
 * ⚠️ 调用方**必须一直调用它**（用 `visible` 开关），不要在外面写 `if (restoring) {...}`：
 * AnimatedVisibility 的退场动画只在它还在树上时才播（`:app` 是从「恢复中」切回 false 时滑下去的）。
 */
@Composable
fun ShellRestoreBanner(visible: Boolean, step: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MiuixTheme.colorScheme.secondaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    step.ifEmpty { "正在恢复登录..." },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
