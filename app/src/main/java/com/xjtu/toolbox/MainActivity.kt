package com.xjtu.toolbox

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.xjtu.toolbox.agent.AgentPendingPrompt
import com.xjtu.toolbox.agent.PidaiAppearanceHost
import com.xjtu.toolbox.agent.attachAgentImage
import com.xjtu.toolbox.auth.SessionKeepAlive
import com.xjtu.toolbox.data.AppearanceSettings
import com.xjtu.toolbox.main.AppRoot
import com.xjtu.toolbox.inbox.ofRoute
import com.xjtu.toolbox.main.BottomTab
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.nav.DeepLinkRouter
import com.xjtu.toolbox.nav.appRouteOf
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private var incomingImages: Job? = null

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
        com.xjtu.toolbox.agent.AgentLocation.register(this)
        if (savedInstanceState == null) consumeLaunchIntent(intent)
        // 导航条全透明：默认样式在 API 29–34 上会加一层半透明 scrim，三键导航由系统自己保证对比度
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        PidaiAppearanceHost.load(this) // 首帧前读入，免得底栏先闪默认形象

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

    /** 文章入口自动阅读，普通深链保留草稿；控件路由取自无 data 的内部入口。 */
    private fun consumeLaunchIntent(intent: Intent?) {
        incomingImages?.cancel()
        val deepLink = intent?.let(DeepLinkRouter::resolve)
        val extras = intent?.takeIf { it.data == null && it.component?.className != DeepLinkRouter.PIDAI_LINK_ALIAS }
        launchRoute.value = deepLink?.route
            ?: extras?.getStringExtra(EXTRA_LAUNCH_ROUTE)?.let(::appRouteOf)
        // 从系统通知点进来：收纳里同一件事一并算看过，不然通知点了、收纳的红点还在
        launchRoute.value?.takeIf { extras?.hasExtra(EXTRA_LAUNCH_ROUTE) == true }?.let { route ->
            val ids = (route as? com.xjtu.toolbox.nav.AppRoute.Browser)?.url?.takeIf { it.isNotBlank() }
                ?.let { listOf("notice:$it") }.orEmpty()
            com.xjtu.toolbox.inbox.InboxStore.acknowledge(ids, com.xjtu.toolbox.inbox.InboxCategories.ofRoute(route))
        }
        extras?.getLongExtra(com.xjtu.toolbox.agent.AgentPushFocus.EXTRA_PUSH_AT, 0L)?.takeIf { it > 0 }
            ?.let(com.xjtu.toolbox.agent.AgentPushFocus::set)
        launchTab.value = extras?.getStringExtra(EXTRA_LAUNCH_TAB)
            ?.let { name -> BottomTab.entries.firstOrNull { it.name == name } }
        if (deepLink?.imageUris?.isNotEmpty() == true) importSharedImages(deepLink)
        else deepLink?.prompt?.let { AgentPendingPrompt.set(it, draftOnly = !deepLink.autoSend) }
    }

    private fun importSharedImages(target: DeepLinkRouter.DeepLinkTarget) {
        incomingImages = lifecycleScope.launch {
            val paths = mutableListOf<String>()
            var delivered = false
            try {
                withContext(Dispatchers.IO) {
                    target.imageUris.forEach { uri ->
                        ensureActive()
                        attachAgentImage(this@MainActivity, Uri.parse(uri))?.let(paths::add)
                    }
                }
                ensureActive()
                if (paths.isEmpty()) {
                    Toast.makeText(this@MainActivity, "图片读取失败，请重新分享", Toast.LENGTH_SHORT).show()
                } else {
                    AgentPendingPrompt.set(target.prompt.orEmpty(), images = paths)
                    delivered = true
                    if (target.imageCount > paths.size) Toast.makeText(this@MainActivity,
                        if (target.imageCount > 4) "已接收前 ${paths.size} 张图片，每次最多 4 张"
                        else "已接收 ${paths.size} 张图片，其余图片读取失败", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (!delivered) paths.forEach { File(it).delete() }
            }
        }
    }

    /**
     * 输入框里的文字刚被改短、排版还没跟上时按键，Compose 会抛 offset out of bounds，整个 App 闪退。
     * 这一次按键丢掉就行，下一帧排版更新后输入照常。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
        super.dispatchKeyEvent(event)
    } catch (e: IllegalArgumentException) {
        if (e.message?.contains("is out of bounds") != true) throw e
        Log.w("MainActivity", "dropped key event on stale text layout", e)
        true
    }
}
