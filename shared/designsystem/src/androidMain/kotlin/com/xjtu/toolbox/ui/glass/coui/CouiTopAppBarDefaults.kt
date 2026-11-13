package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle

@Immutable
data class CouiTopAppBarColors(
    val backgroundColor: Color,
    val titleColor: Color,
    val navigationIconColor: Color,
    val actionIconColor: Color,
)

object CouiTopAppBarDefaults {
    val height = 52.dp
    val expandedHeight = CouiCollapsingAppBarDefaults.titleOnlyHeight
    val collapsedHeight = CouiCollapsingAppBarDefaults.collapsedTitleHeight
    /** 无返回键的大标题顶栏收起后的标题字号，也是钉死小标题应取的尺寸。 */
    val titleOnlyCollapsedSize = 24.sp
    internal val expandedTitleStart = CouiCollapsingAppBarDefaults.titleStart
    internal val horizontalPadding = 12.dp
    internal val titleGap = 8.dp
    val windowInsets: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    /** 普通顶栏标题样式，跟随系统字体大小和字重补偿。 */
    @Composable
    fun titleTextStyle(): TextStyle =
        rememberCouiSystemTextStyle(CouiTextKind.HomeTitle, size = CouiTextKind.TopBar.size)

    @Composable
    internal fun expandedTitleTextStyle(): TextStyle =
        rememberCouiSystemTextStyle(CouiTextKind.HomeTitle)

    @Composable
    fun colors(backgroundColor: Color = Color.Unspecified, titleColor: Color = Color.Unspecified,
        navigationIconColor: Color = Color.Unspecified, actionIconColor: Color = Color.Unspecified,
        dark: Boolean = CouiTheme.isDark): CouiTopAppBarColors {
        val scheme = CouiTheme.colorSchemeFor(dark)
        return CouiTopAppBarColors(backgroundColor.takeOrElse { if (dark) Color.Black else Color.White },
            titleColor.takeOrElse { scheme.onSurface }, navigationIconColor.takeOrElse { scheme.onSurface },
            actionIconColor.takeOrElse { scheme.onSurface })
    }

    /** 记住可恢复的折叠状态；canScroll 读取正文实际滚动范围。 */
    @Composable
    fun exitUntilCollapsedScrollBehavior(canScroll: () -> Boolean = { true }): CouiTopAppBarScrollBehavior {
        val state = rememberCouiCollapsingAppBarState(canScroll)
        return remember(state) { CouiTopAppBarScrollBehavior(state) }
    }

    val fadeHeight = 96.dp
    val referenceBlurRadius = 10.8.dp
    /**
     * 模糊和渐隐延伸到栏下沿之外多少（原版 12dp）。下沿的收尾由表面自己的渐隐遮罩负责（见 CouiTopAppBarSurface）。
     * 淡出段大半落在栏外，栏内只占一小截，标题那一带保持满模糊。
     */
    val bottomExtension = 36.dp

    /** 背景最下面这一段整体淡到透明；模糊强度与遮罩在同一段里一起降，太短会显得硬。 */
    internal val bottomFade = 56.dp
    internal val searchActionGap = 12.dp
    internal val searchActionMinWidth = 40.dp
    internal val searchActionMaxWidth = 152.dp
    internal val searchActionPadding = 16.dp
}
