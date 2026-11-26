package com.xjtu.toolbox.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.browser.BrowserScreen
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.webvpn.WebVpnUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * web 插件的那一页：就是盒子自己的内置浏览器，外面套一层「地址准备」。
 *
 * - `useVpn = false`：直接打开清单里的 `web.startUrl`；
 * - `useVpn = true`：先探一次 WebVPN 登录态（失效就在 App 内重登），
 *   再把地址改写成网关地址（`WebVpnUtil.getVpnUrl`），校外也能开。
 *
 * 网页本身完全在 [BrowserScreen] 里跑，缓存、Cookie、下载、无痕这些跟盒子其它网页一致。
 */
@Composable
internal fun PluginWebScreen(
    pluginId: String,
    loginState: AppLoginState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val manager = remember { PluginManager.get(context) }
    var state by remember(pluginId) { mutableStateOf<WebState>(WebState.Loading) }
    var pluginName by remember(pluginId) { mutableStateOf("插件") }

    LaunchedEffect(pluginId) {
        val record = withContext(Dispatchers.IO) {
            // 冷启动直接深链进来时 registry 还没读过
            runCatching { manager.refresh() }
            manager.records.firstOrNull { it.id == pluginId }
        }
        val manifest = record?.manifest
        if (record == null || manifest == null) {
            state = WebState.Error("插件不存在")
            return@LaunchedEffect
        }
        pluginName = manifest.name
        if (!record.enabled) {
            state = WebState.Error("插件已停用，回上一页打开它")
            return@LaunchedEffect
        }
        val startUrl = manifest.webStartUrl
        if (startUrl.isBlank()) {
            state = WebState.Error("这个插件没有声明网页地址")
            return@LaunchedEffect
        }

        val useVpn = record.webVpnEnabled()
        if (!useVpn) {
            state = WebState.Ready(
                url = startUrl,
                origins = manifest.webOrigins,
                allowSubdomains = manifest.webAllowSubdomains,
            )
            return@LaunchedEffect
        }

        // WebVPN：探活；失效的话 ensureWebVpnClient 会在 App 内重新登录一次
        val client = withContext(Dispatchers.IO) {
            runCatching { loginState.ensureWebVpnClient() }.getOrNull()
        }
        state = if (client != null) {
            // 走 WebVPN 时页面全在网关域名下，白名单只能按网关放行
            // （网关本身就是边界，能访问什么由学校决定）
            WebState.Ready(
                url = WebVpnUtil.getVpnUrl(startUrl),
                origins = listOf(WebVpnUtil.GATEWAY_ORIGIN),
                allowSubdomains = true,
            )
        } else {
            WebState.Error("WebVPN 没登录上，校园账号可能过期了")
        }
    }

    when (val current = state) {
        // 加载与出错都带顶栏：WebVPN 探活慢的时候也能返回
        WebState.Loading, is WebState.Error -> {
            val glass = rememberPageGlass()
            val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
            Scaffold(
                topBar = {
                    GlassTopAppBar(
                        title = pluginName,
                        glass = glass,
                        scrollBehavior = scrollBehavior,
                        onBack = onBack,
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    if (current is WebState.Error) {
                        EmptyState(title = "打不开这个网页", subtitle = current.message)
                    } else {
                        LoadingState(message = "正在打开网页…")
                    }
                }
            }
        }

        is WebState.Ready -> BrowserScreen(
            initialUrl = current.url,
            allowedOrigins = current.origins,
            allowSubdomains = current.allowSubdomains,
            onBack = onBack,
        )
    }
}

/** 地址准备到哪一步了。 */
private sealed interface WebState {
    data object Loading : WebState

    data class Ready(
        val url: String,
        val origins: List<String>,
        val allowSubdomains: Boolean,
    ) : WebState

    data class Error(val message: String) : WebState
}
