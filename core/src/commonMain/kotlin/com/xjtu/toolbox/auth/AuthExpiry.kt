package com.xjtu.toolbox.auth

import androidx.compose.runtime.staticCompositionLocalOf
import com.xjtu.toolbox.nav.AppRoute

/**
 * **会话失效的处理端口**（能力缝）—— 屏幕捕获到「登录态已失效」后请求宿主做该做的事。
 *
 * ## 为什么必须是缝
 *
 * `:app` 里 20 多个屏都写着同一段：
 * ```
 * catch (e: AuthExpiredException) { appLoginState.handleAuthExpired(AppRoute.X, onBack) }
 * ```
 * 而 `AppLoginState` 是 Android 侧的庞然大物（`Context` + `CredentialStore` + `SessionManager`
 * + 校园网探测 + MFA 弹窗宿主），短期搬不进 `:core`。于是**屏幕搬得动、它搬不动** ⇒ 屏一搬过去
 * 就编不过。
 *
 * 这条缝只取屏幕真正需要的那一件事：`AppLoginState.handleAuthExpired` 的语义
 * （见 `:app/auth/AuthRetryHelper.kt`）= 「把该站点的缓存标记失效 + 退回本页，
 * 由导航根部按需静默重登并重新打开它」。注入实现：
 *  - `:app` → `AuthExpiryHandler { r, back -> loginState.handleAuthExpired(r, back) }`（行为逐字不变）；
 *  - Web  → 不注入（默认实现）—— Web 端没有 CAS 会话，这条路径不会触发；真触发了就是「退回上一页」。
 *
 * @param route 出问题的页面路由（`:app` 的实现据此标记对应站点的缓存失效）。
 * @param onBack 屏幕自己提供的「退回上一页」闭包 —— 与原 `handleAuthExpired` 第二个参数同义。
 */
fun interface AuthExpiryHandler {
    fun onAuthExpired(route: AppRoute, onBack: () -> Unit)
}

/**
 * 默认实现 = 直接退回上一页。等价于 `:app` 的 `markStaleAndRetry(route)` 之后再 `onBack()`
 * 里「没有会话可标记」的那一半，所以 Web/预览环境不需要额外装配。
 */
val LocalAuthExpiry = staticCompositionLocalOf<AuthExpiryHandler> {
    AuthExpiryHandler { _, onBack -> onBack() }
}
