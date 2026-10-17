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

/** 触感反馈。 */

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

/** 纯逻辑：总开关关了就不震。 */
fun shouldPlayHaptic(prefsEnabled: Boolean, ringerMode: Int?): Boolean {
    if (!prefsEnabled) return false
    return ringerMode != RINGER_MODE_SILENT
}

/** 语义化触感控制器。 */
interface HapticsController {
    fun tick()
    fun lowTick()
    fun success()
    fun error()
}

/** Compose 入口：`val haptics = rememberHaptics()`，页面里只调 [HapticsController] 的语义化方法。 */
@Composable
expect fun rememberHaptics(): HapticsController

/** 独立的触感开关设置项。 */
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

/** 与 `SettingsScreen.kt` 私有的 `SettingsIcon` 视觉一致的设置图标。 */
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
