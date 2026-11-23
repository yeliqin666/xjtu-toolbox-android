package com.xjtu.toolbox.sharedview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.AuthExpiryHandler
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.LocalAuthExpiry
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.MfaDialogHost
import com.xjtu.toolbox.auth.ProcessLoginState
import com.xjtu.toolbox.auth.ensureSite
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.auth.siteKey
import com.xjtu.toolbox.data.AppearanceSettings
import com.xjtu.toolbox.data.CredentialStore
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.game.board.LeaderboardHost
import com.xjtu.toolbox.main.AppRouter
import com.xjtu.toolbox.main.AutoLoginDialog
import com.xjtu.toolbox.main.BottomTab
import com.xjtu.toolbox.main.MainTabState
import com.xjtu.toolbox.main.autoLoginTimeoutMs
import com.xjtu.toolbox.nav.AppNavigator
import com.xjtu.toolbox.nav.AppPageDeps
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.AwaitSite
import com.xjtu.toolbox.nav.ExpandOriginPopOverlay
import com.xjtu.toolbox.nav.appPageEntries
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.nav.expandFromOrigin
import com.xjtu.toolbox.pay.PaymentCodeDialog
import com.xjtu.toolbox.ui.LocalIsWideLayout
import com.xjtu.toolbox.ui.calculateIsWideLayout
import com.xjtu.toolbox.ui.glass.LocalGlassStyle
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import kotlin.reflect.KClass

/**
 * 「一镜到底」的通用页面宿主：ROM 声明支持金标联盟 SharedViewKit 时，首页卡片点开后由
 * [KitOneTake] 用系统级 Activity 转场启动本页；不支持的设备不会走到这里（压普通 Compose 路由）。
 *
 * 内容复用 `appPageEntries`：页面本身、页面里的二级跳转、返回栈、登录/自动登录、MFA、付款码、
 * 排行榜弹窗都和主界面共用同一套实现；会话来自进程级的 [ProcessLoginState]，不复制 SessionManager。
 *
 * **登录门在宿主里**：只要 ROM 支持、页面能独立托管，点下去就立刻起 Activity 播系统转场；
 * 站点会话没就绪时先画 [SeamlessPageSkeleton]（也是 KIT 要求的首帧不透明全屏），在骨架后面
 * 跑 `ensureSite`，连上之后再把真实页面压进返回栈。这样「还没连接」不会再退回旧动画先等一段。
 */
class SeamlessPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 MainActivity 一致的边到边 + 透明导航条，避免起止两端系统栏跳色。
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        val route = appRouteOf(intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty())
        if (route == null) {
            finish()
            return
        }
        val appearance = AppearanceSettings.get(this)
        setContent {
            val darkMode by appearance.darkMode.collectAsStateWithLifecycle()
            val dynamicColor by appearance.dynamicColor.collectAsStateWithLifecycle()
            val customColorHex by appearance.customColorHex.collectAsStateWithLifecycle()
            val customDarkColorHex by appearance.customDarkColorHex.collectAsStateWithLifecycle()
            XJTUToolBoxTheme(
                darkModeOverride = darkMode,
                dynamicColor = dynamicColor,
                customColorHex = customColorHex,
                customDarkColorHex = customDarkColorHex,
            ) {
                SeamlessPageHost(initialRoute = route) { finish() }
            }
        }
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "seamless_route_id"

        /** [route] 的 `id` 能经 [appRouteOf] 还原（带参数的路由也是），所以只传字符串。 */
        fun intent(context: Context, route: AppRoute): Intent =
            Intent(context, SeamlessPageActivity::class.java).putExtra(EXTRA_ROUTE_ID, route.id)
    }
}

/** 宿主在转场之后补会话：页面自己负责「内容」，宿主只负责「这一屏能不能画」。 */
private sealed interface Preparation {
    data object Ready : Preparation
    data class Waiting(val type: LoginType) : Preparation
    data class Failed(val message: String) : Preparation
}

/**
 * 首帧该不该等登录：
 * - 不需要登录的页面直接画；
 * - 已经连上的站点直接画；
 * - 有缓存的页面直接画：这类页（思源学堂、成绩）本来就是「先上缓存、登录交给第一次取数」，
 *   等登录反而比原来慢；
 * - 其余情况先骨架、宿主里 `ensureSite`。
 */
private fun initialPreparation(route: AppRoute, loginState: AppLoginState): Preparation {
    val type = route.loginType ?: return Preparation.Ready
    if (loginState.sessionManager?.getSiteOrNull(type.siteKey())?.hasLogin == true) return Preparation.Ready
    if (route.offlineCapable) return Preparation.Ready
    return Preparation.Waiting(type)
}

@Composable
private fun SeamlessPageHost(initialRoute: AppRoute, onExit: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val viewModel = remember { ProcessLoginState.get(context) }
    val loginState = viewModel.loginState
    val credentialStore = viewModel.credentialStore
    val accountManager = viewModel.accountManager
    val scope = rememberCoroutineScope()

    var preparation by remember(initialRoute) {
        mutableStateOf<Preparation>(initialPreparation(initialRoute, loginState))
    }
    var attempt by remember(initialRoute) { mutableIntStateOf(0) }

    // 骨架屏期间先把站点连上；连上之后才让 NavDisplay 去组合真实页面（`WithSite` 那些 entry
    // 拿不到会话会直接 back()，所以顺序不能反）。
    LaunchedEffect(initialRoute, attempt) {
        val type = initialRoute.loginType ?: return@LaunchedEffect
        if (preparation is Preparation.Ready) return@LaunchedEffect
        val manager = loginState.sessionManager
        if (manager == null) {
            preparation = Preparation.Failed("账号信息还没恢复，请返回后重试")
            return@LaunchedEffect
        }
        if (!loginState.hasCredentials) {
            preparation = Preparation.Failed("请先登录后使用${type.label}")
            return@LaunchedEffect
        }
        preparation = Preparation.Waiting(type)
        credentialStore.recordSiteUse(type.siteKey())
        val site = try {
            withTimeoutOrNull(autoLoginTimeoutMs(type)) {
                manager.ensureSite(type, userInitiated = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            preparation = Preparation.Failed(FriendlyError.of(e, "连接${type.label}"))
            return@LaunchedEffect
        }
        preparation = if (site != null) {
            Preparation.Ready
        } else {
            Preparation.Failed("连接${type.label}超时，请重试")
        }
    }

    val ready = preparation is Preparation.Ready
    // 还没到真实页面时，系统返回键直接关宿主（finish 会接 ROM 的缩回动画）。
    BackHandler(enabled = !ready) { onExit() }

    val backStack = rememberNavBackStack<AppRoute>(initialRoute)
    val hostView = androidx.compose.ui.platform.LocalView.current
    val navigator = remember(backStack, hostView) { AppNavigator(backStack, hostView) }
    val tabs = remember { MainTabState(BottomTab.HOME.name) }
    val router = remember(navigator, tabs) {
        AppRouter(navigator, tabs, loginState, credentialStore, appContext, scope)
    }
    val back: () -> Unit = {
        if (backStack.size > 1) navigator.popBackStack() else onExit()
    }

    var webVpnJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val openWithWebVpn: (String) -> Unit = { url ->
        webVpnJob?.cancel()
        webVpnJob = scope.launch {
            if (loginState.ensureWebVpnClient() != null) router.open(AppRoute.Browser(url))
        }
    }

    val navStyle by AppearanceSettings.get(context).navBarStyle.collectAsStateWithLifecycle()
    val reducedEffects by AppearanceSettings.get(context).reducedEffects.collectAsStateWithLifecycle()
    val screenCornerPx = with(LocalDensity.current) { rememberNavSystemCornerRadius().toPx() }
    val transitions = remember(screenCornerPx) { mutableMapOf<KClass<out AppRoute>, NavTransition>() }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppLoginState provides loginState,
        LocalAuthExpiry provides AuthExpiryHandler { route, onBack ->
            loginState.handleAuthExpired(route, onBack)
        },
        LocalIsWideLayout provides calculateIsWideLayout(),
        LocalGlassStyle provides (navStyle == CredentialStore.NAV_STYLE_FLOATING),
        com.xjtu.toolbox.ui.components.LocalReducedEffects provides reducedEffects,
    ) {
        // 全应用唯一的 MFA 弹窗 / 排行榜弹窗，在独立宿主里也要有（骨架后面补登录会用到）。
        MfaDialogHost(loginState.sessionManager)
        LeaderboardHost()

        when (val current = preparation) {
            Preparation.Ready -> {
                val deps = remember(router, loginState, credentialStore) {
                    AppPageDeps(router, loginState, credentialStore, accountManager) { openWithWebVpn(it) }
                }
                Box(Modifier.fillMaxSize()) {
                    NavDisplay(backStack = backStack, onBack = back) {
                        appPageEntries(deps, back) { type ->
                            transitions.getOrPut(type) { expandFromOrigin(type, screenCornerPx) }
                        }
                    }
                    ExpandOriginPopOverlay(screenCornerPx)
                }

                AutoLoginDialog(router)

                if (router.showPaymentCode) {
                    Dialog(
                        onDismissRequest = { router.showPaymentCode = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        AwaitSite(loginState, "campus_card", onTimeout = { router.showPaymentCode = false }) {
                            PaymentCodeDialog(site = it) { router.showPaymentCode = false }
                        }
                    }
                }
            }

            is Preparation.Waiting -> SeamlessPageSkeleton(
                message = "正在连接${current.type.label}…",
                onBack = onExit,
            )

            is Preparation.Failed -> SeamlessPageFailure(
                message = current.message,
                onRetry = { attempt++ },
                onBack = onExit,
            )
        }
    }
}
