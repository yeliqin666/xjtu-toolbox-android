package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.graphics.Color

/** COUI 的固定日夜色值，按语义角色供组件和图标复用。 */
object CouiColor {
    /** 日间色值。 */
    object Day {
        val BLUE = Color(0xFF0080FF)
        val GREEN = Color(0xFF00BD13)
        val YELLOW = Color(0xFFFFB200)
        val RED = Color(0xFFEB3B2F)
        val ORANGE = Color(0xFFFF7700)
        val GRAY = Color(0xFF474747)
        val DARK_GRAY = Color(0xFF191919)
        /** 从原始风扇图标提取；夜间入口沿用此值，未证实为 COUI 日夜主题色。 */
        val CYAN_BLUE = Color(0xFF00A1FF)

        val HOME_BLUE = BLUE
        val HOME_GREEN = GREEN
        val HOME_YELLOW = YELLOW
    }

    /** 夜间色值。 */
    object Night {
        val BLUE = Color(0xFF1A8CFF)
        val GREEN = Color(0xFF24B232)
        val YELLOW = Color(0xFFE5A100)
        val RED = Color(0xFFEB493D)
        val ORANGE = Color(0xFFF08222)
        val GRAY = Day.GRAY
        val DARK_GRAY = Day.DARK_GRAY
        val CYAN_BLUE = Day.CYAN_BLUE

        val HOME_BLUE = BLUE
        val HOME_GREEN = GREEN
        val HOME_YELLOW = YELLOW
    }
}
