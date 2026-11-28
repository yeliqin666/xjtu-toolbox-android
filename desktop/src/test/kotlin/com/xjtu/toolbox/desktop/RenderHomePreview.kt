package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.home.*
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.settings.SettingsStore
import com.xjtu.toolbox.shell.*
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon

/** Offline previews of the real shared home screen; values below are demonstration fixtures. */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "build/home-preview").apply { mkdirs() }
    System.setProperty("xjtu.toolbox.keyValueRoot", File(output, "preferences").absolutePath)
    var opened: AppRoute? = null
    val fixture = object : HomeHost {
        override val honoredCards = HomeCard.entries.toSet()
        override val audience = AccountType.UNDERGRADUATE
        override val greetingName = ""
        override val dateLabel = "10月21日 · 周三"
        override val weekNumber = 8
        override val nextUp = HomeNextUp(
            icon = HomeIcons.of(AppRoute.Schedule), title = "大学物理", timeLabel = "14:00",
            timeCaption = "至 15:50", eta = "32 分钟后", detail = "中二 · 1201", route = AppRoute.Schedule,
        )
        override val glances = listOf(
            HomeGlanceItem(HomeIcons.of(AppRoute.CampusCard), "校园卡", "128.60", number = 128.6,
                caption = "今日消费 ¥18.50", route = AppRoute.CampusCard),
            HomeGlanceItem(HomeIcons.of(AppRoute.Schedule), "还有 5 天", "高等数学 · 期中考试",
                route = AppRoute.Schedule),
        )
        override val stats = mapOf(
            AppRoute.Schedule.id to HomeStat("今天 3 门课"),
            AppRoute.SchoolCalendar.id to HomeStat("第 8 教学周"),
            AppRoute.JwappScore.id to HomeStat("GPA 3.82", "已修 86 学分"),
            AppRoute.Library.id to HomeStat("已预约 · 14:00"),
            AppRoute.Lms().id to HomeStat("2 项作业待完成"),
            AppRoute.Notification.id to HomeStat("3 条新通知"),
            AppRoute.Attendance.id to HomeStat("本周出勤正常"),
        )
        override fun onRoute(route: AppRoute) { opened = route }
    }
    fun find(root: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
        if (predicate(root)) root else root.children.firstNotNullOfOrNull { find(it, predicate) }
    val tabs = ShellTabs(listOf(
        ShellTab("HOME", "首页", Icons.Default.Home, ShellTarget.App(AppRoute.Main)),
        ShellTab("COURSES", "日程", Icons.Default.CalendarMonth, ShellTarget.App(AppRoute.Schedule)),
        ShellTab("PIDAI", "屁岱", Icons.Default.SmartToy, ShellTarget.App(AppRoute.Agent)),
        ShellTab("TOOLS", "学辅", Icons.AutoMirrored.Filled.MenuBook, ShellTarget.Custom("zyxf")),
        ShellTab("PROFILE", "我的", Icons.Default.Person, ShellTarget.Custom("profile")),
    ))

    /** [fontScale] 是系统字号倍率：手机设置里「大」档一般 1.15~1.3，超大 1.5 以上。 */
    data class Preview(val name: String, val width: Int, val height: Int, val dark: Boolean = false, val fontScale: Float = 1f)
    for (spec in listOf(
        Preview("home-mobile.png", 412, 915),
        // 系统字号调大后的首页：卡片该把字收进格子里，而不是切掉半行（#622）
        Preview("home-large-font.png", 412, 915, fontScale = 1.3f),
        Preview("home-large-font-xl.png", 412, 915, fontScale = 1.5f),
        Preview("home-full.png", 412, 1690),
        Preview("home-dark.png", 412, 1690, dark = true),
        Preview("home-wide.png", 1100, 1150),
        Preview("home-compact.png", 360, 915),
        Preview("home-compact-status.png", 360, 915),
    )) {
        val previewHost = if (spec.name == "home-compact-status.png") object : HomeHost by fixture {
            override val greetingName = "李同学"
            override val statusLine = HomeStatusLine("校内", "8/10 就绪", HomeTone.NORMAL)
        } else fixture
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(spec.width, spec.height, Density(1f, spec.fontScale)) {
                XJTUToolBoxTheme(darkModeOverride = if (spec.dark) "dark" else "light") {
                    CompositionLocalProvider(LocalIsWideLayout provides (spec.width >= 840)) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                            if (spec.height == 915) {
                                AppShell(
                                    nav = ShellNavState(tabs, ShellTarget.App(AppRoute.Main), {}),
                                    pageHost = ShellPageHost.CURRENT_ONLY,
                                    contentWindowInsets = WindowInsets(0),
                                    glassStyle = false,
                                    bottomInset = 0.dp,
                                    page = { _, insets -> HomeScreen(previewHost,
                                        extraBottomPadding = insets.bottomBarReserve,
                                        topActions = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                listOf(Icons.Default.Search, Icons.Default.QrCodeScanner,
                                                    Icons.Default.NotificationsNone).forEach { icon ->
                                                    Box(Modifier.size(36.dp).background(
                                                        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                                        CircleShape), contentAlignment = Alignment.Center) {
                                                        Icon(icon, null, modifier = Modifier.size(19.dp))
                                                    }
                                                }
                                            }
                                        }) },
                                )
                            } else HomeScreen(previewHost)
                        }
                    }
                }
            }
            scene.render(0)
        }
        repeat(8) { frame ->
            Thread.sleep(100)
            SwingUtilities.invokeAndWait { scene.render((frame + 1) * 200_000_000L) }
        }
        SwingUtilities.invokeAndWait {
            val png = requireNotNull(scene.render(2_000_000_000L).encodeToData()).bytes
            File(output, spec.name).writeBytes(png)
            if (spec.name == "home-mobile.png") {
                val screenshot = ImageIO.read(ByteArrayInputStream(png))
                ImageIO.write(screenshot.getSubimage(0, 0, spec.width, 100), "png", File(output, "home-header.png"))
            }
            val root = scene.semanticsOwners.first().rootSemanticsNode
            val card = requireNotNull(find(root) { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "校园卡" }
            }) { "Campus card entry missing from ${spec.name}" }
            card.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
            check(opened == AppRoute.CampusCard)
            if (spec.height >= 1600) {
                for (service in AppServices.homeFor(AccountType.UNDERGRADUATE)) {
                    check(find(root) { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                        .any { it.text == service.title } } != null) { "Missing service: ${service.title}" }
                }
            }
        }
        if (spec.name in setOf("home-mobile.png", "home-compact.png", "home-dark.png", "home-wide.png")) {
            SwingUtilities.invokeAndWait {
                val root = scene.semanticsOwners.first().rootSemanticsNode
                val hero = requireNotNull(find(root) {
                    it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "设置卡片外观"
                }) { "Hero appearance long press missing" }
                hero.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke()
                check(opened == AppRoute.CampusCard) { "Long press must not navigate" }
            }
            repeat(8) { frame ->
                Thread.sleep(100)
                SwingUtilities.invokeAndWait { scene.render(3_000_000_000L + frame * 100_000_000L) }
            }
            SwingUtilities.invokeAndWait {
                check(scene.semanticsOwners.any { owner -> find(owner.rootSemanticsNode) { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "首页卡片外观" }
                } != null }) { "Appearance dialog did not open" }
                File(output, spec.name.replace("home-", "hero-settings-")).writeBytes(
                    requireNotNull(scene.render(4_000_000_000L).encodeToData()).bytes,
                )
            }
        }
        SwingUtilities.invokeAndWait { scene.close() }
        println("Rendered ${File(output, spec.name).absolutePath}")
    }
    exitProcess(0)
}
