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

/**
 * 宿舍电费：学校的缴费页本身就是完整界面（绑定、查询、充值都在里面），不再自己画一套。
 * 这里只负责换一张能直接登录缴费页的入口地址，交给 [browser] 用内置浏览器铺满；
 * 地址还在路上时 [browser] 收到 null，先出浏览器外壳，不另画一页加载中。
 * 用过一次就记下来，首页才会后台查电量、低电提醒。
 *
 * 本屏从 `:app` 搬进 `:core`（桌面端第 17 条真数据路由，与考勤同一批），屏是两端共用的同一份，
 * 只在**取数**与**宿主能力**上切缝（表格即「原来」→「现在」）：
 *
 * | 原来 | 现在 |
 * |---|---|
 * | `site: SiteSession` + 屏上 `(site as SsnSession).freshPayUrl()` | [source]（`:data` 的 `DormPowerApi`） |
 * | 屏上 `DormPowerStore.markUsed(context)` | 收进 `freshPayUrl()` 的实现里（同一个时机：拿到地址之后） |
 * | `LocalAppLoginState.handleAuthExpired(...)` | `:core` 的 [LocalAuthExpiry] |
 * | `DisposableEffect` 里 `HomeStats.invalidate(context, …)` | [onLeave]（宿主的首页缓存作废槽位） |
 *
 * 屏自己的画法、文案、状态走向**一行未改**：`browser(url)` 与错误页二选一，
 * 「重试」只是把 [attempt] 加一，`error` 文案仍是异常消息（拿不到就用「打开缴费页失败」）。
 *
 * @param browser 内置浏览器外壳（收 null = 地址还在路上）。桌面端没有内置浏览器，宿主用系统浏览器打开。
 * @param onLeave 离开这一屏时回调一次：`:app` 用它让首页把这一路的缓存作废（在页面里绑定、充值后
 *   下一轮首页刷新要马上重查一次电量）；桌面端没有那份首页摘要 ⇒ 默认空实现。
 */
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
