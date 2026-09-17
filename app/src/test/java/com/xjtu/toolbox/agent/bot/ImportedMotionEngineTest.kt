package com.xjtu.toolbox.agent.bot

import com.xjtu.toolbox.agent.skin.PidaiLayer
import com.xjtu.toolbox.agent.skin.PidaiMotion
import com.xjtu.toolbox.agent.skin.PidaiMotionAction
import com.xjtu.toolbox.agent.skin.PidaiMotionFrame
import com.xjtu.toolbox.agent.skin.PidaiPaint
import com.xjtu.toolbox.agent.skin.PidaiShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImportedMotionEngineTest {
    private val blob = PidaiShape("blob", outlineFromRadii(DoubleArray(8) { 1.0 }), null)
    private val spike = PidaiShape("spike", outlineFromRadii(DoubleArray(8) { if (it % 2 == 0) 1.4 else 0.6 }), null)
    private val bar = PidaiShape("bar", SkinPathParser.parse("M -1 0 L 1 0"), null, fill = PidaiPaint.None, stroke = PidaiPaint.Ink, strokeWidth = 0.1)
    private val photo = PidaiShape("photo", null, "images/me.png", imageW = 2.0, imageH = 2.0)

    private fun layer(key: String, shape: PidaiShape, cx: Double = 0.0, alpha: Double = 1.0) =
        PidaiLayer(key = key, shape = shape, cx = cx, alpha = alpha)

    private fun motionOf(vararg actions: PidaiMotionAction) = PidaiMotion(
        colorArgb = null,
        actions = actions.associateBy { it.id },
        bindings = mapOf("rest" to actions.first().id),
        transitions = emptyList(),
    )

    @Test
    fun `同结构轮廓在段内逐控制点插值`() {
        val action = PidaiMotionAction(
            "bloom", 1.0, false, null,
            listOf(
                PidaiMotionFrame(0.0, layers = listOf(layer("body", blob))),
                PidaiMotionFrame(1.0, layers = listOf(layer("body", spike, cx = 0.4))),
            ),
        )
        val engine = ImportedMotionEngine(motionOf(action))
        val mid = engine.actionLayers(action, 0.5).single()
        assertEquals(0.2, mid.cx, 1e-12)
        val expected = lerpOutline(blob.outline!!, spike.outline!!, 0.5)!!
        expected.subpaths.single().pts.forEachIndexed { i, v ->
            assertEquals(v, mid.outline!!.subpaths.single().pts[i], 1e-12)
        }
    }

    @Test
    fun `图层按 key 配对，层序改变也不会错配`() {
        val action = PidaiMotionAction(
            "swap", 1.0, false, null,
            listOf(
                PidaiMotionFrame(0.0, layers = listOf(layer("shell", blob, cx = -1.0), layer("fluff", spike, cx = 1.0))),
                PidaiMotionFrame(1.0, layers = listOf(layer("fluff", spike, cx = 3.0), layer("shell", blob, cx = -3.0))),
            ),
        )
        val mid = ImportedMotionEngine(motionOf(action)).actionLayers(action, 0.5).associateBy { it.key }
        assertEquals(-2.0, mid.getValue("shell").cx, 1e-12)
        assertEquals(2.0, mid.getValue("fluff").cx, 1e-12)
    }

    @Test
    fun `几何无法对位时交叉淡入淡出，而不是被拒绝`() {
        val action = PidaiMotionAction(
            "morph", 1.0, false, null,
            listOf(
                PidaiMotionFrame(0.0, layers = listOf(layer("mark", blob))),
                PidaiMotionFrame(1.0, layers = listOf(layer("mark", bar))),
            ),
        )
        val mid = ImportedMotionEngine(motionOf(action)).actionLayers(action, 0.5)
        assertEquals(2, mid.size)
        assertEquals(1.0, mid.sumOf { it.alpha }, 1e-12)
        assertTrue(mid.any { it.key == "mark~out" } && mid.any { it.key == "mark" })
    }

    @Test
    fun `矢量换成位图也走淡入淡出`() {
        val action = PidaiMotionAction(
            "reveal", 1.0, false, null,
            listOf(
                PidaiMotionFrame(0.0, layers = listOf(layer("face", blob))),
                PidaiMotionFrame(1.0, layers = listOf(layer("face", photo))),
            ),
        )
        val mid = ImportedMotionEngine(motionOf(action)).actionLayers(action, 0.5)
        assertEquals(2, mid.size)
        assertTrue(mid.any { it.imageSrc == "images/me.png" })
        assertTrue(mid.any { it.outline != null })
    }

    @Test
    fun `只在一边出现的层按透明度淡入淡出，层序不重排`() {
        val action = PidaiMotionAction(
            "hatch", 1.0, false, null,
            listOf(
                PidaiMotionFrame(0.0, layers = listOf(layer("egg", blob), layer("crack", bar))),
                PidaiMotionFrame(1.0, layers = listOf(layer("egg", blob), layer("chick", spike))),
            ),
        )
        val mid = ImportedMotionEngine(motionOf(action)).actionLayers(action, 0.25)
        val byKey = mid.associateBy { it.key }
        assertEquals(0.75, byKey.getValue("crack").alpha, 1e-12)
        assertEquals(0.25, byKey.getValue("chick").alpha, 1e-12)
        assertEquals(1.0, byKey.getValue("egg").alpha, 1e-12)
    }

    @Test
    fun `动作中途切换从当前合成姿态出发`() {
        val idle = PidaiMotionAction(
            "idle", 1.0, true, null,
            listOf(PidaiMotionFrame(0.0, layers = listOf(layer("body", blob))), PidaiMotionFrame(1.0, layers = listOf(layer("body", blob)))),
        )
        val bloom = PidaiMotionAction(
            "bloom", 1.0, false, null,
            listOf(PidaiMotionFrame(0.0, layers = listOf(layer("body", blob))), PidaiMotionFrame(1.0, layers = listOf(layer("body", spike, cx = 0.6)))),
        )
        val engine = ImportedMotionEngine(motionOf(idle, bloom))
        engine.setAction("bloom", 0.0)
        val before = engine.composite(0.5).single()
        engine.setAction("idle", 0.5)
        val boundary = engine.composite(0.5).single()
        assertEquals(before.cx, boundary.cx, 1e-9)
        val was = before.outline!!.subpaths.single().pts
        val now = boundary.outline!!.subpaths.single().pts
        assertTrue(was.indices.all { abs(was[it] - now[it]) < 1e-9 })
    }

    @Test
    fun `显式颜色逐通道插值`() {
        val red = PidaiShape("red", blob.outline, null, fill = PidaiPaint.Solid(0xFFFF0000L))
        val blue = PidaiShape("blue", blob.outline, null, fill = PidaiPaint.Solid(0xFF0000FFL))
        val action = PidaiMotionAction(
            "tint", 1.0, false, null,
            listOf(
                PidaiMotionFrame(0.0, layers = listOf(layer("body", red))),
                PidaiMotionFrame(1.0, layers = listOf(layer("body", blue))),
            ),
        )
        val mid = ImportedMotionEngine(motionOf(action)).actionLayers(action, 0.5).single()
        assertEquals(PidaiPaint.Solid(0xFF7F007FL), mid.fill)
    }

    @Test
    fun `v1 皮肤走屏幕轴压扁的旧姿态顺序`() {
        val motion = com.xjtu.toolbox.agent.skin.PidaiSkinParser.parseFiles(
            mapOf(
                "manifest.json" to """{"format_version":1,"id":"legacy","name":"旧皮肤","version":"1","renderer":"radial-motion-v1"}""".toByteArray(),
                "motion.json" to """
                    {"skin_id":"legacy","shapes":{"ball":{"kind":"circle","radius":1.0}},
                     "actions":[{"id":"idle","duration":1.0,"frames":[
                       {"t":0,"shape":"ball","sx":2.0,"sy":1.0,"rot_deg":90},
                       {"t":1.0,"shape":"ball","sx":2.0,"sy":1.0,"rot_deg":90}]}]}
                """.trimIndent().toByteArray(),
            )
        ).motion
        val draw = ImportedMotionEngine(motion).sample(0.0).layers.single()
        val expected = SkinTransform.ofRadialV1(0.0, 0.0, 2.0, 1.0, Math.PI / 2).scaled(100.0)
        assertEquals(expected.a, draw.transform.a, 1e-9)
        assertEquals(expected.b, draw.transform.b, 1e-9)
        assertEquals(expected.c, draw.transform.c, 1e-9)
        assertEquals(expected.d, draw.transform.d, 1e-9)
    }
}
