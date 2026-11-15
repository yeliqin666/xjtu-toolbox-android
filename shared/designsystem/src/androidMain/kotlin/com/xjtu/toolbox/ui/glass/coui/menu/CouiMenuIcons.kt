package com.xjtu.toolbox.ui.glass.coui.menu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 等价于 COUI-Kit 的 res/drawable/coui_pop_up_next.xml:
 * 14dp × 24dp, 上下两段 1.4 描边圆头折线组成子菜单展开指示。
 * 原图用 pathData 描边, 颜色由宿主 Icon 的 tint 提供, 这里保持同一几何并交给 ColorFilter。
 */
internal val CouiMenuSubMenuIndicator: ImageVector by lazy {
    ImageVector.Builder(
        name = "CouiMenuSubMenuIndicator",
        defaultWidth = 14.dp,
        defaultHeight = 24.dp,
        viewportWidth = 14f,
        viewportHeight = 24f,
    ).apply {
        // M4.199,10L8.328,5.413C8.526,5.192 8.872,5.192 9.071,5.413L13.199,10
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4.199f, 10f)
            lineTo(8.328f, 5.413f)
            curveTo(8.526f, 5.192f, 8.872f, 5.192f, 9.071f, 5.413f)
            lineTo(13.199f, 10f)
        }
        // M13.199,14L9.071,18.587C8.872,18.808 8.526,18.808 8.328,18.587L4.199,14
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(13.199f, 14f)
            lineTo(9.071f, 18.587f)
            curveTo(8.872f, 18.808f, 8.526f, 18.808f, 8.328f, 18.587f)
            lineTo(4.199f, 14f)
        }
    }.build()
}
