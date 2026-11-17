package com.xjtu.toolbox.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 扫一扫的图标：四个取景角 + 中间一条扫描线。
 *
 * 以前这份路径只画在 `:app` 的 `res/drawable/ic_scan.xml` 里，「我的」右上角那颗圆钮用它，
 * 图书馆页顶栏却用 Material 的 `QrCodeScanner`（一个方框加三横的小码），同一件事两个样子。
 * 2026-11 维护者要求统一成「我的」这一颗 —— 挪进 `:shared:designsystem` 才能三端与各处共用：
 * `App`、`Desktop`、`Web` 的 commonMain 都画得出矢量图，不必各自备一份位图。
 *
 * 线宽 2（与顶栏的铃铛、搜索同一档粗细），颜色交给调用方的 `tint`。
 */
val ScanIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Scan",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 四个角：左上、右上、右下、左下
            moveTo(3f, 8f); verticalLineTo(6f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, -3f); horizontalLineToRelative(2f)
            moveTo(16f, 3f); horizontalLineToRelative(2f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, 3f); verticalLineToRelative(2f)
            moveTo(21f, 16f); verticalLineToRelative(2f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, 3f); horizontalLineToRelative(-2f)
            moveTo(8f, 21f); horizontalLineTo(6f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, -3f); verticalLineToRelative(-2f)
            // 扫描线
            moveTo(7f, 12f); horizontalLineTo(17f)
        }
    }.build()
}
