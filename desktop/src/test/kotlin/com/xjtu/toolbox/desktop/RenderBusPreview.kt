package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.xjtu.toolbox.bus.BusRun
import com.xjtu.toolbox.bus.BusScreen
import com.xjtu.toolbox.bus.BusSource
import com.xjtu.toolbox.bus.BusStop
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 校车页离线预览：手机竖屏、手机横屏、平板、深色。班次是编的。 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "build/bus-preview").apply { mkdirs() }
    fun run(time: String, line: String, cat: String, used: Int, seats: Int, vararg stops: String) = BusRun(
        line, time, cat, seats, used, "陕A00000", stops.first(), stops.last(),
        stops.mapIndexed { i, s -> BusStop(s, time.takeIf { i == 0 }, if (i == 1) "$s 路对面候车" else "") },
    )
    val port = "创新港班车"
    val city = "兴庆班车"
    val data = listOf(
        run("06:20", "1号线(三村西门-创新港)", port, 7, 49, "三村西门", "阳光小区", "曲江校区", "曲江宜居", "创新港"),
        run("06:20", "1号线(三村西门直通车)", port, 26, 49, "三村西门", "创新港"),
        run("07:30", "3号线（思源中心-创新港）", port, 12, 49, "思源中心", "北池头公交站", "新开门公交站", "创新港"),
        run("07:30", "4号线（三村西门-创新港）", port, 49, 49, "三村西门", "交大东南门", "创新港"),
        run("09:20", "兴庆B2线（雁塔-兴庆直达）", city, 1, 35, "医学行政楼", "思源南广场"),
        run("12:15", "6A号线（创新港-财经南院）", port, 18, 49, "创新港", "西三爻", "明德门小区", "财经南院"),
        run("12:30", "1号线（思源中心-创新港）", port, 44, 49, "思源中心", "阳光小区", "曲江校区", "创新港"),
        run("18:15", "4号线（创新港-三村西门）", port, 43, 49, "创新港", "交大东南门", "三村西门"),
        run("21:15", "8号线（创新港-思源中心）", port, 1, 49, "创新港", "医学北院", "曲江校区", "思源中心"),
    )
    val source = object : BusSource { override suspend fun runs(date: LocalDate) = data }
    fun find(root: SemanticsNode, text: String): SemanticsNode? =
        if (root.config.getOrNull(SemanticsActions.OnClick) != null &&
            root.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text }) root
        else root.children.firstNotNullOfOrNull { find(it, text) }

    fun capture(name: String, width: Int, height: Int, dark: Boolean = false, clicks: List<String> = emptyList()) {
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(width, height, Density(1f)) {
                XJTUToolBoxTheme(darkModeOverride = if (dark) "dark" else "light") {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) { BusScreen(source, onBack = {}) }
                }
            }
            scene.render(0)
        }
        repeat(8) { frame ->
            Thread.sleep(100)
            SwingUtilities.invokeAndWait { scene.render((frame + 1) * 200_000_000L) }
        }
        clicks.forEachIndexed { step, label ->
            SwingUtilities.invokeAndWait {
                val entry = requireNotNull(scene.semanticsOwners.reversed().firstNotNullOfOrNull { find(it.rootSemanticsNode, label) }) { "找不到 $label" }
                entry.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
            }
            repeat(8) { frame ->
                Thread.sleep(100)
                SwingUtilities.invokeAndWait { scene.render(2_000_000_000L + step * 1_000_000_000L + frame * 100_000_000L) }
            }
        }
        SwingUtilities.invokeAndWait {
            File(output, name).writeBytes(requireNotNull(scene.render(9_000_000_000L).encodeToData()).bytes)
            scene.close()
        }
    }
    capture("today.png", 412, 915)
    capture("phone.png", 412, 915, clicks = listOf("明天"))
    capture("phone-expanded.png", 412, 915, clicks = listOf("明天", "三村西门 → 创新港"))
    capture("phone-dark.png", 412, 915, dark = true, clicks = listOf("明天"))
    capture("landscape.png", 915, 412, clicks = listOf("明天"))
    capture("tablet.png", 1280, 800, clicks = listOf("明天"))
    exitProcess(0)
}
