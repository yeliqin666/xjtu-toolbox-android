package com.xjtu.toolbox.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldPlayHaptic] 是触感开关唯一的纯逻辑，其余部分都要碰真实 Vibrator/View，测不了。
 *
 * 从 :app 的 JUnit4 版搬进 `commonTest`，并把 `AudioManager.RINGER_MODE_*` 换成它们的字面值
 * （commonMain 不认识 AudioManager）—— 这样同一份断言在 `:core:jvmTest` 与 wasm 目标上都能跑，
 * 触感这条逻辑从此有了跨端回归网，而不是只活在 Android 单测里。
 */
class HapticsTest {

    private companion object {
        const val RINGER_MODE_SILENT = 0
        const val RINGER_MODE_VIBRATE = 1
        const val RINGER_MODE_NORMAL = 2
    }

    @Test
    fun disabledInPrefs_neverPlays() {
        assertFalse(shouldPlayHaptic(prefsEnabled = false, ringerMode = RINGER_MODE_NORMAL))
        assertFalse(shouldPlayHaptic(prefsEnabled = false, ringerMode = null))
    }

    @Test
    fun enabledAndNormalRinger_plays() {
        assertTrue(shouldPlayHaptic(prefsEnabled = true, ringerMode = RINGER_MODE_NORMAL))
    }

    @Test
    fun enabledAndVibrateRinger_stillPlays() {
        // 勿扰/静音开关很多机型映射成 VIBRATE 而不是 SILENT，这种情况下用户是主动要振动反馈的。
        assertTrue(shouldPlayHaptic(prefsEnabled = true, ringerMode = RINGER_MODE_VIBRATE))
    }

    @Test
    fun enabledAndSilentRinger_doesNotPlay() {
        assertFalse(shouldPlayHaptic(prefsEnabled = true, ringerMode = RINGER_MODE_SILENT))
    }

    @Test
    fun enabledAndUnknownRingerState_playsByDefault() {
        // 拿不到 AudioManager 时不该因为读不到状态就拒绝震动。
        assertTrue(shouldPlayHaptic(prefsEnabled = true, ringerMode = null))
    }
}
