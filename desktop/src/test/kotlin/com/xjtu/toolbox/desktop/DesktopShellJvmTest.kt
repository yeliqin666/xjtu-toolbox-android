package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.shell.ShellTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 桌面这一端接共享壳的那条缝（`DesktopShell.kt`）：五格、以及「当前页」两个方向的换算。 */
class DesktopShellJvmTest {

    @Test
    fun `五格的顺序文字与底栏从左到右一致`() {
        assertEquals(
            listOf("首页", "校历", "图书馆", "黄页", "游戏"),
            desktopTabs().tabs.map { it.label },
        )
        assertEquals(
            listOf("main", "school_calendar", "library", "yellow_page", "games"),
            desktopTabs().ids,
        )
    }

    @Test
    fun `五格的 id 与目标都不重复（一格指向一页）`() {
        val tabs = desktopTabs()
        assertTrue(tabs.ids.all { it.isNotBlank() }, "id 不能是空串：${tabs.ids}")
        assertEquals(tabs.size, tabs.ids.toSet().size, "id 重复了：${tabs.ids}")
        val targets = tabs.tabs.map { it.target }
        assertEquals(tabs.size, targets.toSet().size, "两格指向了同一页：$targets")
    }

    @Test
    fun `每一格都是共享路由表里的一屏，dock 里没有「全部」`() {
        assertEquals(
            listOf(
                ShellTarget.App(AppRoute.Main),
                ShellTarget.App(AppRoute.SchoolCalendar),
                ShellTarget.App(AppRoute.Library),
                ShellTarget.App(AppRoute.YellowPage),
                ShellTarget.App(AppRoute.Games),
            ),
            desktopTabs().tabs.map { it.target },
        )
    }

    @Test
    fun `当前页两个方向往返一致`() {
        listOf(
            DesktopTarget.App(AppRoute.Main),
            DesktopTarget.App(AppRoute.Fitness),
            DesktopTarget.App(AppRoute.Schedule),
        ).forEach { target ->
            assertEquals(target, target.toShell().toDesktop(), "$target 换算一圈变了")
        }
        assertEquals(AppRoute.Fitness, (DesktopTarget.App(AppRoute.Fitness).toShell() as ShellTarget.App).route)
    }

    @Test
    fun `壳里有而这一端没有的那一档如实返回 null（全部页面 / 端自己的页）`() {
        assertNull(ShellTarget.AllPages.toDesktop())
        assertNull(ShellTarget.Custom("desktop_only").toDesktop())
    }
}
