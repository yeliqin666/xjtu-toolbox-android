package com.xjtu.toolbox.sharedview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.SeamlessCardSource
import com.xjtu.toolbox.nav.isStandaloneHostable
import com.xjtu.toolbox.nav.maintenanceRoutes

/**
 * [com.xjtu.toolbox.nav.LocalSeamlessOpener] 的 Android 实现，只回答「**现在能不能起一个独立 Activity**」：
 * ROM 支持一镜到底、页面能单独托管、不是在维护中、有凭据、非离线页有网。
 *
 * 站点会话还没连上**不再挡在这里**：宿主先播系统转场，再在骨架屏后面把站点连上
 * （见 `SeamlessPageActivity` 的 `Preparation`）。这样「连不上 / 还没登录」不会让用户先等一段
 * Snackbar，然后才看到一个迟到的旧动画。
 */
internal fun seamlessOpener(
    context: Context,
    loginState: AppLoginState,
    open: (AppRoute) -> Unit,
): (AppRoute, SeamlessCardSource?) -> Unit = { route, source ->
    val activity = context.findActivity()
    if (source != null && activity != null && KitOneTake.supported && canHostWithKit(context, loginState, route)) {
        KitOneTake.openPage(activity, route, source) { open(route) }
    } else {
        open(route)
    }
}

/**
 * 静态门：这里判的都是**点下去之前就注定开不了**的情况。能开但会话没就绪的页面照走 KIT，
 * 由宿主在转场之后补登录。
 */
private fun canHostWithKit(context: Context, loginState: AppLoginState, route: AppRoute): Boolean {
    if (!isStandaloneHostable(route)) return false
    if (maintenanceRoutes[route] != null) return false
    val type = route.loginType
    if (type != null) {
        // 一个凭据都没存：保留原来的「请先登录」提示，不要开一页空白再让人退回来。
        if (!loginState.hasCredentials) return false
        // 有缓存的页面断网也能看，交给页面自己的缓存态；没缓存的等有网再说。
        if (!route.offlineCapable && !context.isOnline()) return false
    } else if (route.needsNetwork && !context.isOnline()) {
        return false
    }
    return true
}

internal fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    return cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
