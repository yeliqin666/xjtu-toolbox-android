package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import com.xjtu.toolbox.ui.glass.LocalTabRoot
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 顶栏左上角的返回按钮。页面被放到底栏上时（[LocalTabRoot]）没有上一级，不画。
 *
 * 走 [CouiToolbarActionButton]：在 COUI 顶栏里就是和右上角动作按钮同一套材质
 * （40dp 圆钮 + 48dp 交互、AGSL 浮层、边缘光、跟手、按压）；没有顶栏采样源时退回半透明圆钮。
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalTabRoot.current) return
    CouiToolbarActionButton(onClick = onClick, modifier = modifier) {
        Icon(BackArrow, contentDescription = "返回")
    }
}

/** Material `arrow_back`（24dp）的原始路径，逐点照抄。 */
private val BackArrow: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12f, 4f)
            lineToRelative(-8f, 8f)
            lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            verticalLineToRelative(-2f)
            close()
        }
    }.build()
}
