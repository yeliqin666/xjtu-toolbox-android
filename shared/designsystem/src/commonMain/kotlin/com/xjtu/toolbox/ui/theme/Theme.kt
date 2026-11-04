package com.xjtu.toolbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.ui.HapticsPrefs
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * @param darkModeOverride "system" | "light" | "dark" — 手动覆盖系统深色模式
 * @param dynamicColor 跟随系统壁纸 / 调色盘取色（Monet）
 * @param customColorHex 浅色模式的自选主题色（`#RRGGBB`），空串表示没选
 * @param customDarkColorHex 深色模式的自选主题色；空串表示没单独选过，跟着 [customColorHex] 走
 */
@Composable
fun XJTUToolBoxTheme(
    darkModeOverride: String = "system",
    dynamicColor: Boolean = false,
    customColorHex: String = "",
    customDarkColorHex: String = "",
    applySystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (darkModeOverride) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    // 明暗两档各用各的自选色：哪一档没选过就用另一档那个（老版本只存了一个色，也走这条路）。
    // 明暗是先算出来的，所以这里不能再让 miuix 自己按系统判断（MonetSystem），得显式给每一档的色。
    val seed = remember(customColorHex, customDarkColorHex, darkTheme) {
        val light = SettingsStore.normalizeThemeColor(customColorHex)
        val dark = SettingsStore.normalizeThemeColor(customDarkColorHex)
        val hex = if (darkTheme) dark.ifEmpty { light } else light.ifEmpty { dark }
        hex.takeIf { it.isNotEmpty() }?.removePrefix("#")?.toLong(16)?.let { Color(0xFF000000 or it) }
    }
    val monet = dynamicColor || seed != null
    val mode = when {
        monet && darkTheme -> ColorSchemeMode.MonetDark
        monet -> ColorSchemeMode.MonetLight
        darkTheme -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.Light
    }
    val controller = remember(mode, seed) { ThemeController(colorSchemeMode = mode, keyColor = seed) }
    if (applySystemBars) PlatformSystemBars(darkTheme)
    val platformHaptics = LocalHapticFeedback.current
    val gatedHaptics = remember(platformHaptics) { GatedHapticFeedback(platformHaptics) }
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme, LocalHapticFeedback provides gatedHaptics) {
        MiuixTheme(controller = controller) {
            val colors = when {
                !monet -> MiuixTheme.colorScheme
                // 系统取色（dynamicColor）背后没有「用户挑的颜色」，照旧用 Monet 那套
                seed == null -> remapMonetColorsToMiuix(MiuixTheme.colorScheme, darkTheme)
                else -> remapMonetColorsToMiuix(MiuixTheme.colorScheme, darkTheme).withExactPrimary(seed, darkTheme)
            }
            MiuixTheme(
                colors = if (darkTheme) colors else colors.copy(surface = LightPageBackground),
                content = content,
            )
        }
    }
}

/**
 * 系统触感先过设置里的「触感反馈」开关。miuix 的开关、滑条、下拉刷新、滚到底的回弹
 * 都直接调 LocalHapticFeedback，不经 [com.xjtu.toolbox.ui.rememberHaptics]，不拦就关不掉。
 */
private class GatedHapticFeedback(private val inner: HapticFeedback) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (HapticsPrefs.isEnabled()) inner.performHapticFeedback(hapticFeedbackType)
    }
}

/** 浅色模式的页面底色；卡片仍是 surfaceVariant（白）。 */
private val LightPageBackground = Color(0xFFF0F1F3)

/**
 * 自选主题色：用户挑的就是主题色本身，不再拿它当种子重新调一档色阶。
 *
 * MIUIX 的 Monet 把 keyColor 当种子，按明暗各推一档 tone（浅色、深色取的不是同一个亮度），
 * 于是「我选了深红」实际画出来是另一个红，挑色和结果对不上。这里保留 Monet 算出来的灰阶、
 * 卡片底和次要色，只把跟主题色同源的四个角色换成原色。
 *
 * 主题色上的字不按颜色亮度算对比度，只跟明暗走：深色模式白字、浅色模式黑字。挑什么色
 * 都不影响字色（这是维护者定的口径），所以挑了极端色时对比度好坏由用户自己看着办。
 */
private fun Colors.withExactPrimary(primary: Color, dark: Boolean): Colors {
    val onPrimary = if (dark) Color.White else Color.Black
    return copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary,
        onPrimaryContainer = onPrimary,
    )
}

/**
 * Monet / MD3 按同名角色灌进 MIUIX，会把「灰页 / 白卡片」对调。
 *
 * MIUIX 浅色默认：`surface` 是页面灰（`#F7F7F7`），`surfaceVariant` / `surfaceContainer`
 * 是卡片白。MD3 浅色正好相反：`surface` 近白，`surfaceContainer` 才是略深的灰底，
 * `surfaceVariant` 还带着壁纸色相。一对一映射后 TopAppBar（默认 `surface`）变纯白，
 * 下面的 Tab / 卡片（`surfaceVariant`）变成淡紫，顶栏看起来像被切开。
 *
 * 深色同样不用 MD3 的 `surfaceVariant`（tone 30，过亮），卡片改走 `surfaceContainer`。
 */
private fun remapMonetColorsToMiuix(colors: Colors, dark: Boolean): Colors {
    return if (dark) {
        colors.copy(
            surfaceVariant = colors.surfaceContainer,
        )
    } else {
        colors.copy(
            surface = colors.surfaceContainer,
            surfaceVariant = colors.surface,
            surfaceContainer = colors.surface,
        )
    }
}
