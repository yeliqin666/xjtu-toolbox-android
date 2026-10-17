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
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/** 宿舍电费：学校的缴费页本身就是完整界面（绑定、查询、充值都在里面），不再自己画一套。 */
@Composable
fun DormPowerScreen(
    /** 本端的取数（Android / 桌面 = `:data` 的 [DormPowerApi]）。 */
    source: DormPowerSource,
    onBack: () -> Unit,
    browser: @Composable (url: String?) -> Unit,
    onLeave: () -> Unit = {},
) {
    val authExpiry = LocalAuthExpiry.current
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, attempt) {
        error = null
        try {
            url = source.freshPayUrl()
        } catch (e: Exception) {
            // `catch` 抓不了标记接口 ⇒ 先抓 Exception 再判，与评教/成绩/校园卡/加餐券同一条缝。
            if (e is SessionExpiredFailure) authExpiry.onAuthExpired(AppRoute.DormPower, onBack) else error = e.message ?: "打开缴费页失败"
        }
    }
    // 在页面里绑定、充值后，下一轮首页刷新要马上重查一次电量
    DisposableEffect(Unit) { onDispose { onLeave() } }

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
