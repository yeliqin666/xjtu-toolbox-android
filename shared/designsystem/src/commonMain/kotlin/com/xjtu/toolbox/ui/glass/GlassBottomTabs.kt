// 原先这里还有一套 kyant 的液态玻璃胶囊（拖动手势、形变、折射、高光）；Android 早就整条换成
// COUI `NavigationBar` 了，非 Android 平台又都是经典风格（`glassStyle = false`，走 ClassicBottomBar），
// 那条回退路径没有任何平台会走到，已删除。
package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底栏的一格。
 *
 * @param key 稳定标识，一般传 tab 的枚举值本身，方便调用方在 [GlassBottomTabs.onTabSelected] 里反查。
 * @param tintExempt 旧 kyant 回退路径的「染色遍跳过这一格」标记，现在没有染色遍，保留只为不动调用方。
 * @param title 格子的无障碍/默认文案（COUI 路径用来构造 `CouiNavigationItem`）。
 * @param icon 格子的默认图标（[content] 整格替换时不显示）。
 * @param content 格子内容（一般是图标 + 文字），`selected` 是当前是否选中这一格。
 */
class GlassTab(
    val key: Any,
    val tintExempt: Boolean = false,
    val title: String = "",
    val icon: ImageVector? = null,
    val content: @Composable ColumnScope.(selected: Boolean) -> Unit,
)

/**
 * 悬浮玻璃底栏：整条交给 COUI 的 `NavigationBar` 移植（三主体融球、材质、投影、拖动形变）。
 *
 * 只有 Android 有 COUI 材质；其它平台 [couiGlassBarSupported] 为 false，这里直接什么都不画
 * （那些平台的玻璃风格本来就没启用）。
 *
 * @param selectedIndex 选中那一格的序号。**-1 = 一格都不高亮**（当前页不属于任何一格时就是这样）。
 */
@Composable
fun GlassBottomTabs(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val couiBackdrop = LocalCouiGlassBarBackdrop.current ?: return
    if (!couiGlassBarMaterialEnabled || !couiGlassBarSupported) return
    CouiGlassBottomBar(couiBackdrop, tabs, selectedIndex, onTabSelected, modifier)
}
