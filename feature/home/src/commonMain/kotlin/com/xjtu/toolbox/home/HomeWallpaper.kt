package com.xjtu.toolbox.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.xjtu.toolbox.ui.components.HeroCustomBackground
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 整页壁纸：没选图时是主题色铺开的大渐变（很浅，正文和底栏照常清楚）；选了图就铺图片，
 * 顶部和底部各压一段暗色，刊头白字和底栏都看得清。
 *
 * 它原来住在 `HomeWidgetGrid.kt`（dev 的小组件那套）里。小组件实现并进卡片组后那个文件删了，
 * 但**经典分类卡**这一屏还在用它，所以单独搬出来留在这里。
 */
@Composable
internal fun HomeWallpaper(
    mode: String,
    imageData: String,
    modifier: Modifier = Modifier,
    blur: Int = 0,
    brightness: Int = 100,
) {
    val dark = LocalIsDarkTheme.current
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    Box(modifier) {
        if (mode == "solid") {
            Box(Modifier.matchParentSize().background(lerp(surface, primary, if (dark) 0.10f else 0.06f)))
        } else if (imageData.isNotEmpty()) {
            HeroCustomBackground(
                imageData = imageData,
                modifier = Modifier.matchParentSize(),
                blur = blur,
                brightness = brightness,
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.3f to Color.Black.copy(alpha = 0.12f),
                        0.75f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.5f),
                    ),
                ),
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(
                        listOf(
                            lerp(surface, primary, if (dark) 0.30f else 0.22f),
                            lerp(surface, primary, if (dark) 0.18f else 0.10f),
                            lerp(surface, primary, if (dark) 0.08f else 0.04f),
                            surface,
                        ),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                ),
            )
        }
    }
}
