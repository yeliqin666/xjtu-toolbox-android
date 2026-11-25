package com.xjtu.toolbox.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 全局"要打开哪个插件页"的信号；设置页写它，[PluginPageOverlayHost] 读它。 */
internal object PluginOverlay {
    data class Request(val pluginId: String, val pageId: String)

    var current by mutableStateOf<Request?>(null)
        private set

    fun open(pluginId: String, pageId: String) {
        current = Request(pluginId, pageId)
    }

    fun close() {
        current = null
    }
}

/**
 * 全屏插件页宿主：标准顶栏 + 插件自己的 View。
 *
 * 这里不放导航栈：插件页是覆盖层，返回键先关它；账号切换由 [AppRoot] 调 [PluginOverlay.close]。
 */
@Composable
internal fun PluginPageOverlayHost() {
    val request = PluginOverlay.current ?: return
    val context = LocalContext.current
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()

    var opened by remember(request) { mutableStateOf<OpenedPluginPage?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) { PluginOverlay.close() }

    LaunchedEffect(request) {
        val result = withContext(Dispatchers.IO) {
            runCatching { manager.openPage(request.pluginId, request.pageId) }
        }
        val page = result.getOrNull()
        if (page != null) {
            opened = page
        } else {
            error = result.exceptionOrNull()?.message ?: "插件未启用，或页面不存在"
        }
    }

    val title = opened?.title ?: manager.records.firstOrNull { it.id == request.pluginId }?.manifest?.name ?: "插件"
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = title,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = { PluginOverlay.close() },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val page = opened
            when {
                error != null -> Message("插件加载失败：$error")
                page == null -> Message("正在加载插件…")
                else -> {
                    val dark = LocalIsDarkTheme.current
                    val primary = MiuixTheme.colorScheme.primary.toArgb()
                    // 先创建 View（主线程），再在 effect 里驱动 lifecycle；ComposeView 挂上去时
                    // 插件自己的 LifecycleOwner 已经在 RESUMED，动画和 rememberCoroutineScope 才正常。
                    val viewResult = remember(dark, primary, page) {
                        runCatching {
                            page.runtime.applyTheme(dark, primary)
                            page.page.createView(context, "{}")
                        }
                    }
                    val pluginView = viewResult.getOrNull()
                    if (pluginView == null) {
                        Message("插件页面创建失败：${viewResult.exceptionOrNull()?.message ?: "unknown"}")
                    } else {
                        AndroidView(factory = { pluginView }, modifier = Modifier.fillMaxSize())
                        DisposableEffect(page) {
                            page.page.onStart()
                            page.page.onResume()
                            onDispose {
                                page.page.onPause()
                                page.page.onStop()
                                page.page.onDestroy()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
