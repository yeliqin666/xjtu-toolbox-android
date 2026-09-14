package com.xjtu.toolbox.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjtu.toolbox.agent.bot.BotEngine
import com.xjtu.toolbox.agent.bot.botShapeById
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 底栏屁岱：每帧的分配量与耗时。
 *
 * 这是全 App 唯一常驻 7×24 在跑的渲染，所以「每帧分配多少字节」是它最要紧的指标：
 * 分配量直接决定 GC 频率，而底栏动画本身就是持续产生垃圾的来源。
 *
 * 用 `sample()` 而不是整条 draw 链路：`BotFrame` 里的身体轮廓、眼洞、彩带 Path 全在
 * 这里分配，正是要测的部分（Brush/Stroke 在绘制期分配，另计）。
 *
 * 引擎建在测量循环**之外**、每轮只 `reset` 一次，这样测到的就是纯每帧成本，不含
 * 引擎构造。累加 `bodyAlpha` 是为了防止 JIT 把 `sample()` 当死代码消除。
 */
@RunWith(AndroidJUnit4::class)
class PerfBotFrameTest {

    private val circle = botShapeById("cercle")!!.radii
    private val triangle = botShapeById("triangle")!!.radii

    private companion object {
        /** 每轮迭代采 60 帧 ≈ 1 秒 60fps，与真实帧循环同量级。 */
        const val FRAMES = 60
    }

    private fun bench(name: String, state: String, shape: DoubleArray?) {
        val engine = BotEngine()
        if (shape != null) engine.setShape(shape, 0.0)
        val stats = Perf.measureMedian(warmup = 100, iterations = 400, rounds = 5) { i ->
            engine.reset(state, 0.0)
            var acc = 0.0
            for (f in 0 until FRAMES) {
                acc += engine.sample(i * 0.016 + f * 0.016).bodyAlpha
            }
            acc
        }
        Perf.report(name, stats, divisor = FRAMES, unit = "frame")
    }

    @Test
    fun idle_circle_perFrame() {
        // 稳态成本：待命圆形（占运行时间 99% 以上）
        bench("bot idle/circle", "idle", circle)
    }

    @Test
    fun idle_triangle_perFrame() {
        // 稳态 + 自定义形状（形状分支参与）
        bench("bot idle/triangle", "idle", triangle)
    }

    @Test
    fun orbit_circle_perFrame() {
        // 最坏情况：6 条轨道环，每条前后两段 Path，外加每帧插值轮廓
        bench("bot orbit/circle", "orbit", circle)
    }

    @Test
    fun comet_triangle_perFrame() {
        // 彩带 + 自定义形状同时在场
        bench("bot comet/triangle", "comet", triangle)
    }

    @Test
    fun noShape_vs_circle_perFrame() {
        // null 形状应等价于圆（形状分支不参与）
        bench("bot idle/null-shape", "idle", null)
    }
}
