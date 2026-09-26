package com.xjtu.toolbox

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.PidaiAppearanceHost
import com.xjtu.toolbox.auth.SessionKeepAlive
import com.xjtu.toolbox.data.AppearanceSettings
import com.xjtu.toolbox.main.AppRoot
import com.xjtu.toolbox.main.BottomTab
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.DeepLinkRouter
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme

class MainActivity : ComponentActivity() {
    companion object {
        /** 启动后要打开的页面，值是 [AppRoute.id]。小组件、通知、桌面快捷方式都用它。 */
        const val EXTRA_LAUNCH_ROUTE = "extra_launch_route"

        /** 启动后要切到的底栏 tab，值是 [BottomTab.name]。 */
        const val EXTRA_LAUNCH_TAB = "extra_launch_tab"

        /** 启动后交给屁岱自动发送的一句话。 */
        const val EXTRA_LAUNCH_PROMPT = "extra_launch_prompt"
    }

    /** 登录恢复完成（首帧已画出）后为 true，SplashScreen 据此消失。 */
    private var isAppReady = false
    private val launchRoute = mutableStateOf<AppRoute?>(null)
    private val launchTab = mutableStateOf<BottomTab?>(null)

    /**
     * 手机锁竖屏，平板（含折叠屏展开）随意转。
     *
     * 手机和平板按经典分界线分：最短边 ≥ 600dp 才算平板。手机横过来宽也有七八百 dp，
     * 以前按窗口宽度判断就进了平板的侧栏 + 分栏排布，高度只剩三百多 dp，处处挤。
     * 折叠屏合上 / 展开时 smallestScreenSize 会变，Manifest 里声明了自己处理，
     * 这里在 onConfigurationChanged 里重新判断一次。
     *
     * 只在「手机 / 平板」这个结论变了时才动 requestedOrientation：视频全屏会临时请求横屏
     * （VideoPlayer），转过去也会触发 onConfigurationChanged，每次都重设就把它掰回竖屏了。
     */
    private var lastIsTablet: Boolean? = null

    private fun applyOrientationPolicy(config: Configuration) {
        val isTablet = config.smallestScreenWidthDp >= 600
        if (isTablet == lastIsTablet) return
        lastIsTablet = isTablet
        val want = if (isTablet) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy(newConfig)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !isAppReady }
        super.onCreate(savedInstanceState)
        applyOrientationPolicy(resources.configuration)
        consumeLaunchIntent(intent)
        // 底部导航条（小白条）背景强制全透明：默认样式在 API 29–34 上会往
        // window.navigationBarColor 写一层半透明 scrim（浅色是 90% 白），内容从
        // 小白条下面滚过时会被罩一层灰。auto 传全透明后，手势导航全透明；
        // 三键导航由系统自动垫对比度（isNavigationBarContrastEnforced），按钮不会看不见。
        // API 35+ 上 navigationBarColor 已废弃、edge-to-edge 强制透明，此参数无副作用。
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // 屁岱形象（形状/颜色）在首帧前读入，免得底栏先闪一下默认圆再跳到用户选的样子
        PidaiAppearanceHost.load(this)
        // 后台保活：循环读 KeepAlivePrefs，真正续期走 sessionRefresher。
        SessionKeepAlive.start(this)

        val appearance = AppearanceSettings.get(this)
        setContent {
            val darkMode by appearance.darkMode.collectAsStateWithLifecycle()
            val dynamicColor by appearance.dynamicColor.collectAsStateWithLifecycle()
            XJTUToolBoxTheme(darkModeOverride = darkMode, dynamicColor = dynamicColor) {
                AppRoot(
                    initialRoute = launchRoute.value,
                    onInitialRouteConsumed = { launchRoute.value = null },
                    initialTab = launchTab.value,
                    onInitialTabConsumed = { launchTab.value = null },
                    onReady = { isAppReady = true },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    /**
     * 解析启动意图：深链优先于 [EXTRA_LAUNCH_ROUTE]。解析不了的路由（旧版本存下的快捷方式、
     * 过时的深链）当作没有，落在默认首页。深链或 [EXTRA_LAUNCH_PROMPT] 带的 prompt 一次性交给屁岱。
     */
    private fun consumeLaunchIntent(intent: Intent?) {
        val deepLink = intent?.let(DeepLinkRouter::resolve)
        launchRoute.value = deepLink?.route
            ?: intent?.getStringExtra(EXTRA_LAUNCH_ROUTE)?.let(::appRouteOf)
        launchTab.value = intent?.getStringExtra(EXTRA_LAUNCH_TAB)
            ?.let { name -> BottomTab.entries.firstOrNull { it.name == name } }
        (deepLink?.prompt ?: intent?.getStringExtra(EXTRA_LAUNCH_PROMPT))
            ?.let { AgentPendingPrompt.set(it) }
    }
}
