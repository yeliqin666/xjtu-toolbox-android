package com.xjtu.toolbox.agent.bot.cast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 角色自检：每个角色每个动作逐帧采样，查越界 / NaN、一次性动作末帧回到静息，
 * 并把联系表 PNG 和预览网页的帧数据写到 build/pidai-preview/（看图调参用）。
 */
class CastPreviewTest {

    private val out = File("build/pidai-preview").apply { mkdirs() }

    /** 导出到预览网页的角色。 */
    private val PREVIEW: Set<String> = CAST.map { it.id }.toSet()

    /** 一段要看的片段：动作（null = 待命）、时长、视线。 */
    private class Clip(val key: String, val label: String, val act: Act?, val seconds: Double, val lookX: Double = 0.0)

    private fun clips(c: CastCharacter) = listOf(
        Clip("rest", "待命", null, 3.6),
        Clip("micro", "微动", Act.MICRO, c.microSec + 0.3),
        Clip("think", "思考", Act.THINK, 4.0),
        Clip("alert", "提醒", Act.ALERT, 3.0),
        Clip("poke", "被戳", Act.POKE, c.pokeSec + 0.3),
        Clip("combo", "连戳彩蛋", Act.COMBO, c.comboSec + 0.3),
        Clip("left", "看左边", null, 2.0, -1.0),
        Clip("right", "看右边", null, 2.0, 1.0),
    )

    /** 模拟 30fps 推进到片段的 [t] 时刻，返回那一帧。 */
    private fun frames(c: CastCharacter, clip: Clip, fps: Double, each: (Double, Sketch) -> Unit) {
        val engine = CastEngine(c)
        val s = Sketch()
        val t0 = 10.0
        engine.lookAt(clip.lookX, 0.0)
        var now = t0 - 1.0
        while (now < t0) { engine.sample(now, s); now += 1 / 30.0 }
        engine.play(clip.act, t0)
        val n = (clip.seconds * fps).toInt()
        var next = 0
        now = t0
        while (next <= n) {
            val target = t0 + next / fps
            while (now < target - 1e-9) { now = minOf(now + 1 / 30.0, target); engine.sample(now, s) }
            engine.sample(target, s)
            each(next / fps, s)
            next++
        }
    }

    /** 画出来的东西离中心最远多远。镂空出界不要紧（挖的是画布外的空白），不计。 */
    private fun extent(s: Sketch): Double {
        var m = 0.0
        val cmd = s.cmd
        for (i in 0 until s.count) {
            val op = s.op(i)
            var k = op.start
            while (k < op.end) {
                val n = when (cmd[k]) { Sketch.MOVE, Sketch.LINE -> 2; Sketch.CUBIC -> 6; else -> 0 }
                for (j in 1..n) {
                    val v = cmd[k + j].toDouble()
                    assertTrue("NaN", !v.isNaN())
                    if (op.tone != Tone.HOLE) m = maxOf(m, abs(v))
                }
                k += n + 1
            }
        }
        return m
    }

    @Test
    fun `不越界、没有 NaN`() {
        val bad = ArrayList<String>()
        for (c in CAST) for (clip in clips(c)) {
            var worst = 0.0
            var at = 0.0
            frames(c, clip, 30.0) { t, s ->
                val leave = c.leavesCanvas && (clip.act == Act.POKE || clip.act == Act.COMBO)
                val e = extent(s)
                if (!leave && e > worst) { worst = e; at = t }
                if (s.count == 0 && !leave) bad.add("${c.id}/${clip.key} 空画面 @${"%.2f".format(t)}")
            }
            if (worst > 123.0) bad.add("${c.id}/${clip.key} 越界 ${"%.1f".format(worst)} @${"%.2f".format(at)}")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `一次性动作播完回到静息画面`() {
        val bad = ArrayList<String>()
        for (c in CAST) for (act in listOf(Act.MICRO, Act.POKE, Act.COMBO)) {
            val a = render(sampleAfter(c, act, c.seconds(act)), 96)
            val b = render(sampleAfter(c, null, c.seconds(act)), 96)
            var diff = 0
            for (y in 0 until 96) for (x in 0 until 96) {
                val ra = a.getRGB(x, y)
                val rb = b.getRGB(x, y)
                val d = abs((ra shr 16 and 0xff) - (rb shr 16 and 0xff)) +
                    abs((ra shr 8 and 0xff) - (rb shr 8 and 0xff)) + abs((ra and 0xff) - (rb and 0xff))
                if (d > 90) diff++
            }
            if (diff >= 96 * 96 / 100) bad.add("${c.id}/$act 末帧和静息差 $diff 像素")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    private fun sampleAfter(c: CastCharacter, act: Act?, after: Double): Sketch {
        val engine = CastEngine(c)
        val s = Sketch()
        var now = 9.0
        while (now < 10.0) { engine.sample(now, s); now += 1 / 30.0 }
        engine.play(act, 10.0)
        while (now < 10.0 + after) { engine.sample(now, s); now += 1 / 30.0 }
        engine.sample(10.0 + after, s)
        return s
    }

    @Test
    fun `导出联系表与预览帧`() {
        val json = StringBuilder("{")
        for ((ci, c) in CAST.withIndex()) {
            // 联系表：每行一个片段，每格一帧
            val cols = 10
            val cell = 120
            val rows = clips(c)
            val sheet = BufferedImage(cols * cell, rows.size * cell, BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, sheet.width, sheet.height)
            for ((r, clip) in rows.withIndex()) {
                var k = 0
                frames(c, clip, (cols - 1) / clip.seconds) { _, s ->
                    if (k < cols) g.drawImage(render(s, cell), k * cell, r * cell, null)
                    k++
                }
                g.color = Color.GRAY
                g.drawString(clip.key, 4, r * cell + 14)
            }
            ImageIO.write(sheet, "png", File(out, "${c.id}.png"))

            // 预览帧：15fps，每帧一串笔画；每个角色单独一个文件，网页按需加载
            if (c.id !in PREVIEW) continue
            if (json.length > 1) json.append(',')
            val start = json.length
            json.append("\"${c.id}\":{\"label\":\"${c.label}\",\"clips\":{")
            for ((ri, clip) in rows.withIndex()) {
                if (ri > 0) json.append(',')
                json.append("\"${clip.key}\":{\"label\":\"${clip.label}\",\"frames\":[")
                var first = true
                frames(c, clip, 15.0) { _, s ->
                    if (!first) json.append(',')
                    first = false
                    json.append(frameJson(s))
                }
                json.append("]}")
            }
            json.append("}}")
            val one = json.substring(start + c.id.length + 3)
            File(out, "frames").mkdirs()
            File(out, "frames/${c.id}.json").writeText(one)
        }
        json.append('}')
        File(out, "frames.json").writeText(json.toString())
        File(out, "cast.json").writeText(CAST.joinToString(",", "[", "]") { "[\"${it.id}\",\"${it.label}\"]" })
    }

    /** 真实尺寸对比：底栏 60 点、聊天头像 26 点（按 3 倍屏），浅色 / 深色底各一份。 */
    @Test
    fun `导出真实尺寸对比`() {
        val shots = listOf(null to 0.5, Act.THINK to 1.2, Act.ALERT to 1.0, Act.POKE to 0.15, Act.COMBO to 1.2)
        val sizes = intArrayOf(180, 78)
        val grounds = listOf(Color(0xf6, 0xf3, 0xf7), Color(0x1d, 0x1b, 0x20))
        val colW = sizes.sum() + 12
        val rowH = sizes[0] + 8
        val img = BufferedImage(colW * shots.size * grounds.size, rowH * CAST.size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        for ((r, c) in CAST.withIndex()) for ((gi, ground) in grounds.withIndex()) for ((si, shot) in shots.withIndex()) {
            val s = sampleAfter(c, shot.first, shot.second)
            var x = (gi * shots.size + si) * colW
            g.color = ground
            g.fillRect(x, r * rowH, colW, rowH)
            for (px in sizes) {
                g.drawImage(render(s, px, ground), x, r * rowH + (sizes[0] - px) / 2, null)
                x += px + 6
            }
        }
        ImageIO.write(img, "png", File(out, "sizes.png"))
    }

    private fun frameJson(s: Sketch): String {
        val b = StringBuilder("[")
        for (i in 0 until s.count) {
            val op = s.op(i)
            if (i > 0) b.append(',')
            // [笔法, 透明度, 线宽, 颜色, 圆头, 奇偶填充, 路径]：笔法 1 = 镂空、2 = 纯色，和预览网页约定好的格式
            val tone = if (op.tone == Tone.HOLE) 1 else 2
            val color = if (op.tone == Tone.COLOR) "#%06x".format(op.color and 0xffffff) else ""
            b.append("[$tone,${"%.2f".format(op.alpha)},${"%.1f".format(op.stroke)},\"$color\",1,0,\"")
            b.append(svgPath(s, op))
            b.append("\"]")
        }
        return b.append(']').toString()
    }

    private fun svgPath(s: Sketch, op: SketchOp): String {
        val b = StringBuilder()
        var k = op.start
        val c = s.cmd
        fun n(v: Float) = Math.round(v).toString()
        while (k < op.end) {
            when (c[k]) {
                Sketch.MOVE -> { b.append('M').append(n(c[k + 1])).append(' ').append(n(c[k + 2])); k += 3 }
                Sketch.LINE -> { b.append('L').append(n(c[k + 1])).append(' ').append(n(c[k + 2])); k += 3 }
                Sketch.CUBIC -> {
                    b.append('C')
                    for (j in 1..6) { if (j > 1) b.append(' '); b.append(n(c[k + j])) }
                    k += 7
                }
                else -> { b.append('Z'); k += 1 }
            }
        }
        return b.toString()
    }

    /** 用 AWT 按 App 的规则画一帧：镂空（DstOut）、纯色。 */
    private fun render(s: Sketch, px: Int, ground: Color = Color(0xf4, 0xf2, 0xee)): BufferedImage {
        val layer = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = layer.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val unit = px / 2.0 / 1.22 / 100.0
        g.translate(px / 2.0, px / 2.0)
        g.scale(unit, unit)
        for (i in 0 until s.count) {
            val op = s.op(i)
            val path = Path2D.Float(Path2D.WIND_NON_ZERO)
            var k = op.start
            val c = s.cmd
            while (k < op.end) {
                when (c[k]) {
                    Sketch.MOVE -> { path.moveTo(c[k + 1], c[k + 2]); k += 3 }
                    Sketch.LINE -> { path.lineTo(c[k + 1], c[k + 2]); k += 3 }
                    Sketch.CUBIC -> { path.curveTo(c[k + 1], c[k + 2], c[k + 3], c[k + 4], c[k + 5], c[k + 6]); k += 7 }
                    else -> { path.closePath(); k += 1 }
                }
            }
            g.composite = AlphaComposite.getInstance(
                if (op.tone == Tone.HOLE) AlphaComposite.DST_OUT else AlphaComposite.SRC_OVER, op.alpha,
            )
            g.color = if (op.tone == Tone.HOLE) Color.BLACK else Color(op.color.toInt(), true)
            if (op.stroke > 0) {
                g.stroke = BasicStroke(op.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            } else {
                g.fill(path)
            }
        }
        val img = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
        val g2 = img.createGraphics()
        g2.color = ground
        g2.fillRect(0, 0, px, px)
        g2.drawImage(layer, 0, 0, null)
        return img
    }
}
