package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object CouiCheckboxDefaults {
    val shape = CouiShape(4.dp)
    val borderShape = CouiShape(3.2.dp)
    fun colors(checked: Color, dark: Boolean): CouiCheckboxColors = CouiCheckboxColors(
        checked = checked,
        unchecked = Color(if (dark) 0x4dffffff else 0x42000000),
        disabledChecked = checked.copy(alpha = 0.3f),
        disabledUnchecked = Color(if (dark) 0x26ffffff else 0x14000000),
        checkmark = Color.White,
        disabledCheckmark = Color(if (dark) 0x29ffffff else 0x8affffff),
    )
}
