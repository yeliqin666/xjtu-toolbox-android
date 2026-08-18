package com.xjtu.toolbox.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val LocalIsDarkTheme = compositionLocalOf { false }

/**
 * @param darkModeOverride "system" | "light" | "dark" — 手动覆盖系统深色模式
 * @param dynamicColor 跟随系统壁纸 / 调色盘取色（Monet）
 */
@Composable
fun XJTUToolBoxTheme(
    darkModeOverride: String = "system",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val mode = if (dynamicColor) {
        when (darkModeOverride) {
            "light" -> ColorSchemeMode.MonetLight
            "dark" -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }
    } else {
        when (darkModeOverride) {
            "light" -> ColorSchemeMode.Light
            "dark" -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }
    }
    val controller = remember(darkModeOverride, dynamicColor) { ThemeController(mode) }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (darkModeOverride) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MiuixTheme(controller = controller) {
            if (!dynamicColor) {
                content()
            } else {
                MiuixTheme(
                    colors = remapMonetColorsToMiuix(MiuixTheme.colorScheme, darkTheme),
                    content = content,
                )
            }
        }
    }
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