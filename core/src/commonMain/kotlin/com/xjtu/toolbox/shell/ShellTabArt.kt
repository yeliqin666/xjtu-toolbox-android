package com.xjtu.toolbox.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底栏 / 侧栏里「某一格自己画」的缝。
 *
 * ## 为什么要有这一条缝
 *
 * Android 的「屁岱」那一格不是一个图标，是一个会动的机器人（`:app` 的 `PidaiNavButton`）：
 * 它认形象设置（`PidaiAppearanceHost`）、认主动提醒（`ProactiveBubbleHost`）、认 Agent 是否在思考
 * （`:core` 的 `AgentThinkingHost`），还会朝手指按的地方瞟一眼（`:app` 的 `PidaiTouch`）。
 * 这三样**只有这一端有**（桌面/web 没有屁岱这一格，也没有那套形象与气泡）⇒ 不能把机器人搬进
 * `:core`，但底栏本身要共享 ⇒ 把「自画那一格」做成一条缝。
 *
 * ## 各端注入什么
 *
 * - `:app`：`AndroidShellTabArt`（屁岱那一格交给 `PidaiTabButton`）；
 * - `:desktop` / `:web`：不注入（用默认的 [None]），每一格都按 [ShellTab.icon] + label 画；
 * - 某一端以后要做「会动的图标」，实现这个接口即可 —— **不用改壳**。
 *
 * ## 注意
 *
 * 自绘的格子不参与玻璃底栏「染色那一遍」（[tintExempt][com.xjtu.toolbox.ui.glass.GlassTab]）：
 * 强调色那一遍会重新创建一份内容，机器人被画两份会跑出两个实例。这一点由 [handles] 说。
 */
interface ShellTabArt {

    /** [tab] 这一格要不要自绘。false = 走壳默认的图标 + 文字（也不豁免染色）。 */
    fun handles(tab: ShellTab): Boolean

    /**
     * 画底栏里的 [tab]（经典底栏 / 玻璃胶囊 / 悬浮胶囊三处共用）。
     *
     * @param current 当前页落的那一格；**null = 不属于任何一格**（底栏一格都不高亮，例如桌面上一条
     *   非 tab 路由）⇒ 自绘内容不该拿它当「切到了哪一格」（屁岱就是按它决定朝哪边瞟一眼）
     * @param tab 要画的这一格
     * @param selected 这一格是不是选中态
     * @param variant 尺寸 / 底色 / 要落的 [Modifier] / 点击（各底栏各不相同，见 [ShellTabVariant]）
     */
    @Composable
    fun barTab(current: ShellTab?, tab: ShellTab, selected: Boolean, variant: ShellTabVariant)

    /**
     * 画侧栏（宽屏）里的 [tab]。
     *
     * 与 [barTab] 分开是因为侧栏那一格比底栏多一样东西：宽屏侧栏会展开 / 收起，
     * 自绘内容要跟着那条弹簧一起动（屁岱旁边的文字按展开进度淡入）。弹簧与几何由端上自己算
     * （`:app` 用的是 `NavigationRailState` 加 miuix 的公式），壳只负责把这一格的位置让出来。
     *
     * @param expanded 侧栏当前是不是展开态
     */
    @Composable
    fun railTab(current: ShellTab?, tab: ShellTab, expanded: Boolean, variant: ShellTabVariant)

    companion object {

        /**
         * 这一端没有自绘的格子：每格都按 [ShellTab.icon] + label 画，也不豁免玻璃底栏的染色。
         * 桌面 / web 用这个。
         */
        val None: ShellTabArt = object : ShellTabArt {
            override fun handles(tab: ShellTab): Boolean = false

            @Composable
            override fun barTab(current: ShellTab?, tab: ShellTab, selected: Boolean, variant: ShellTabVariant) = Unit

            @Composable
            override fun railTab(current: ShellTab?, tab: ShellTab, expanded: Boolean, variant: ShellTabVariant) = Unit
        }
    }
}

/**
 * 自绘那一格要跟着底栏变的几样。**其余由这一端自己定**（机器人画多大、什么颜色、
 * 有没有形象设置 —— 那些是端的自由，壳不认识）。
 *
 * @param diameter 这一格的中心形状直径（经典底栏 38dp / 玻璃与悬浮 40dp）
 * @param paper 这一格的底色（经典与悬浮是 `surfaceContainerHigh`，玻璃是 `surfaceContainerHigh`）
 * @param modifier 要落在外层节点上的 [Modifier]。经典底栏给的是 `Modifier.weight(1f)`
 *   ——「中心那一格像普通 tab 一样占 1/5 宽」这件事由底栏决定，自绘本体的外层节点必须照办，
 *   否则五格宽度不均。
 * @param liftUp 整体上移量（经典底栏的邻居图标压在 64dp 项高的上半部，机器人纯居中会显得沉下去）
 * @param vertical 竖排（侧栏）
 * @param onClick 点这一格。壳把它接成 [ShellNavState.onSelect] —— 自绘内容自己决定把
 *   `selectable` / `clickable` 挂在哪一层（Android 挂在最外层的 Box 上）。
 */
class ShellTabVariant(
    val diameter: Dp,
    val paper: Color,
    val modifier: Modifier = Modifier,
    val liftUp: Dp = 0.dp,
    val vertical: Boolean = false,
    val onClick: () -> Unit = {},
)
