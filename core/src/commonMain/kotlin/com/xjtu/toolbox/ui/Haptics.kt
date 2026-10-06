package com.xjtu.toolbox.ui

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
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.keyValueStore
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 触感反馈（PR T，计划 §11）—— 从 :app 的 `ui/Haptics.kt` 搬进 commonMain，
 * 并把它按「能力」切成平台家族（交接文档 §4 的第 8 族「触感」）。
 *
 * 搬法与交接文档要求的「移植既有实现，不要新造 API」一致：**对外 API 一字未改**
 * （`rememberHaptics()` / `HapticFeel` / `HapticSettingItem`），34 个调用点因为包名不变，
 * import 一行都不用动。变的只有两处实现细节：
 *
 * 1. 偏好存储从 `Context.getSharedPreferences("haptics_prefs")` 换成
 *    [keyValueStore]（Android actual 仍落到同一个 SharedPreferences 文件，行为逐字一致；
 *    jvm 走内存、Web 走 localStorage）。
 * 2. `Context` / `View` / `Vibrator` 这些 Android 类型收进 androidMain 的 actual，
 *    commonMain 只留 [HapticsController] 这个语义接口。
 *
 * 降级链（§11.2）保持原样，见 androidMain 的 actual：
 * `VibrationEffect.startComposition()` 的基本振动 → `View.performHapticFeedback`
 * → Compose 自己的 `LocalHapticFeedback`。
 */

private const val PREFS_NAME = "haptics_prefs"
private const val KEY_ENABLED = "enabled"

/** `android.media.AudioManager.RINGER_MODE_SILENT` 的字面值；commonMain 不认识 AudioManager。 */
private const val RINGER_MODE_SILENT = 0

/** 触感总开关的偏好存储，独立于 `CredentialStore`（那是热点文件，不在这里碰）。 */
object HapticsPrefs {
    private fun store() = keyValueStore(PREFS_NAME)

    fun isEnabled(): Boolean = store().getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        store().putBoolean(KEY_ENABLED, value)
    }
}

/** 页面里只关心"这是什么场景"的语义化触感，不直接碰 Vibrator。 */
enum class HapticFeel { TICK, CLICK, LOW_TICK, SUCCESS, ERROR }

/**
 * 纯逻辑部分，抽出来单独测：总开关关了就不震；开关开着时只在明确的静音模式下跳过——
 * 勿扰模式很多机型仍映射成 `RINGER_MODE_VIBRATE`，那种情况下用户其实是主动想要振动反馈的，
 * 不该连这个也一起哑掉。`ringerMode` 传 null 表示拿不到 AudioManager，
 * 这种情况下不因为读不到状态就拒绝震动。
 *
 * 从 `internal` 放宽为 `public`：它现在住在 :core，而 :app 的单测（`HapticsTest`）
 * 要继续钉这九个分支 —— 这是「跨模块使用」的正常代价，接口本身仍是纯函数。
 */
fun shouldPlayHaptic(prefsEnabled: Boolean, ringerMode: Int?): Boolean {
    if (!prefsEnabled) return false
    return ringerMode != RINGER_MODE_SILENT
}

/**
 * 语义化触感控制器。各端提供自己的 actual（Android 走真实 Vibrator 三级降级，
 * jvm / Web 是空实现 —— 桌面与浏览器可以不震动，UI 无需降级）。
 */
interface HapticsController {
    fun tick()
    fun lowTick()
    fun success()
    fun error()
}

/** Compose 入口：`val haptics = rememberHaptics()`，页面里只调 [HapticsController] 的语义化方法。 */
@Composable
expect fun rememberHaptics(): HapticsController

/**
 * 独立的触感开关设置项，主会话接进设置页「外观」组（§11.2）。
 * 偏好存在本文件自己的 [HapticsPrefs] 里，不依赖 `CredentialStore`。
 */
@Composable
fun HapticsSettingItem() {
    var enabled by remember { mutableStateOf(HapticsPrefs.isEnabled()) }
    SwitchPreference(
        checked = enabled,
        onCheckedChange = {
            enabled = it
            HapticsPrefs.setEnabled(it)
        },
        title = "触感反馈",
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
