package com.xjtu.toolbox.auth

import androidx.compose.runtime.staticCompositionLocalOf
import com.xjtu.toolbox.nav.AppRoute

/** 会话失效的处理端口（能力缝）—— 屏幕捕获到「登录态已失效」后请求宿主做该做的事。 */
fun interface AuthExpiryHandler {
    fun onAuthExpired(route: AppRoute, onBack: () -> Unit)
}

/** 默认实现 = 直接退回上一页。 */
val LocalAuthExpiry = staticCompositionLocalOf<AuthExpiryHandler> {
    AuthExpiryHandler { _, onBack -> onBack() }
}
