package com.xjtu.toolbox.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalComposeUiApi::class)
class CustomThemeTest {
    /**
     * 用户挑的色就是主题色本身：明暗两档各用各的，不再按种子色重新推一档色阶；
     * 主题色上的字只看明暗（深色白字、浅色黑字），不按颜色亮度算对比度。
     */
    @Test
    fun customColorsAreUsedVerbatimInBothModes() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                for (mode in listOf("light", "dark")) {
                    for (hex in listOf("#B73945", "#208A77", "#000000", "#FFFFFF")) {
                        var primary: Color? = null
                        var foreground: Color? = null
                        // 另一档给一个明显不同的色，用来确认两档不会互相串
                        val lightHex = if (mode == "light") hex else "#208A77"
                        val darkHex = if (mode == "dark") hex else "#B73945"
                        val scene = ImageComposeScene(320, 140, Density(1f)) {
                            XJTUToolBoxTheme(
                                darkModeOverride = mode,
                                customColorHex = lightHex,
                                customDarkColorHex = darkHex,
                                applySystemBars = false,
                            ) {
                                val colors = MiuixTheme.colorScheme
                                SideEffect { primary = colors.primary; foreground = colors.onPrimary }
                                Column(Modifier.fillMaxSize().background(colors.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("主题预览 · $mode · $hex", color = colors.onSurface)
                                    Box(Modifier.fillMaxWidth().background(colors.primary).padding(12.dp)) {
                                        Text("应用主题色", color = colors.onPrimary)
                                    }
                                }
                            }
                        }
                        try {
                            val image = scene.render(0)
                            val expected = Color(0xFF000000 or hex.drop(1).toLong(16))
                            assertEquals(expected, assertNotNull(primary), "$mode $hex 应当原样用作主题色")
                            assertEquals(
                                if (mode == "dark") Color.White else Color.Black,
                                assertNotNull(foreground),
                                "$mode 的主题色上，字色只跟明暗走",
                            )
                            val out = File("build/reports/theme/${mode}-${hex.drop(1)}.png")
                            out.parentFile.mkdirs()
                            out.writeBytes(assertNotNull(image.encodeToData()).bytes)
                        } finally { scene.close() }
                    }
                }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
