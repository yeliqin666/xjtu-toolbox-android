package com.xjtu.toolbox.agent.skin

import com.xjtu.toolbox.agent.bot.ImportedMotionEngine
import com.xjtu.toolbox.agent.bot.SkinTransform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 从 `core/src/commonTest/.../agent/bot/ImportedMotionEngineTest.kt` 拆出来的一例。
 *
 * 它要 `PidaiSkinParser`（解析皮肤包，**留在 :app**，因为皮肤包的下载/解压是 Android 那侧的活），
 * 而引擎（`ImportedMotionEngine`）已经搬进 :core —— 夹在中间的这条断言只能放在其中一侧，
 * 放在 :app 这一侧更自然（它同时覆盖解析器与引擎的接口）。
 * 其余 7 例不需要解析器，留在 :core 跟着引擎走。
 */
class PidaiSkinParserV1Test {

    @Test
    fun `v1 皮肤走屏幕轴压扁的旧姿态顺序`() {
        val motion = PidaiSkinParser.parseFiles(
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
