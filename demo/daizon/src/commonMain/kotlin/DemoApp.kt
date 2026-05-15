import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles

@Composable
fun DemoApp(screen: String = "schedule", darkMode: Boolean = false, cjkFont: FontFamily? = null) {
    val controller = remember {
        ThemeController(if (darkMode) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    val textStyles = remember(cjkFont) {
        if (cjkFont != null) defaultTextStyles().withFont(cjkFont) else defaultTextStyles()
    }
    MiuixTheme(controller = controller, textStyles = textStyles) {
        when (screen) {
            "attendance"  -> AttendanceDemo()
            "campuscard"  -> CampusCardDemo()
            "coupon"      -> CouponDemo()
            "emptyroom"   -> EmptyRoomDemo()
            "score"       -> ScoreDemo()
            else          -> ScheduleDemo()
        }
    }
}

private fun TextStyles.withFont(fontFamily: FontFamily): TextStyles = copy(
    main       = main.copy(fontFamily = fontFamily),
    paragraph  = paragraph.copy(fontFamily = fontFamily),
    body1      = body1.copy(fontFamily = fontFamily),
    body2      = body2.copy(fontFamily = fontFamily),
    button     = button.copy(fontFamily = fontFamily),
    footnote1  = footnote1.copy(fontFamily = fontFamily),
    footnote2  = footnote2.copy(fontFamily = fontFamily),
    headline1  = headline1.copy(fontFamily = fontFamily),
    headline2  = headline2.copy(fontFamily = fontFamily),
    subtitle   = subtitle.copy(fontFamily = fontFamily),
    title1     = title1.copy(fontFamily = fontFamily),
    title2     = title2.copy(fontFamily = fontFamily),
    title3     = title3.copy(fontFamily = fontFamily),
    title4     = title4.copy(fontFamily = fontFamily),
)
