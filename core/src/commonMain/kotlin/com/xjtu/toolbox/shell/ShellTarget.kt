package com.xjtu.toolbox.shell

import com.xjtu.toolbox.nav.AppRoute

/** 壳的导航模型：三端同壳说的第一件事 —— 「我现在在哪一页」。 */
sealed interface ShellTarget {

    /** 共享路由表里的一页：屏在 `:core`（或由这一端自己提供）。 */
    data class App(val route: AppRoute) : ShellTarget

    /** 「更多 / 全部页面」那一档：这一端把它真能打开的路由列成一页（桌面底栏的第六格）。 */
    data object AllPages : ShellTarget

    /** 端自己的页：[id] 由端自己解释（`:core` 不认识它，只当它是这一格的标识）。 */
    data class Custom(val id: String) : ShellTarget
}
