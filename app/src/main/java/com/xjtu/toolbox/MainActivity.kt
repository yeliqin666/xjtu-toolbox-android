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
import com.xjtu.toolbox.inbox.ofRoute
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
    }

    /** 登录恢复完成（首帧已画出）后为 true，SplashScreen 据此消失。 */
    private var isAppReady = false
    private val launchRoute = mutableStateOf<AppRoute?>(null)
    private val launchTab = mutableStateOf<BottomTab?>(null)

    /**
     * 手机锁竖屏，平板（最短边 ≥ 600dp，含折叠屏展开）随意转。
     * 只在手机 / 平板结论变化时才改方向，否则会把视频全屏的横屏掰回去。
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
        // 导航条全透明：默认样式在 API 29–34 上会加一层半透明 scrim，三键导航由系统自己保证对比度
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        PidaiAppearanceHost.load(this) // 首帧前读入，免得底栏先闪默认形象

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

    override fun onStart() {
        super.onStart()
        SessionKeepAlive.start()
    }

    override fun onStop() {
        super.onStop()
        SessionKeepAlive.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    /**
     * 解析启动意图：深链优先；认不出的路由当作没有；深链带的话只填进屁岱的输入框。
     *
     * 网页能用 `intent://…;S.extra_launch_route=…;end` 往 xjtu:// 深链里塞任意 extra，
     * 所以带 data 的意图只认链接本身，extra 只认小组件、通知、快捷方式这类不带 data 的。
     */
    private fun consumeLaunchIntent(intent: Intent?) {
        val deepLink = intent?.let(DeepLinkRouter::resolve)
        val extras = intent?.takeIf { it.data == null }
        launchRoute.value = deepLink?.route
            ?: extras?.getStringExtra(EXTRA_LAUNCH_ROUTE)?.let(::appRouteOf)
        // 从系统通知点进来：收纳里同一件事一并算看过，不然通知点了、收纳的红点还在
        launchRoute.value?.takeIf { extras?.hasExtra(EXTRA_LAUNCH_ROUTE) == true }?.let { route ->
            val ids = (route as? com.xjtu.toolbox.nav.AppRoute.Browser)?.url?.takeIf { it.isNotBlank() }
                ?.let { listOf("notice:$it") }.orEmpty()
            com.xjtu.toolbox.inbox.InboxStore.acknowledge(ids, com.xjtu.toolbox.inbox.InboxCategories.ofRoute(route))
        }
        launchTab.value = extras?.getStringExtra(EXTRA_LAUNCH_TAB)
            ?.let { name -> BottomTab.entries.firstOrNull { it.name == name } }
        // 外部链接的话不能替用户发出去：发出去模型就能调全部工具，回复里的图片一渲染就把数据带走了
        deepLink?.prompt?.let { AgentPendingPrompt.set(it, draftOnly = true) }
    }
}
