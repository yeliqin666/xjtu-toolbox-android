package com.xjtu.toolbox.shell

import com.xjtu.toolbox.nav.AppRoute

/**
 * 壳的**导航模型**：三端同壳说的第一件事 —— 「我现在在哪一页」。
 *
 * ## 为什么要有这一层
 *
 * 在它之前，「当前页」这件事三端各写了一遍：`:app` 用的是底栏枚举 + 43 条 `AppRoute` 的返回栈，
 * 桌面用的是它自己的 `DesktopTarget`（`internal sealed interface`，见
 * `desktop/ToolboxDesktopApp.kt`）。同一个概念两种形状 ⇒ 底栏、tab 集合、壳级插槽都没法共用。
 *
 * 这一层只描述**页**，不描述「怎么画」：[ShellTab] 把某一格指向一个 [ShellTarget]，
 * [AppShell] 按选中的那一格渲染，画什么由各端的 `page` lambda 决定。
 *
 * ## 各端怎么用它（谁注入什么）
 *
 * - `:app`（Android）：只用到 [App]（43 条 `AppRoute` 的屏都在 `:core`）；
 * - `:desktop`：`App` + [AllPages]（底栏第六格「全部」那一页）；
 * - `:web`：与桌面同一套（同源反代那一份），同样只用这两个；
 * - 将来某个端要一个不属于共享路由表的页（例如只有它有的设置页），用 [Custom] —— **别改这一层**。
 *
 * ## 三端共享什么、什么是各端的权利
 *
 * 共享的是**这套形状**与按它分派的渲染；**哪些页真的存在**是各端的权利（[AppRoute] 是共享的
 * 全集，但每一端真能画的是它的子集 —— 桌面那份清单见 `DESKTOP_SUPPORTED_ROUTES`）。
 */
sealed interface ShellTarget {

    /**
     * 共享路由表里的一页：屏在 `:core`（或由这一端自己提供）。
     *
     * 打开它一律走这一端的跳转入口（`:app` 是 `AppRouter.open`），壳不负责压栈 —— 返回栈是
     * 各端的导航实现（Android/桌面是 miuix-nav，web 还在自己的那份壳里）。
     */
    data class App(val route: AppRoute) : ShellTarget

    /**
     * 「更多 / 全部页面」那一档：这一端把它真能打开的路由列成一页（桌面底栏的第六格）。
     *
     * Android 与 Web 都没有这一档 ⇒ 它们的 tab 集合里不会出现它。
     */
    data object AllPages : ShellTarget

    /**
     * 端自己的页：[id] 由**端自己**解释（`:core` 不认识它，只当它是这一格的标识）。
     *
     * 存在的理由是别把三端的形状设计死：桌面/web 以后要加「只有这一端有的页」时，
     * 不需要回来改这个 sealed 接口（一改就要动三端）。
     */
    data class Custom(val id: String) : ShellTarget
}
