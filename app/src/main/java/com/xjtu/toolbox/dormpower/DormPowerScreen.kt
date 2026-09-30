package com.xjtu.toolbox.dormpower

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.SsnSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/**
 * 宿舍电费：学校的缴费页本身就是完整界面（绑定、查询、充值都在里面），不再自己画一套。
 * 这里只负责换一张能直接登录缴费页的入口地址，交给 [browser] 用内置浏览器铺满；
 * 地址还在路上时 [browser] 收到 null，先出浏览器外壳，不另画一页加载中。
 * 用过一次就记下来，首页才会后台查电量、低电提醒。
 */
@Composable
fun DormPowerScreen(site: SiteSession, onBack: () -> Unit, browser: @Composable (url: String?) -> Unit) {
    val context = LocalContext.current
    val appLoginState = LocalAppLoginState.current
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(site, attempt) {
        error = null
        try {
            url = (site as SsnSession).freshPayUrl()
            DormPowerStore.markUsed(context)
        } catch (e: AuthExpiredException) {
            appLoginState.handleAuthExpired(AppRoute.DormPower, onBack)
        } catch (e: Exception) {
            error = e.message ?: "打开缴费页失败"
        }
    }
    // 在页面里绑定、充值后，下一轮首页刷新要马上重查一次电量
    DisposableEffect(Unit) { onDispose { HomeStats.invalidate(context, AppRoute.DormPower) } }

    val message = error
    if (message == null) {
        browser(url)
        return
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = { GlassTopAppBar(title = "宿舍电费", glass = rememberPageGlass(), scrollBehavior = scrollBehavior, onBack = onBack) },
    ) { padding ->
        ErrorState(message, onRetry = { attempt++ }, modifier = Modifier.fillMaxSize().padding(padding))
    }
}
