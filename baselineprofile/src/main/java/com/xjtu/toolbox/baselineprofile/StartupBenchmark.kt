package com.xjtu.toolbox.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 量 Baseline Profile 到底值多少毫秒：同一个 release 包，一次不带 profile、一次带，比 TTID。
 *
 * 跑法：`./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`。
 * 用的是插件建出来的 benchmarkRelease 变体（minify 开着、加 profileable），
 * 所以数字是 release 口径；debug 不做 AOT，在 debug 上量这个没有意义。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** 基线：装包时不喂 profile，热点方法全靠解释执行 + 运行中 JIT */
    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    /** 对照：要求 profile 必须存在并生效，缺了就报错，避免"其实没装上"被当成没提升 */
    @Test
    fun startupBaselineProfile() =
        measure(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = BaselineProfileGenerator.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // 同 BaselineProfileGenerator：底栏常驻动画会让 waitForIdle / Until.hasObject
        // 永远等不到 idle，这里只能用固定延时。TTID 由 StartupTimingMetric 从系统
        // trace 里读，本来也不依赖这次等待，等一下只是别让下一轮 pressHome 打断收尾。
        SystemClock.sleep(SETTLE_MS)
    }

    private companion object {
        const val ITERATIONS = 10
        const val SETTLE_MS = 2_000L
    }
}
