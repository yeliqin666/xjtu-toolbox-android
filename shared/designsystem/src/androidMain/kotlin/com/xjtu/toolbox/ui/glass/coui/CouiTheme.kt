package com.xjtu.toolbox.ui.glass.coui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 组件实际使用的色彩角色；页面染色由主题单独提供，不混入原生材质。 */
@Immutable
data class CouiColorScheme(
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val container: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outlineVariant: Color,
    val accentOnSurface: Color = accent,
    val menuSurface: Color = container,
    val checkboxAccent: Color = accent,
    val menuCheckboxAccent: Color = accentOnSurface,
    /** 页面底色与弹窗等组件的 surface 分开；自定义色板缺省沿用 surface。 */
    val background: Color = surface,
    /** 列表卡片使用 couiColorCardBackground；与菜单等顶层容器分开。 */
    val card: Color = container,
)

/** Theme.COUI 的蓝色强调色与中性表面；不读取壁纸动态色。 */
object CouiPresets {
    val Light = CouiColorScheme(CouiColor.Day.BLUE, Color.White, Color.White, Color.White,
        Color(0xE6000000), Color(0x8A000000), Color(0x1F000000), background = Color(0xFFF0F1F2))
    val Dark = CouiColorScheme(CouiColor.Night.BLUE, Color.White, Color(0xFF1E1E1E), Color(0xFF333333),
        Color(0xE6FFFFFF), Color(0x8AFFFFFF), Color(0x33FFFFFF), Color(0xFF5C9DFF),
        background = Color.Black, card = Color(0x1AFFFFFF))
}

/** 普通内容预设及可选字体族；Dialog 保留字号与行距，M3 仅适配字体族，native 保留系统字体。 */
@Immutable
data class CouiTypography(
    val body: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Normal,
        platformStyle = PlatformTextStyle(includeFontPadding = true)),
    val label: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp,
        fontWeight = FontWeight.Normal, lineHeight = 16.sp, letterSpacing = 0.4.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true)),
    val menuItem: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp,
        fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true)),
    val chipLabelFontFamily: FontFamily? = null,
    val dialogTitleFontFamily: FontFamily? = null,
    val dialogMessageFontFamily: FontFamily? = null,
    val dialogButtonFontFamily: FontFamily? = null,
    val dialogInputFontFamily: FontFamily? = null,
    val dialogCounterFontFamily: FontFamily? = null,
)

private data class CouiThemeValues(val dark: Boolean, val colors: CouiColorScheme,
    val typography: CouiTypography, val materialTint: Color)

private val LocalCouiTheme = staticCompositionLocalOf<CouiThemeValues?> { null }
/** 宿主已知的主题模式；嵌套 MaterialTheme 改变模式时也应在同一作用域提供。 */
val LocalCouiDarkTheme = staticCompositionLocalOf<Boolean?> { null }
/** Unspecified 表示没有提供内容色，组件在消费处读取当前主题。 */
val LocalCouiContentColor = staticCompositionLocalOf { Color.Unspecified }
val LocalCouiTextStyle = staticCompositionLocalOf { CouiTypography().body }

object CouiTheme {
    val isDark: Boolean
        @Composable get() = LocalCouiDarkTheme.current ?: isSystemInDarkTheme()
    val colorScheme: CouiColorScheme
        @Composable get() = colorSchemeFor(isDark)
    internal val contentColor: Color
        @Composable get() = LocalCouiContentColor.current.takeOrElse { colorScheme.onSurface }
    val typography: CouiTypography
        @Composable get() = LocalCouiTheme.current?.typography ?: Coui.defaultStyle.themeDefaults(isDark).typography

    /** 组件覆盖深浅色时，不复用另一模式的宿主色板。 */
    @Composable
    fun colorSchemeFor(dark: Boolean): CouiColorScheme {
        val local = LocalCouiTheme.current
        if (local != null) return if (local.dark == dark) local.colors else if (dark) CouiPresets.Dark else CouiPresets.Light
        return if (dark == isDark) Coui.defaultStyle.themeDefaults(dark).colorScheme
            else if (dark) CouiPresets.Dark else CouiPresets.Light
    }

    /** 透明表示不做额外页面色相染色；显式材质配置始终由调用方负责。 */
    @Composable
    fun materialTintFor(dark: Boolean): Color {
        val local = LocalCouiTheme.current
        if (local != null) return if (local.dark == dark) local.materialTint else Color.Transparent
        return if (dark == isDark) Coui.defaultStyle.themeDefaults(dark).materialTint else Color.Transparent
    }
}

/**
 * 可选外观上下文。materialTint 默认为透明；Material 宿主可显式传入页面表面色启用项目染色。
 * 不提供主题时使用初始化策略，未初始化则使用原生预设。业务状态及资源生命周期仍由原接口管理。
 */
@Composable
fun CouiTheme(
    darkTheme: Boolean = CouiTheme.isDark,
    colorScheme: CouiColorScheme = if (darkTheme) CouiPresets.Dark else CouiPresets.Light,
    typography: CouiTypography = CouiTypography(),
    materialTint: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCouiTheme provides CouiThemeValues(darkTheme, colorScheme, typography, materialTint),
        LocalCouiDarkTheme provides darkTheme,
    ) { ProvideCouiContent(colorScheme.onSurface, typography.body, mergeTextStyle = false, content = content) }
}

@Composable
internal fun ProvideCouiTextStyle(style: TextStyle, content: @Composable () -> Unit) {
    ProvideCouiContent(textStyle = style, content = content)
}
