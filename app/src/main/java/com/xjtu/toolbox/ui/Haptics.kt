package com.xjtu.toolbox.ui

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 触感反馈（PR T，计划 §11）。
 *
 * §11.1：miuix 组件（Switch/Checkbox/RadioButton/Slider/PullToRefresh/NumberPicker，还有
 * `utils/ScrollEndHaptic`）内部已经自带触感，这个文件只管 miuix 没覆盖到的场景——具体每一处
 * 埋在哪，见调用处注释和收尾报告的"埋点"一节，不要在那些场景上重复加，否则会震两次。
 *
 * 降级链（§11.2）：`VibrationEffect.startComposition()`（minSdk=31 全覆盖，不需要版本判断）
 * 探测到的基本振动 → `View.performHapticFeedback` → Compose 自己的 `LocalHapticFeedback`。
 * 不同厂商支持的基本振动不一样，第一层的探测结果只和硬件有关、不会中途变化，缓存在进程里。
 */

private const val PREFS_NAME = "haptics_prefs"
private const val KEY_ENABLED = "enabled"

/** 触感总开关的偏好存储，独立于 `CredentialStore`（那是热点文件，不在这里碰）。 */
object HapticsPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}

/** 页面里只关心"这是什么场景"的语义化触感，不直接碰 Vibrator。 */
enum class HapticFeel { TICK, CLICK, LOW_TICK, SUCCESS, ERROR }

/**
 * 纯逻辑部分，抽出来单独测：总开关关了就不震；开关开着时只在明确的静音模式下跳过——
 * 勿扰模式很多机型仍映射成 [AudioManager.RINGER_MODE_VIBRATE]，那种情况下用户其实是
 * 主动想要振动反馈的，不该连这个也一起哑掉。`ringerMode` 传 null 表示拿不到 AudioManager，
 * 这种情况下不因为读不到状态就拒绝震动。
 */
internal fun shouldPlayHaptic(prefsEnabled: Boolean, ringerMode: Int?): Boolean {
    if (!prefsEnabled) return false
    return ringerMode != AudioManager.RINGER_MODE_SILENT
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

/** Compose 入口：`val haptics = rememberHaptics()`，页面里只调 [HapticsController] 的语义化方法。 */
@Composable
fun rememberHaptics(): HapticsController {
    val context = LocalContext.current
    val view = LocalView.current
    val composeHaptics = LocalHapticFeedback.current
    return remember(context, view) { HapticsController(context, view, composeHaptics) }
}

class HapticsController internal constructor(
    private val context: Context,
    private val view: View,
    private val composeHaptics: HapticFeedback,
) {
    private val vibrator: Vibrator? by lazy {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    }

    fun tick() = play(HapticFeel.TICK)
    fun click() = play(HapticFeel.CLICK)
    fun lowTick() = play(HapticFeel.LOW_TICK)
    fun success() = play(HapticFeel.SUCCESS)
    fun error() = play(HapticFeel.ERROR)

    private fun enabled(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return shouldPlayHaptic(
            prefsEnabled = HapticsPrefs.isEnabled(context),
            ringerMode = audioManager?.ringerMode,
        )
    }

    private fun play(feel: HapticFeel) {
        // 调用方大多在 Compose 回调 / 主线程里，但课表这类"加载完成"的成功触感是从
        // IO 协程里触发的（见 ScheduleScreen.kt 的 paintCourses）。playViewFallback 里的
        // View.performHapticFeedback 必须在主线程调，这里统一兜底切一下，调用方不用操心。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { playOnMainThread(feel) }
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

/**
 * 独立的触感开关设置项，主会话接进设置页「外观」组（§11.2）。
 * 偏好存在本文件自己的 [HapticsPrefs] 里，不依赖 `CredentialStore`。
 */
@Composable
fun HapticsSettingItem() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(HapticsPrefs.isEnabled(context)) }
    SwitchPreference(
        checked = enabled,
        onCheckedChange = {
            enabled = it
            HapticsPrefs.setEnabled(context, it)
        },
        title = "触感反馈",
        summary = if (enabled) "切周、加载完成等场景有轻触感" else "已关闭；miuix 控件自带的触感不受这个开关影响",
        startAction = { HapticsSettingIcon() },
    )
}

/**
 * 和 `SettingsScreen.kt` 里私有的 `SettingsIcon` 视觉上保持一致（同样的圆形色块 + 18dp 图标），
 * 但那是热点文件里的私有 composable，不能跨文件复用，这里自己起一份，避免依赖胶合之后才有的东西。
 */
@Composable
private fun HapticsSettingIcon() {
    val color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
    }
}
