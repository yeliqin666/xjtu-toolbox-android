package com.xjtu.toolbox.ui.glass.coui


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

data class CouiCheckboxColors(
    val checked: Color,
    val unchecked: Color,
    val disabledChecked: Color,
    val disabledUnchecked: Color,
    val checkmark: Color,
    val disabledCheckmark: Color,
)

private val CheckboxEasing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
private val CheckmarkReturnEasing = CubicBezierEasing(0.3f, 0f, 0.633f, 1f)

/** 承接短信 coui_btn_check 的几何和切换动效; 点击和无障碍语义由宿主提供。 */
@Composable
fun Checkbox(
    modifier: Modifier = Modifier,
    checked: Boolean,
    enabled: Boolean = true,
    shape: Shape = CouiCheckboxDefaults.shape,
    borderShape: Shape = CouiCheckboxDefaults.borderShape,
    colors: CouiCheckboxColors = CouiCheckboxDefaults.colors(CouiTheme.colorScheme.checkboxAccent, CouiTheme.isDark),
) {
    val checkedPath = remember {
        PathParser().parsePathString(
            "M10.831,14.318L17.033,8L18,8.985L11.315,15.796C11.047,16.068 10.614,16.068 " +
                "10.347,15.796L7,12.386L7.967,11.401L10.831,14.318Z",
        ).toPath()
    }
    val timeline = remember { Animatable(1f) }
    var previousChecked by remember { mutableStateOf(checked) }
    var previousEnabled by remember { mutableStateOf(enabled) }
    var animated by remember { mutableStateOf(false) }
    LaunchedEffect(checked, enabled) {
        val changed = enabled && previousEnabled && previousChecked != checked
        previousChecked = checked
        previousEnabled = enabled
        animated = changed
        if (changed) {
            // 原 animated-selector 不可逆；反向从旧目标对应的另一条时间线起点重新播放。
            timeline.snapTo(0f)
            timeline.animateTo(1f, tween(300, easing = LinearEasing))
        } else {
            timeline.snapTo(1f)
        }
    }
    Spacer(modifier.size(24.dp).drawWithCache {
        val unit = size.minDimension / 24f
        val borderOutline = borderShape.createOutline(Size(16.4f * unit, 16.4f * unit), layoutDirection, this)
        val fillOutline = shape.createOutline(Size(18f * unit, 18f * unit), layoutDirection, this)
        val borderStroke = Stroke(1.3f * unit)
        val activeBorderStroke = Stroke(1.6f * unit)
        val checkStroke = Stroke(width = 1.3f * unit, cap = StrokeCap.Butt, join = StrokeJoin.Round)
        val check = Path().apply {
            moveTo((12f - 14.12f / 3f) * unit, (12f - 0.236f / 3f) * unit)
            lineTo((12f - 3.44f / 3f) * unit, (12f + 10.454f / 3f) * unit)
            lineTo((12f + 16.97f / 3f) * unit, (12f - 10.146f / 3f) * unit)
        }
        val measure = PathMeasure().apply { setPath(check, false) }
        val segment = Path()
        onDrawBehind {
            val fillColor = if (enabled) colors.checked else colors.disabledChecked
            val borderColor = if (enabled) colors.unchecked else colors.disabledUnchecked
            val checkColor = if (enabled) colors.checkmark else colors.disabledCheckmark
            val transitioning = enabled && previousEnabled && (animated || previousChecked != checked)
            val time = if (!transitioning) 1f else if (previousChecked != checked) 0f else timeline.value
            val returning = time >= 0.5f
            val phase = (if (returning) time * 2f - 1f else time * 2f).coerceIn(0f, 1f)
            val groupPulse = if (!transitioning) 1f else if (returning) {
                1.08f - 0.08f * CheckboxEasing.transform(phase)
            } else {
                1f + 0.08f * CheckboxEasing.transform(phase)
            }
            val checkPulse = if (transitioning && checked && returning) {
                1.08f - 0.08f * CheckmarkReturnEasing.transform(phase)
            } else groupPulse
            val fill = if (checked) CheckboxEasing.transform((time * 2f).coerceIn(0f, 1f))
                else 1f - CheckboxEasing.transform((time * 2f - 1f).coerceIn(0f, 1f))
            val checkProgress = if (checked) CheckboxEasing.transform(time) else 1f - CheckboxEasing.transform(time)
            scale(groupPulse) {
                translate(3.8f * unit, 3.799f * unit) {
                    drawOutline(
                        outline = borderOutline,
                        color = borderColor,
                        style = if (transitioning && checked) activeBorderStroke else borderStroke,
                    )
                }
                translate(3f * unit, 3f * unit) {
                    drawOutline(
                        outline = fillOutline,
                        color = fillColor,
                        alpha = fill,
                    )
                }
            }
            scale(checkPulse) {
                if (checked && !transitioning) {
                    scale(unit, unit, pivot = Offset.Zero) {
                        drawPath(checkedPath, color = checkColor)
                    }
                } else {
                    segment.reset()
                    measure.getSegment(0f, measure.length * checkProgress, segment)
                    drawPath(
                        path = segment,
                        color = checkColor,
                        style = checkStroke,
                    )
                }
            }
        }
    })
}
