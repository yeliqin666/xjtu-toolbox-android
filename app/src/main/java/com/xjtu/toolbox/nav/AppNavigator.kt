package com.xjtu.toolbox.nav

import top.yukonga.miuix.kmp.nav.core.NavBackStack

/**
 * miuix-nav 返回栈上的三个操作：压栈、出栈、退到某一页。
 *
 * 只管栈本身。「去某个地方」要不要先登录、是不是切 tab、付款码之类的覆盖层，
 * 统一在 [com.xjtu.toolbox.main.AppRouter.open] 里决定，别处不要直接调这里的 [navigate]。
 */
class AppNavigator(val backStack: NavBackStack) {

    /** 栈顶页面。栈空（理论上不会）时当作首页。 */
    val current: AppRoute
        get() = backStack.lastOrNull() as? AppRoute ?: AppRoute.Main

    /**
     * 跳转到 [route]。
     *
     * - 栈顶已经是它：什么都不做（原来的 `launchSingleTop` 语义，也挡住连点两下）；
     * - 它已经在栈里、但不在栈顶：退回到它。miuix-nav 要求栈里每一页的键都不同，
     *   同一个路由推两次会直接抛异常，所以不能再压一层；
     * - 否则压栈。
     */
    fun navigate(route: AppRoute) {
        when {
            backStack.lastOrNull() == route -> Unit
            route in backStack -> popUntil { it == route }
            else -> {
                ExpandOrigins.onPush(route)
                backStack.add(route)
            }
        }
    }

    /** 返回上一页。只剩首页时不动，交给系统（退到桌面）。 */
    fun popBackStack(): Boolean = if (backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
        true
    } else {
        false
    }

    /** 一直退到 [predicate] 命中的那一页为止，至少留下首页。 */
    fun popUntil(predicate: (AppRoute) -> Boolean) {
        while (backStack.size > 1 && (backStack.last() as? AppRoute)?.let(predicate) != true) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}
