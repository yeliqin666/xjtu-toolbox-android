package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HsvHueSlider

/**
 * 通用的不透明取色器：一块「饱和度 × 明度」二维方块，下面一条色相滑条。
 *
 * 为什么不用 miuix 的 `ColorPalette` / `ColorPicker`：0.9.4 这两个组件都把透明度滑条写死在组件
 * 内部（`ColorPalette` 在网格下面无条件跟一条 `HsvAlphaSlider`，`ColorPicker` 的四条通道滑条里
 * 也有 alpha），**都没有开关参数**。本项目里的颜色只用来画课程块、待办图标、主题色和首页卡片，
 * 一律要不透明的：`CourseColors.toHex` 只留 RGB，让用户拖出一条半透明色，存下去就丢了 alpha，
 * 面板里显示的 Hex 和眼睛看到的色块立刻对不上——这正是 issue #311 报的「滑动条滑动后颜色对不上」。
 *
 * 所以只借 miuix 的公开色相滑条 [HsvHueSlider]（观感与其余 MIUIX 控件一致，也不带 alpha），
 * 二维方块自己画：先横铺「白 → 该色相的纯色」，再压一层「透明 → 黑」，就是标准 HSV 方块。
 * 本组件产出的颜色 alpha 恒为 1。
 */
@Composable
fun AppColorPicker(
    color: Color,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
) {
    val currentOnColorChanged by rememberUpdatedState(onColorChanged)

    // 三通道各自一个状态；起步值取外部传进来的色。之后由手势驱动，只有「外面塞进一个我们没
    // 同步过的色」才回头覆盖：纯黑/纯白在 HSV 里色相无定义，跟着回声重置会让色相条乱跳。
    val initial = remember { color.hsvComponents() }
    var hue by remember { mutableFloatStateOf(initial.first) }
    var saturation by remember { mutableFloatStateOf(initial.second) }
    var value by remember { mutableFloatStateOf(initial.third) }

    /**
     * 上一次「从外部同步进来」的 ARGB。只在这一步更新，**不**在用户拖动时更新，这一点很关键：
     * 若拿它记「我发出去的色」，业务层晚一帧才回传（或干脆不回传，只做实时预览）时，
     * 下面的判据就会把用户刚拖到的位置误判成外部改色、把方块弹回原处。
     */
    var lastSyncedExternalArgb by remember { mutableIntStateOf(color.toArgb()) }

    val current = opaqueHsv(hue, saturation, value)

    SideEffect {
        val external = color.toArgb()
        // 两个条件缺一不可：外部值是新来的（不等于上次同步的那个），且和当前内部色不同
        // （否则这次重组就是我刚发出去那一下的回声，再同步回去只会把色相拍成 0）。
        if (external != lastSyncedExternalArgb && external != current.toArgb()) {
            lastSyncedExternalArgb = external
            val c = color.hsvComponents()
            hue = c.first
            saturation = c.second
            value = c.third
        }
    }

    /** 落状态并通知外面；alpha 由 [opaqueHsv] 保证是 1。 */
    fun emit(h: Float, s: Float, v: Float) {
        currentOnColorChanged(opaqueHsv(h, s, v))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showPreview) {
            Box(Modifier.fillMaxWidth().height(26.dp).clip(CircleShape).background(current))
        }

        val squareShape = RoundedCornerShape(12.dp)
        // 方块实测尺寸：命中换算与选中圈都用它。不在 pointerInput 里读 PointerInputScope.size，
        // 而是像上游 ColorPalette 那样由 onGloballyPositioned 记下来——尺寸在手势期间才稳定。
        var square by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .fillMaxWidth()
                // 固定高度 + 满宽：标准取色器的二维图本就是略扁的矩形（饱和度走横轴、明度走纵轴各占一边），
                // 宽高都交给布局给，不在窄屏变扁条、也不至于把弹窗顶出去。
                .height(180.dp)
                .onGloballyPositioned { square = it.size }
                .pointerInput(Unit) {
                    // 手指位置先夹进方块边界，再换算成 (饱和度, 明度)：拖到外面也照样跟随
                    fun pick(pos: Offset) {
                        val w = square.width
                        val h = square.height
                        if (w <= 0 || h <= 0) return
                        val s = (pos.x / w.toFloat()).coerceIn(0f, 1f)
                        val v = 1f - (pos.y / h.toFloat()).coerceIn(0f, 1f)
                        if (s != saturation || v != value) {
                            saturation = s
                            value = v
                            emit(hue, s, v)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            // 多指时只认发起这次手势的那一根，别让第二根手指把颜色拽走
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break
                            pick(change.position)
                            change.consume()
                        }
                    }
                },
        ) {
            // 底色层：圆角裁在这里，渐变只需要画满这一块
            Canvas(Modifier.fillMaxSize().clip(squareShape)) {
                // 横铺「白 → 该色相的纯色」，再竖压一层「透明 → 黑」：明度与饱和度就都在里面了
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.White, opaqueHsv(hue, 1f, 1f))),
                    size = size,
                )
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), size = size)
            }
            // 选中点单独一层、不裁剪：拖到最边上时整圈仍看得见（画进上面那层会被圆角切掉半个）
            Canvas(Modifier.fillMaxSize()) {
                val r = 9.dp.toPx()
                // 圈心跟着手指，但不许探出方块：探出去的那半边会被父布局切掉，反而看着没对齐
                val inner = Offset(
                    (saturation * size.width).coerceIn(r, (size.width - r).coerceAtLeast(r)),
                    ((1f - value) * size.height).coerceIn(r, (size.height - r).coerceAtLeast(r)),
                )
                // 深色底上纯白圈够醒目，浅色底上靠外面那圈淡阴影兜住轮廓
                drawCircle(Color.Black.copy(alpha = 0.28f), radius = r + 1.5.dp.toPx(), center = inner)
                drawCircle(Color.White, radius = r, center = inner, style = Stroke(width = 2.5.dp.toPx()))
            }
        }

        HsvHueSlider(
            currentHue = hue,
            // miuix 这条滑条的回调给的是 0..1（它内部按 currentHue/360 画），这里换回角度
            onHueChanged = { t ->
                hue = ((t * 360f) % 360f + 360f) % 360f
                emit(hue, saturation, value)
            },
        )
    }
}

/**
 * 该色相下 (饱和度, 明度) 对应的纯色，alpha 恒为 1 —— 本项目的颜色不该带透明度。
 *
 * 写成 internal 是为了让 [AppColorPickerTest] 直接钉住这段算术；对外只该用 [AppColorPicker]。
 */
internal fun opaqueHsv(hue: Float, saturation: Float, value: Float): Color =
    Color.hsv(
        hue = ((hue % 360f) + 360f) % 360f,
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f),
    )

/**
 * Compose 的 `Color` 只能拿回 RGB，这里自己折回 (色相 0..360, 饱和度 0..1, 明度 0..1)。
 *
 * 不用 miuix 的 `Color.toHsv()`：剥开也只是一层算术，自己写少一个跨端依赖面
 * （`:core` / `:web` 的 wasmJs 构建对第三方 API 的变体覆盖更敏感）。
 * 灰阶（max == min）色相无定义，一律按 0 处理，与 miuix 的取舍一致。
 */
internal fun Color.hsvComponents(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    val h = if (d <= 0f) {
        0f
    } else {
        val raw = when (max) {
            red -> ((green - blue) / d) % 6f
            green -> (blue - red) / d + 2f
            else -> (red - green) / d + 4f
        }
        ((raw * 60f) % 360f + 360f) % 360f
    }
    val s = if (max <= 0f) 0f else d / max
    return Triple(h, s, max)
}
