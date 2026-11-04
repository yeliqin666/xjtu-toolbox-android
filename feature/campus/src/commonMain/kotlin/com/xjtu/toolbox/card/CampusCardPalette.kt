package com.xjtu.toolbox.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme

@Composable
internal fun campusCardPalette(): List<Color> {
    val dark = LocalIsDarkTheme.current
    return listOf(
        0xFFB66F67, 0xFF718E72, 0xFF6C8DA5, 0xFFB59963, 0xFF9280A3,
        0xFF699A99, 0xFF9A8274, 0xFFB7856C, 0xFF7B8E98, 0xFF92938D,
    ).map { value ->
        val base = Color(value)
        (if (dark) lerp(base, Color.White, 0.15f) else base).copy(alpha = 0.85f)
    }
}
