package com.xjtu.toolbox.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.basic.Icon

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

/**
 * 与设置页其余行一致的纯图标：24dp、不带圆底、直接填调色板里的粉色
 * （与设置页同一套固定色，不跟主题色——主题色设成黑色时这里会变成一颗灰黑图标）。
 */
@Composable
private fun HapticsSettingIcon() {
    val color = Color(0xFFEC407A)
    Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
    Spacer(Modifier.width(16.dp))
}
