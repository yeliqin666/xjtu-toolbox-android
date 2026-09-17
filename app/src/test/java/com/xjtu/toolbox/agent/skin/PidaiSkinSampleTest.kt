package com.xjtu.toolbox.agent.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把 Skill 打包脚本真实产出的标准目录钉在测试里。
 *
 * 之前格式文档和 App 解析器各走各的，动作写了也播不出来；这条用例保证
 * `scripts/package_skin.py` 的输出始终是 App 能读的东西。
 */
class PidaiSkinSampleTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/pidai/$name")!!.readBytes()

    @Test
    fun `packager 产出的标准目录可以被 App 解析`() {
        val skin = PidaiSkinParser.parseFiles(
            mapOf(
                "manifest.json" to res("manifest.json"),
                "motion.json" to res("motion.json"),
                "persona.json" to res("persona.json"),
            )
        )
        assertEquals("chick", skin.manifest.id)
        assertEquals(4, skin.motion.actions.size)
        val hatch = skin.motion.actions.getValue("hatch")
        assertEquals(10, hatch.frames.first().layers.size)
        // 破壳的第一拍必须早：裂纹在 0.10s 就亮起来
        val firstBeat = hatch.frames[1]
        assertEquals(0.10, firstBeat.t, 1e-9)
        assertEquals(1.0, firstBeat.layers.first { it.key == "crack" }.alpha, 1e-9)
        val crack = hatch.frames.first().layers.first { it.key == "crack" }.shape
        assertTrue(!crack.outline!!.subpaths.single().closed)
        assertEquals(PidaiPaint.None, crack.fill)
        // 破壳时壳片飞走：末帧仍然声明这一层，只是透明度归零
        val top = hatch.frames.last().layers.first { it.key == "top" }
        assertEquals(0.0, top.alpha, 1e-9)
        // 思考态靠位移而不是旋转对称的形状：那个点要真的走位
        val dots = skin.motion.actions.getValue("think").frames.map { f -> f.layers.first { it.key == "dot" } }
        assertTrue("思考态的点必须移动，否则在 46px 上看不出来", dots.any { kotlin.math.hypot(it.cx - dots[0].cx, it.cy - dots[0].cy) > 0.5 })
        // persona.prompt 用「你是」开头做了身份宣称，display_name 必须跟它一致，
        // 否则又是一句「你是屁岱」对一句「你是小鸡」的老毛病。
        assertEquals("小鸡", skin.persona!!.displayName)
        assertTrue(skin.persona!!.prompt.startsWith("你是"))
    }
}
