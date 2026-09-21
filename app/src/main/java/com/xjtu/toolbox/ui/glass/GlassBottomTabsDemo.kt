// 只是给 R2 和仓库主单独看效果用的演示，不接进任何真实页面（见计划 §9.3A 最后一条）。
package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃底栏的独立演示：五个 tab，中间那格模拟屁岱（`tintExempt = true`，画一个纯色圆点代替
 * 真正的 `PidaiNavButton`——那是热点文件 `agent` 目录下的东西，R1 不碰）。
 *
 * 背景铺了一条渐变横幅，方便直接看出玻璃后面的内容有没有被采到、模糊/折射够不够明显。
 */
@Composable
fun GlassBottomTabsDemo() {
    val backdrop = rememberLayerBackdrop()
    var selectedIndex by remember { mutableIntStateOf(0) }
    val demoLabels = listOf("首页", "日程", "屁岱", "学辅", "我的")
    val demoIcons = listOf(
        Icons.Filled.Home,
        Icons.Filled.CalendarMonth,
        Icons.Filled.SmartToy,
        Icons.AutoMirrored.Filled.MenuBook,
        Icons.Filled.Person,
    )

    Box(Modifier.fillMaxSize()) {
        // 背后的内容：一条渐变横幅 + 几行占位文字，模拟首页内容从底栏下面滚过去的场景。
        Column(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFFF7AC6), Color(0xFF7AA6FF), Color(0xFF7AFFC6)),
                        ),
                    ),
            )
            repeat(6) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("演示内容第 ${it + 1} 行——玻璃就是从这些内容上面滚过去的")
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp, start = 16.dp, end = 16.dp)) {
            GlassBottomTabs(
                tabs = demoLabels.indices.map { index ->
                    GlassTab(key = index, tintExempt = index == 2) { selected ->
                        if (index == 2) {
                            PidaiPlaceholderDot(selected)
                        } else {
                            Icon(
                                imageVector = demoIcons[index],
                                contentDescription = demoLabels[index],
                            )
                            Text(demoLabels[index], fontSize = 11.sp)
                        }
                    }
                },
                selectedIndex = selectedIndex,
                onTabSelected = { selectedIndex = it },
                backdrop = backdrop,
            )
        }
    }
}

/** 用一个纯色圆点代替真正的屁岱形象——真正的形象在 `agent/PidaiNavButton.kt`，热点文件不碰。 */
@Composable
private fun PidaiPlaceholderDot(selected: Boolean) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            ),
    )
}
