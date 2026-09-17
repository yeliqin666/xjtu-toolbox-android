package com.xjtu.toolbox.agent

import androidx.compose.ui.geometry.Offset
import com.xjtu.toolbox.agent.bot.ImportedMotionEngine
import com.xjtu.toolbox.agent.bot.SkinTransform
import com.xjtu.toolbox.agent.bot.transformedBy
import com.xjtu.toolbox.agent.skin.PidaiSkin
import com.xjtu.toolbox.agent.skin.PidaiSkinParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 绘制层的几何契约。
 *
 * 这一层原本没有任何测试：`SkinTransform.toMatrix()` 曾经把平移写进了 Compose `Matrix`
 * 的透视位（`get(row, column)` 的第一个参数其实是列），每个点被除以一个会过零的 w，
 * 所有带偏移的图层都炸成横跨画布的楔形，而单测和编译都是绿的。下面的用例把这条边界钉死。
 */
class SkinDrawGeometryTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/pidai/$name")!!.readBytes()

    private fun sampleSkin(): PidaiSkin = PidaiSkinParser.parseFiles(
        mapOf(
            "manifest.json" to res("manifest.json"),
            "motion.json" to res("motion.json"),
            "persona.json" to res("persona.json"),
        )
    )

    @Test
    fun `toMatrix 映射与仿射定义一致`() {
        val m = SkinTransform(2.0, 3.0, 5.0, 7.0, 11.0, 13.0)
        val matrix = m.toMatrix()
        listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f), Offset(-0.37f, 0.82f)).forEach { p ->
            val mapped = matrix.map(p)
            assertEquals(m.mapX(p.x.toDouble(), p.y.toDouble()), mapped.x.toDouble(), 1e-4)
            assertEquals(m.mapY(p.x.toDouble(), p.y.toDouble()), mapped.y.toDouble(), 1e-4)
        }
    }

    @Test
    fun `平移不会被写进透视位`() {
        val matrix = SkinTransform(1.0, 0.0, 0.0, 1.0, -16.0, 29.0).toMatrix()
        // 纯平移下原点必须落在 (e, f)；写错槽位时它会停在 (0, 0) 或被 w 除掉。
        val origin = matrix.map(Offset.Zero)
        assertEquals(-16.0, origin.x.toDouble(), 1e-4)
        assertEquals(29.0, origin.y.toDouble(), 1e-4)
        // 第四行必须仍是 (0,0,0,1)：任何非零透视分量都会引入除法。
        assertEquals(0f, matrix.values[3])
        assertEquals(0f, matrix.values[7])
        assertEquals(1f, matrix.values[15])
    }

    @Test
    fun `烘焙进控制点与矩阵映射得到同一批坐标`() {
        val outline = sampleSkin().motion.actions.getValue("idle").frames.first().layers.first().shape.outline!!
        val m = SkinTransform.of(cx = 0.3, cy = -0.2, sx = 1.4, sy = 0.8, rot = 0.7).scaled(100.0)
        val baked = outline.transformedBy(m)
        val matrix = m.toMatrix()
        outline.subpaths.forEachIndexed { index, sub ->
            val out = baked.subpaths[index].pts
            var i = 0
            while (i < sub.pts.size) {
                val mapped = matrix.map(Offset(sub.pts[i].toFloat(), sub.pts[i + 1].toFloat()))
                assertEquals(mapped.x.toDouble(), out[i], 1e-2)
                assertEquals(mapped.y.toDouble(), out[i + 1], 1e-2)
                i += 2
            }
        }
    }

    @Test
    fun `样例皮肤每一层都落在画布内`() {
        val skin = sampleSkin()
        val engine = ImportedMotionEngine(skin.motion)
        skin.motion.actions.values.forEach { action ->
            engine.reset(action.id, 0.0)
            // 采若干时刻，包括关键帧之间的插值点
            listOf(0.0, action.duration * 0.25, action.duration * 0.5, action.duration * 0.9, action.duration)
                .forEach { t ->
                    engine.sample(t).layers.forEach { draw ->
                        val outline = draw.outline ?: return@forEach
                        val b = outline.transformedBy(draw.transform).bounds()
                        assertTrue(
                            "${action.id}@$t 的图层跑出画布：${b.toList()}",
                            b.all { it.isFinite() && abs(it) <= 400.0 },
                        )
                    }
                }
        }
    }

    @Test
    fun `眼睛落在关键帧声明的位置上`() {
        val skin = sampleSkin()
        val engine = ImportedMotionEngine(skin.motion)
        engine.reset("hatch", 0.0)
        val eye = engine.sample(1.25).layers.first { it.outline != null && it.alpha > 0.5 && run {
            val b = it.outline!!.transformedBy(it.transform).bounds()
            abs(b[2] - b[0]) < 20.0
        } }
        val b = eye.outline!!.transformedBy(eye.transform).bounds()
        val cx = (b[0] + b[2]) / 2.0
        val cy = (b[1] + b[3]) / 2.0
        // hatch 末帧的 eye_l 在 (-0.17, 0.10 - 0.26)，引擎单位放大 100 倍
        assertEquals(-17.0, cx, 1.0)
        assertEquals(-16.0, cy, 1.0)
    }
}
