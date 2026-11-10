// 移植自 COUI-Kit `navigation/NavigationBar.kt`（0.0.1-alpha10）。
//
// 本 App 的底栏不用左右附件，也就用不到融球：这里改成库的一等公民单主体路径
// `CouiFloatingRenderer.standalone()` + `couiFloatingSurface()`，它与三主体路径共用同一个
// `floatingSurfaces` 私有实现——材质滤镜（两层 BlendModeColorFilter）、patch 合成
// （Src + destinationEffect + includePrefix）、CouiShadowRenderer 网格投影、边缘光/内发光/
// 轮廓光、跟手形变与按压弹簧全部一致，只是不声明关联主体、不计算 metaball pair。
// 库里的三主体管线（CouiFloatingSurfaces.kt / CouiFabExpansion / CouiFabDefaults）原样保留，
// 供搜索框、FAB 等其它组件使用，这里不引用它们。
//
// 宿主接缝：
//   1. selectedIndex 允许 -1（当前页不属于任何一格 → 无高亮）；原厂 require 是 in items.indices。
//   2. [itemContent] 非 null 时整格替换原厂「图标 + 文字」（屁岱机器人、徽标）。
//   3. 胶囊宽度铺满、左右各 20dp 侧距（回到本 App 原来的宽度）；原厂为 16dp 侧距 +
//      136dp 附件预留 + maximumMenuWidth 上限。
package com.xjtu.toolbox.ui.glass.coui

import android.graphics.BlendMode
import androidx.annotation.RequiresApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import com.xjtu.toolbox.ui.glass.coui.CouiIcon as Icon
import com.xjtu.toolbox.ui.glass.coui.CouiText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.ui.glass.coui.CouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.LocalCouiBackdrop
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingMaterial
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingRenderer
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingShadow
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingSurface
import com.xjtu.toolbox.ui.glass.coui.LocalCouiContourGlowEnabled
import com.xjtu.toolbox.ui.glass.coui.SpotlightPreset
import com.xjtu.toolbox.ui.glass.coui.couiFloatingSurface
import com.xjtu.toolbox.ui.glass.coui.couiNavigationFeedback
import com.xjtu.toolbox.ui.glass.coui.rememberCouiSpotlight
import com.xjtu.toolbox.ui.glass.coui.isCouiTouchCancelled
import com.xjtu.toolbox.ui.glass.coui.CouiPressMotion
import com.xjtu.toolbox.ui.glass.coui.CouiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.couiPressMotion
import com.xjtu.toolbox.ui.glass.coui.couiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.rememberCouiPressMotion
import com.xjtu.toolbox.ui.glass.coui.rememberCouiTouchMotion
import com.xjtu.toolbox.ui.glass.coui.CouiTheme
import com.xjtu.toolbox.ui.glass.coui.ProvideCouiContent
import com.xjtu.toolbox.ui.glass.coui.CouiCapsuleShape

/** [selectedIcon] 用于选中和拖动预选，省略时两种状态共用 [icon]。 */
data class CouiNavigationItem(val title: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** 只负责底栏装配，页面状态和导航提交仍由宿主持有。 */
@RequiresApi(34)
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    backdrop: CouiBackdrop = checkNotNull(LocalCouiBackdrop.current) {
        "底栏需要显式 backdrop 或 COUI Scaffold 的独立栏位"
    },
    renderer: CouiFloatingRenderer = remember { CouiFloatingRenderer.standalone() },
    items: List<CouiNavigationItem>,
    selectedIndex: Int,
    dark: Boolean = CouiTheme.isDark,
    lightColor: Color = Color.White,
    themeTint: Color = CouiTheme.materialTintFor(dark),
    capsuleShape: Shape = CouiCapsuleShape,
    selectedColor: Color = (if (dark) Color.White else Color.Black).copy(alpha = 230f / 255f),
    unselectedColor: Color = (if (dark) Color.White else Color.Black).copy(alpha = 184f / 255f),
    itemContent: (@Composable ColumnScope.(index: Int, selected: Boolean) -> Unit)? = null,
    onSelection: (Int) -> Unit,
) {
    // 宿主接缝：允许 -1（无高亮），原厂为 selectedIndex in items.indices
    require(items.isNotEmpty() && selectedIndex in -1 until items.size)
    val density = LocalDensity.current
    val contour = LocalCouiContourGlowEnabled.current
    val material = remember(dark, lightColor, themeTint, density) {
        val extension = with(density) { 4.dp.roundToPx() }
        CouiFloatingMaterial(
            firstColor = if (dark) Color(0x80666666) else Color(0xA3666666),
            firstBlendMode = if (dark) BlendMode.OVERLAY else BlendMode.COLOR_DODGE,
            secondColor = if (dark) Color(0xB3262626) else Color(0x80F0F0F0),
            edgeAlpha = if (dark) 0.8f else 0.25f,
            innerGlowAlpha = if (dark) 0.03f else 0.1f,
            lightColor = lightColor,
            themeTint = themeTint,
            index = 1,
            extension = extension,
        )
    }
    // 与 COUI NavigationBar 菜单主体（shadows[1]）逐参数一致
    val shadow = remember(density, lightColor, dark, contour) {
        fun pixelOffset(dp: Float): Float = (dp * density.density).toInt().toFloat()
        fun pixelSize(dp: Float): Float {
            val px = dp * density.density
            return (if (px >= 0f) px + 0.5f else px - 0.5f).toInt().toFloat()
        }
        val projectionColor = when {
            contour -> Color(0x2D000000)
            dark -> Color(0x78000000)
            else -> Color(0x28000000)
        }
        CouiFloatingShadow(
            elevation = pixelSize(if (contour) 24f else 350f), color = projectionColor,
            lightY = pixelSize(if (contour) -320f else -160f),
            lightZ = if (contour) 0f else pixelSize(10000f),
            lightRadius = pixelSize(if (contour) 1600f else 2000f),
            causticColor = lightColor.copy(alpha = 0x42 / 255f),
            layout = Rect(0f, 0f, 0f, pixelOffset(4.9f)),
            sdf = pixelOffset(10.4f),
            smoothCorner = true,
        )
    }
    val contentLayer = rememberGraphicsLayer()
    val menuMotion = rememberCouiTouchMotion(true)
    val menuPress = rememberCouiPressMotion()
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    var previewSelection by remember { mutableIntStateOf(selectedIndex) }
    val currentSelection by rememberUpdatedState(selectedIndex)
    val onSelect by rememberUpdatedState(onSelection)
    LaunchedEffect(selectedIndex) { previewSelection = selectedIndex }
    val bottomMargin = if (WindowInsets.tappableElement.getBottom(density) > 0) 0.dp else 8.dp
    Box(
        modifier = modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.tappableElement.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp + bottomMargin),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val menuWidth = maxWidth
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .onGloballyPositioned { rowCoordinates = it }
                    .couiFloatingSurface(
                        backdrop = backdrop,
                        renderer = renderer,
                        material = material,
                        shadow = shadow,
                        shape = capsuleShape,
                        contentLayer = contentLayer,
                        contourGlowEnabled = contour,
                    ) {
                        surface(menuBounds, menuMotion, menuPress)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingNode(
                    width = menuWidth, motion = menuMotion, press = menuPress, dark = dark,
                    shape = capsuleShape,
                    lightColor = lightColor,
                    contentLayer = contentLayer,
                    onBounds = { node -> rowCoordinates?.let { menuBounds = it.localBoundingBoxOf(node, false) } },
                ) {
                    val itemBounds = remember(items.size) { mutableMapOf<Int, Rect>() }
                    var menuCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Row(
                        Modifier.fillMaxSize().selectableGroup().onGloballyPositioned { menuCoordinates = it }
                            .pointerInput(items.size) {
                                var active: PointerId? = null
                                var pending: Int? = null
                                var downItem: Int? = null
                                fun hit(position: Offset): Int? = itemBounds.entries.firstOrNull { it.value.contains(position) }?.key
                                fun finish(cancelled: Boolean, position: Offset?) {
                                    val target = couiNavigationReleaseTarget(
                                        previewSelection, pending, downItem, position?.let(::hit), cancelled,
                                    )
                                    if (target != null) {
                                        previewSelection = target
                                        onSelect(target)
                                    }
                                    active = null
                                    pending = null
                                    downItem = null
                                }
                                try {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Main)
                                            if (event.isCouiTouchCancelled()) {
                                                finish(true, null)
                                                continue
                                            }
                                            val tracked = event.changes.firstOrNull { it.id == active }
                                            val pointer = tracked?.takeIf { it.pressed }
                                                ?: event.changes.firstOrNull { it.pressed }
                                                ?: tracked ?: continue
                                            if (active == null) {
                                                downItem = hit(pointer.position)
                                            } else if (pointer.pressed && pointer.position != pointer.previousPosition) {
                                                hit(pointer.position)?.let { index ->
                                                    if (previewSelection != index) {
                                                        previewSelection = index
                                                        pending = index
                                                    }
                                                }
                                            }
                                            active = pointer.id
                                            if (!pointer.pressed) finish(false, pointer.position)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } finally {
                                    // 节点移除不等于原厂 ACTION_CANCEL, 不能在离开页面时触发导航。
                                    previewSelection = currentSelection
                                }
                            }.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items.forEachIndexed { index, item ->
                            val isSelected = previewSelection == index
                            val itemContentColor = if (isSelected) selectedColor else unselectedColor
                            val interactionSource = remember { MutableInteractionSource() }
                            Box(
                                Modifier.weight(1f).height(48.dp)
                                    .onGloballyPositioned { node -> menuCoordinates?.let { itemBounds[index] = it.localBoundingBoxOf(node, false) } }
                                    .semantics(mergeDescendants = true) {
                                        role = Role.Tab
                                        selected = isSelected
                                        onClick {
                                            if (index != currentSelection) onSelect(index)
                                            true
                                        }
                                    }.onKeyEvent {
                                        if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                                            if (index != currentSelection) onSelect(index)
                                            true
                                        } else false
                                    }.focusable(interactionSource = interactionSource),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    Modifier.fillMaxSize().couiNavigationFeedback(dark, interactionSource).padding(top = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Top,
                                ) {
                                    // 宿主接缝：非 null 时整格替换原厂内容，容器与反馈不变
                                    if (itemContent != null) {
                                        itemContent(this, index, isSelected)
                                    } else {
                                        Icon(modifier = Modifier.size(24.dp), imageVector = if (isSelected) item.selectedIcon else item.icon,
                                            contentDescription = null, tint = itemContentColor)
                                        Spacer(Modifier.height(2.dp))
                                        ProvideCouiContent {
                                            Text(text = item.title, color = itemContentColor, fontSize = 10.sp,
                                                lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单主体节点：跟手形变 + 按压弹簧 + 高光，内容录进 contentLayer 后由材质合成。 */
@Composable
private fun FloatingNode(
    width: Dp,
    motion: CouiTouchMotion,
    press: CouiPressMotion,
    dark: Boolean,
    shape: Shape,
    contentLayer: GraphicsLayer,
    lightColor: Color,
    onBounds: (LayoutCoordinates) -> Unit,
    content: @Composable () -> Unit,
) {
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = true,
        preset = SpotlightPreset.OpaqueSmall2,
        dark = dark,
        color = lightColor,
    )
    Box(Modifier.width(width).height(56.dp).onGloballyPositioned(onBounds)) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = motion.translationX.value
                translationY = motion.translationY.value
                scaleX = motion.scaleX.value + press.scale.value - 1f
                scaleY = motion.scaleY.value + press.scale.value - 1f
            }.couiTouchMotion(motion, true, cancelOnConsumedMove = false, consumeDrag = true)
                .couiPressMotion(press, true).then(lightInput)
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                }.clip(shape)
                .drawBehind { drawLight?.invoke(this) },
        ) { content() }
    }
}

private fun surface(
    bounds: Rect, motion: CouiTouchMotion, press: CouiPressMotion,
): CouiFloatingSurface = CouiFloatingSurface(
    bounds.translate(Offset(motion.translationX.value, motion.translationY.value)),
    motion.scaleX.value + press.scale.value - 1f,
    motion.scaleY.value + press.scale.value - 1f,
    1f,
)
