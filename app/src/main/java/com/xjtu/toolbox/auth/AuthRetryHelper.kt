package com.xjtu.toolbox.auth

import com.xjtu.toolbox.nav.AppRoute

/**
 * 在页面内部捕获 [AuthExpiredException] 时调用：
 * 1. 把 [route] 对应站点的已缓存登录标记为失效；
 * 2. 退出当前页，由导航根部自动重新打开它（途中按需登录，必要时走 MFA），整个过程对用户透明。
 *
 * 页面内典型用法：
 * ```
 * try { ... }
 * catch (e: AuthExpiredException) {
 *     appLoginState.handleAuthExpired(AppRoute.X, onBack)
 * }
 * ```
 */
fun AppLoginState.handleAuthExpired(route: AppRoute, onBack: () -> Unit) {
    markStaleAndRetry(route)
    onBack()
}
