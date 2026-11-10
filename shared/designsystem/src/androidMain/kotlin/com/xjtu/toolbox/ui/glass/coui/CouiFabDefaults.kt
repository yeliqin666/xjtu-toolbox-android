package com.xjtu.toolbox.ui.glass.coui

import android.graphics.BlendMode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingMaterial
import com.xjtu.toolbox.ui.glass.coui.CouiFloatingShadow
import com.xjtu.toolbox.ui.glass.coui.CouiShape
import com.xjtu.toolbox.ui.glass.coui.CouiColor

/** 有色 FAB 的固定配色、材质与投影预设; 自定义底色仍由宿主提供。 */
object CouiFabDefaults {
    val circleShape = CouiShape(999.dp, cornerWeight = 2f)
    val roundedSquareShape = CouiShape(16.dp)

    val contentColor = Color.White

    fun containerColor(preset: CouiFabColor, dark: Boolean): Color = when (preset) {
        CouiFabColor.Notes -> if (dark) CouiColor.Night.YELLOW else CouiColor.Day.YELLOW
        CouiFabColor.Phone -> if (dark) CouiColor.Night.GREEN else CouiColor.Day.GREEN
        CouiFabColor.InfraredRemote -> if (dark) CouiColor.Night.BLUE else CouiColor.Day.BLUE
        CouiFabColor.Recorder -> if (dark) CouiColor.Night.RED else CouiColor.Day.RED
    }

    fun material(color: Color, dark: Boolean, extension: Int = 0): CouiFloatingMaterial =
        CouiFloatingMaterial(
            firstColor = color,
            firstBlendMode = BlendMode.SRC_OVER,
            secondColor = if (dark) Color(0x19FFFFFF) else Color(0x1AFFFFFF),
            secondBlendMode = BlendMode.OVERLAY,
            blurRadius = 10f,
            strokeType = 2,
            index = 2,
            extension = extension,
            edgeAlpha = 0.25f,
            innerGlowAlpha = 0.3f,
        )

    fun shadow(color: Color, density: Float, contourGlowEnabled: Boolean): CouiFloatingShadow {
        fun pixelOffset(dp: Float): Float = (dp * density).toInt().toFloat()
        fun pixelSize(dp: Float): Float {
            val px = dp * density
            return (if (px >= 0f) px + 0.5f else px - 0.5f).toInt().toFloat()
        }
        // 关闭 caustic 时恢复普通有色投影, 不保留弱化的 caustic。
        val projectionColor = if (contourGlowEnabled) Color(0x1E000000) else color.copy(alpha = 86f / 255f)
        return CouiFloatingShadow(
            elevation = if (contourGlowEnabled) 8f * density else pixelSize(60f),
            color = projectionColor,
            lightY = if (contourGlowEnabled) pixelOffset(-600f) else pixelSize(-800f),
            lightZ = if (contourGlowEnabled) 0f else pixelSize(10000f),
            lightRadius = if (contourGlowEnabled) pixelOffset(600f) else pixelSize(2000f),
            causticColor = causticColor(color),
            layout = Rect(pixelOffset(4f), 0f, pixelOffset(-4f), pixelOffset(11.2f)), sdf = pixelOffset(14f),
            power = 0.3f, powerMix = 0.2f, speed = 1.4f)
    }

    private fun causticColor(color: Color): Color {
        val argb = color.toArgb()
        val red = android.graphics.Color.red(argb).toFloat()
        val green = android.graphics.Color.green(argb).toFloat()
        val blue = android.graphics.Color.blue(argb).toFloat()
        val luma = 0.299f * red + 0.587f * green + 0.114f * blue
        val rgb = when (luma) {
            0f -> 0x828282
            255f -> 0x0B0B0B
            else -> android.graphics.Color.rgb((red * 80f / luma).toInt().coerceIn(0, 255),
                (green * 80f / luma).toInt().coerceIn(0, 255), (blue * 80f / luma).toInt().coerceIn(0, 255))
        }
        return Color((rgb and 0x00FFFFFF) or (153 shl 24))
    }
}
