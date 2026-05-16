package com.xjtu.toolbox.auth

import com.xjtu.toolbox.AppLoginState

/**
 * 在 Screen 内部捕获 [AuthExpiredException] 时调用：
 * 1. 让 AppLoginState 标记此 type 的 cached login 已失效；
 * 2. 通过 navigation 层 pendingRetry 自动 popBackStack 并重新 navigateWithLogin
 *    （包含 autoLogin、必要时 MFA 流程，整个过程对用户透明）。
 *
 * Screen 内典型用法：
 * ```
 * try { ... }
 * catch (e: AuthExpiredException) {
 *     appLoginState.handleAuthExpired(LoginType.X, Routes.X, onBack)
 * }
 * ```
 */
fun AppLoginState.handleAuthExpired(
    type: LoginType,
    route: String,
    onBack: () -> Unit
) {
    markStaleAndRetry(type, route)
    onBack()
}
