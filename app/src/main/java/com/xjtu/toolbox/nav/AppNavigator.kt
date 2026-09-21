package com.xjtu.toolbox.nav

import android.util.Log
import com.xjtu.toolbox.Routes
import top.yukonga.miuix.kmp.nav.core.NavBackStack

/**
 * 包一层 miuix-nav 的返回栈，让调用方继续用路由字符串跳转。
 *
 * 换导航之前全项目有 18 处 `navController.navigate(...)`，参数都是 [Routes] 里的字符串；
 * 保留 `navigate(String)` 和 `popBackStack()` 这两个名字，这些调用点基本只要换个接收者。
 *
 * @param onShowPaymentCode 付款码不进返回栈（见 [AppRoute] 的说明），由导航层外面显示。
 */
class AppNavigator(
    val backStack: NavBackStack,
    private val onShowPaymentCode: () -> Unit,
) {
    /** 栈顶页面的路由字符串。栈空（理论上不会）时当作首页。 */
    val currentId: String
        get() = (backStack.lastOrNull() as? AppRoute)?.id ?: Routes.MAIN

    /**
     * 按路由字符串跳转。解析不了的字符串写日志、原地不动，**不闪退**：
     * 它可能来自旧版本存下的快捷方式或者一条过时的深链。
     */
    fun navigate(id: String) {
        if (id == Routes.PAYMENT_CODE) {
            onShowPaymentCode()
            return
        }
        val route = appRouteOf(id)
        if (route == null) {
            Log.w("AppNavigator", "未知路由，忽略：$id")
            return
        }
        navigate(route)
    }

    /**
     * 跳转到 [route]。
     *
     * - 栈顶已经是它：什么都不做（原来的 `launchSingleTop` 语义，也挡住连点两下）；
     * - 它已经在栈里、但不在栈顶：退回到它。miuix-nav 要求栈里每一页的键都不同，
     *   同一个路由推两次会直接抛异常，所以不能像原来那样再压一层；
     * - 否则压栈。
     */
    fun navigate(route: AppRoute) {
        when {
            backStack.lastOrNull() == route -> Unit
            route in backStack -> popUntil { it == route }
            else -> backStack.add(route)
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
