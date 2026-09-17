package com.xjtu.toolbox.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采集冷启动 + 首屏滚动的热点类/方法，产出 baseline-prof.txt 与 startup-prof.txt。
 *
 * 跑法：`./gradlew :app:generateBaselineProfile`（需连着真机）。产物由插件写进
 * app/src/release/generated/baselineProfiles/，那是插件自己约定的路径，别手工搬走，
 * 否则 release 构建不会打包。
 *
 * 全程不用 UiAutomator 里任何"等界面 idle"的 API（waitForIdle / UiObject2.fling /
 * Until.hasObject）：底栏屁岱是常驻动画，界面永远不会进入 idle，这些调用会一直阻塞，
 * 实测能把单次采集卡死十几分钟。所以只用固定延时 + 低层 swipe（直接注入事件，不等 idle）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndFirstScreen() = rule.collect(
        packageName = TARGET_PACKAGE,
        // 同时产出 startup-prof.txt：启动路径的方法会被排到 dex 前部，
        // 对 splash 一直挡到首帧的本应用（MainActivity.setKeepOnScreenCondition）收益更直接
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // startActivityAndWait 只保证首帧已画，而首帧多半还是 splash——
        // isAppReady 要等登录态恢复完才置 true。固定等一段，让真正的首屏组合完成。
        SystemClock.sleep(SETTLE_MS)

        // 首屏上下滑几次，把列表项的测量/布局/绘制路径带进 profile。
        // 未登录时首屏是登录页，滑动没有内容响应，但也不会失败——启动路径照样采到。
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(3) {
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS)
            SystemClock.sleep(SCROLL_SETTLE_MS)
        }
        device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, SWIPE_STEPS)
        SystemClock.sleep(SCROLL_SETTLE_MS)
    }

    companion object {
        const val TARGET_PACKAGE = "com.xjtu.toolbox"
        private const val SETTLE_MS = 3_000L
        private const val SCROLL_SETTLE_MS = 700L
        private const val SWIPE_STEPS = 12
    }
}
