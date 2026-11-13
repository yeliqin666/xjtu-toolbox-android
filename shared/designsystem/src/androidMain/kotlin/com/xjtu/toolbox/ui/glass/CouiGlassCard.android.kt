package com.xjtu.toolbox.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiFabDefaults
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingRenderer
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingShadow
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingSurface
import com.xjtu.toolbox.ui.glass.coui.CouiShape
import com.xjtu.toolbox.ui.glass.coui.CouiTheme
import com.xjtu.toolbox.ui.glass.coui.LocalCouiContourGlowEnabled
import com.xjtu.toolbox.ui.glass.coui.couiBackdrop
import com.xjtu.toolbox.ui.glass.coui.couiFloatingSurface
import com.xjtu.toolbox.ui.glass.coui.couiPressMotion
import com.xjtu.toolbox.ui.glass.coui.rememberCouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.rememberCouiPressMotion
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 实际实现：COUI 有色浮层。
 *
 * 材质与投影直接复用 [CouiFabDefaults]（原厂有色 FAB 的 AGSL 材质、边缘光、内光晕、
 * 轮廓投影），按压弹簧复用 [rememberCouiPressMotion]；不创建跟手光、不挂跟手形变。
 *
 * **采样源必须是自己的兄弟节点，不能是包住自己的页面采样层。**
 * 页面正文那层采样层是整个页面录的一张 RenderNode，卡片也在里面；浮层再把它画进
 * 自己的子节点就会形成 RenderNode 环，渲染线程准备树时无限递归直接爆栈
 * （`RenderNode::prepareTreeImpl` × 512 → SIGSEGV）。所以这里单独录一层页面底色
 * 作为采样源：它和浮层是兄弟，颜色也正好是卡片后面本来该有的东西。
 */
@Composable
actual fun CouiGlassCard(
    color: Color,
    modifier: Modifier,
    cornerRadius: Dp,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val density = LocalDensity.current.density
    val contour = LocalCouiContourGlowEnabled.current
    // 圆角用 COUI 的平滑圆角轮廓：和按钮/FAB 同一套 createOutline。
    // cornerWeight = 2 时就是普通圆角矩形（原来的 12dp 卡片形状），不会变成胶囊。
    val shape = remember(cornerRadius) { CouiShape(cornerRadius, cornerWeight = 2f) }
    val cornerRadiusPx = cornerRadius.value * density
    // 课程色保留，但压到 0.78 让页面底色透上来，成半透明玻璃而不是实色块。
    val material = remember(color, dark) { CouiFabDefaults.material(color.copy(alpha = 0.78f), dark) }
    // 课格不要投影：也不要有 FAB 那团彩色光焦（见 CouiCardNoShadow）。
    val shadow = CouiCardNoShadow
    val renderer = remember { CouiFloatingRenderer.standalone() }
    val contentLayer = rememberGraphicsLayer()
    // 卡片自己的采样层：只录一层页面底色，和浮层是兄弟节点。
    val flatBackdrop = rememberCouiBackdrop()
    val baseColor = MiuixTheme.colorScheme.surface
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }
    val press = rememberCouiPressMotion()
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier) {
        // 采样层：纯底色，没有子节点，不会把卡片自己录进去。
        Box(
            Modifier
                .matchParentSize()
                .couiBackdrop(flatBackdrop)
                .background(baseColor),
        )
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { boundsSize = it }
                .couiFloatingSurface(
                    backdrop = flatBackdrop,
                    renderer = renderer,
                    material = material,
                    shadow = shadow,
                    shape = shape,
                    contentLayer = contentLayer,
                    contourGlowEnabled = contour,
                    foregroundComposition = true,
                ) {
                    // 只有按压弹簧：按下缩、松手回弹；没有跟手位移与形变。
                    CouiFloatingSurface(
                        bounds = Rect(
                            Offset.Zero,
                            Size(boundsSize.width.toFloat(), boundsSize.height.toFloat()),
                        ),
                        scaleX = press.scale.value,
                        scaleY = press.scale.value,
                        cornerWeight = 2f,
                        // 必须显式给半径：不传时默认取 min(宽,高)/2，竖长卡片会画成竖胶囊。
                        cornerRadius = cornerRadiusPx,
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = press.scale.value
                        scaleY = press.scale.value
                    }
                    .couiPressMotion(press, enabled = enabled)
                    .drawWithContent { contentLayer.record { this@drawWithContent.drawContent() } }
                    .clip(shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled && onClick != null,
                        role = Role.Button,
                        onClick = { onClick?.invoke() },
                    ),
            ) {
                content()
            }
        }
    }
}

/** 课格不要投影：COUI 浮层的 shadow 参数必填，给一个全透明、不外扩的。 */
private val CouiCardNoShadow = CouiFloatingShadow(
    elevation = 0f,
    color = Color.Transparent,
    lightY = 0f,
    lightZ = 0f,
    lightRadius = 0f,
    causticColor = Color.Transparent,
    layout = Rect.Zero,
    sdf = 1f,
)