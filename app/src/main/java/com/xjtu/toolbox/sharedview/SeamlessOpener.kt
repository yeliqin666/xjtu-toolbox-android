package com.xjtu.toolbox.sharedview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.isStandaloneHostable

/**
 * [com.xjtu.toolbox.nav.LocalSeamlessOpener] 的 Android 实现：ROM 支持一镜到底、页面能独立托管、
 * 登录会话已就绪、需要联网时有网，才走 KIT 的系统级转场；其余情况（需要登录、离线、覆盖层、
 * 底栏语义）原样交给 [open]，保持原来的交互。
 */
internal fun seamlessOpener(
    context: Context,
    loginState: AppLoginState,
    open: (AppRoute) -> Unit,
): (AppRoute, SeamlessCardSource?) -> Unit = { route, source ->
    val activity = context.findActivity()
    if (source != null && activity != null && KitOneTake.supported && canOpenWithKit(context, loginState, route)) {
        KitOneTake.openPage(activity, route, source) { open(route) }
    } else {
        open(route)
    }
}

private fun canOpenWithKit(context: Context, loginState: AppLoginState, route: AppRoute): Boolean {
    if (!isStandaloneHostable(route)) return false
    route.loginType?.let { type ->
        if (loginState.sessionManager?.getSiteOrNull(type.siteKey())?.hasLogin != true) return false
    }
    return !route.needsNetwork || context.isOnline()
}

private fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    return cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
