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

    /**
     * 启动之后的高频操作：底栏逐格切换、首页上下滑、开关一张卡片、进出编辑态。
     * 只采启动的话，这些页面第一次打开时仍要解释执行、现场 JIT——「第一次切过去最慢」的主因。
     *
     * 坐标按屏幕比例给（底栏五格的中心、首页第一排卡片），格子没放这些页时点空了也不会失败。
     * 编辑态用返回键退出（= 保存，但没改动）。
     */
    @Test
    fun tabsCardsAndEditing() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
        SystemClock.sleep(SETTLE_MS)
        val w = device.displayWidth
        val h = device.displayHeight
        val barY = (h * BAR_Y).toInt()
        // 两轮：第一轮页面第一次落地，第二轮走「已落地、切回来」那条路径
        repeat(2) {
            BAR_X.forEach { x ->
                device.click((w * x).toInt(), barY)
                SystemClock.sleep(TAB_SETTLE_MS)
            }
        }
        // 回到首页，上下滑，开关第一排第一张卡
        device.click((w * BAR_X.first()).toInt(), barY)
        SystemClock.sleep(TAB_SETTLE_MS)
        repeat(2) {
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS)
            SystemClock.sleep(SCROLL_SETTLE_MS)
            device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, SWIPE_STEPS)
            SystemClock.sleep(SCROLL_SETTLE_MS)
        }
        repeat(2) {
            device.click((w * CARD_X).toInt(), (h * CARD_Y).toInt())
            SystemClock.sleep(CARD_SETTLE_MS)
            device.pressBack()
            SystemClock.sleep(CARD_SETTLE_MS)
        }
        // 长按进编辑态、滑一下、返回键退出
        device.swipe((w * CARD_X).toInt(), (h * CARD_Y).toInt(), (w * CARD_X).toInt(), (h * CARD_Y).toInt(), LONG_PRESS_STEPS)
        SystemClock.sleep(CARD_SETTLE_MS)
        device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS)
        SystemClock.sleep(SCROLL_SETTLE_MS)
        device.pressBack()
        SystemClock.sleep(CARD_SETTLE_MS)
    }

    companion object {
        const val TARGET_PACKAGE = "com.xjtu.toolbox"
        private const val SETTLE_MS = 3_000L
        private const val SCROLL_SETTLE_MS = 700L
        private const val SWIPE_STEPS = 12
        private const val TAB_SETTLE_MS = 1_200L
        private const val CARD_SETTLE_MS = 1_200L

        /** 原地 swipe 的步数：每步约 5ms，200 步 ≈ 1 秒，够系统判成长按。 */
        private const val LONG_PRESS_STEPS = 200

        /** 底栏所在高度、五格中心（占屏幕宽高的比例，按 1280×2772 的实机量的）。 */
        private const val BAR_Y = 0.94f
        private val BAR_X = listOf(0.157f, 0.33f, 0.5f, 0.67f, 0.842f)

        /** 首页第一排第一张卡的中心。 */
        private const val CARD_X = 0.15f
        private const val CARD_Y = 0.52f
    }
}
