package com.xjtu.toolbox.ui

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Android 侧的真实现 —— 与搬迁前 :app `ui/Haptics.kt` **逐字一致**（只把 `Context`/`View`
 * 收进了本文件、并把入口改成 actual）。
 *
 * 降级链（§11.2）：`VibrationEffect.startComposition()`（minSdk=31 全覆盖，不需要版本判断）
 * 探测到的基本振动 → `View.performHapticFeedback` → Compose 自己的 `LocalHapticFeedback`。
 * 不同厂商支持的基本振动不一样，第一层的探测结果只和硬件有关、不会中途变化，缓存在进程里。
 */
@Composable
actual fun rememberHaptics(): HapticsController {
    val context = LocalContext.current
    val view = LocalView.current
    val composeHaptics = LocalHapticFeedback.current
    return remember(context, view) { AndroidHapticsController(context, view, composeHaptics) }
}

private class AndroidHapticsController(
    private val context: Context,
    private val view: View,
    private val composeHaptics: HapticFeedback,
) : HapticsController {

    private val vibrator: Vibrator? by lazy {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    }

    override fun tick() = play(HapticFeel.TICK)
    override fun lowTick() = play(HapticFeel.LOW_TICK)
    override fun success() = play(HapticFeel.SUCCESS)
    override fun error() = play(HapticFeel.ERROR)

    private fun enabled(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return shouldPlayHaptic(
            prefsEnabled = HapticsPrefs.isEnabled(),
            ringerMode = audioManager?.ringerMode,
        )
    }

    private fun play(feel: HapticFeel) {
        // 调用方大多在 Compose 回调 / 主线程里，但课表这类"加载完成"的成功触感是从
        // IO 协程里触发的（见 ScheduleScreen.kt 的 paintCourses）。playViewFallback 里的
        // View.performHapticFeedback 必须在主线程调，这里统一兜底切一下，调用方不用操心。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { playOnMainThread(feel) }
            return
        }
        playOnMainThread(feel)
    }

    private fun playOnMainThread(feel: HapticFeel) {
        if (!enabled()) return
        val v = vibrator
        if (v != null && PrimitiveSupport.isSupported(v)) {
            if (playPrimitive(v, feel)) return
        }
        playViewFallback(feel)
    }

    private fun playPrimitive(v: Vibrator, feel: HapticFeel): Boolean = try {
        val composition = VibrationEffect.startComposition()
        when (feel) {
            HapticFeel.TICK -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
            HapticFeel.CLICK -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
            HapticFeel.LOW_TICK -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            HapticFeel.SUCCESS -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
            HapticFeel.ERROR -> {
                // "两下一组"：两次 CLICK 中间隔 60ms，和普通单下点击区分开，总时长仍在 100ms 左右。
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 60)
            }
        }
        v.vibrate(composition.compose())
        true
    } catch (_: Exception) {
        false
    }

    private fun playViewFallback(feel: HapticFeel) {
        val constant = when (feel) {
            HapticFeel.TICK, HapticFeel.LOW_TICK -> HapticFeedbackConstants.CLOCK_TICK
            HapticFeel.CLICK -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticFeel.SUCCESS -> HapticFeedbackConstants.CONFIRM
            HapticFeel.ERROR -> HapticFeedbackConstants.REJECT
        }
        val handled = try {
            view.performHapticFeedback(constant)
        } catch (_: Exception) {
            false
        }
        if (!handled) {
            // 最后一层兜底：机型再老，Compose 自己的触感总归有。
            val type = if (feel == HapticFeel.ERROR) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
            composeHaptics.performHapticFeedback(type)
        }
    }
}

/** 基本振动支持探测，和硬件绑定，进程存活期内缓存一次够了。 */
private object PrimitiveSupport {
    @Volatile
    private var cached: Boolean? = null

    fun isSupported(vibrator: Vibrator): Boolean {
        cached?.let { return it }
        val supported = try {
            vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            )
        } catch (_: Exception) {
            false
        }
        cached = supported
        return supported
    }
}
