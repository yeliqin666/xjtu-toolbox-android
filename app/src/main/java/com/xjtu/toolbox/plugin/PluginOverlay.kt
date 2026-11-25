package com.xjtu.toolbox.plugin

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.plugin.api.UiEmptyNode
import com.xjtu.toolbox.plugin.api.UiLoadingNode
import com.xjtu.toolbox.plugin.api.UiNode
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.tabContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 全局"要打开哪个插件页"的信号；设置页写它，[PluginPageOverlayHost] 读它。 */
internal object PluginOverlay {
    data class Request(val pluginId: String, val pageId: String)

    var current by mutableStateOf<Request?>(null)
        private set

    /** 调试用：整页显示插件列表（设置 → 插件那一页）。 */
    var listOpen by mutableStateOf(false)
        private set

    fun openList() {
        listOpen = true
    }

    fun closeList() {
        listOpen = false
    }

    fun open(pluginId: String, pageId: String) {
        current = Request(pluginId, pageId)
    }

    fun close() {
        current = null
    }

    fun closeAll() {
        current = null
        listOpen = false
    }
}

/**
 * 全屏插件页宿主：标准顶栏 + 插件返回的界面树。
 *
 * 插件那边一个 Compose 类都没有——它只交回数据（[UiNode]），这里用盒子自己的组件画。
 * 所以不存在跨 classloader 的类型身份问题，插件也不必自带 Compose / lifecycle / 协程。
 *
 * 这里不放导航栈：插件页是覆盖层，返回键先关它；账号切换由 [AppRoot] 调 [PluginOverlay.close]。
 */
@Composable
internal fun PluginPageOverlayHost() {
    if (PluginOverlay.listOpen) {
        PluginsPage(onBack = { PluginOverlay.closeList() })
        return
    }
    val request = PluginOverlay.current ?: return
    val context = LocalContext.current
    val manager = remember { PluginManager.get(context) }
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scroll = rememberScrollState()

    var opened by remember(request) { mutableStateOf<OpenedPluginPage?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var tick by remember(request) { mutableIntStateOf(0) }

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

    val page = opened

    // 插件改完状态调 invalidate()，这里切回主线程重要一次树；插件从任意线程调都行
    DisposableEffect(page) {
        if (page == null) {
            onDispose { }
        } else {
            page.page.attachInvalidator { mainHandler.post { tick++ } }
            page.page.onStart()
            page.page.onResume()
            onDispose {
                page.page.attachInvalidator(null)
                page.page.onPause()
                page.page.onStop()
                page.page.onDestroy()
            }
        }
    }

    // 树每帧重新要一次：插件把数据放在自己的字段里，render() 只是把字段组装成节点
    val tree: UiNode? = remember(page, tick) {
        page?.let { p ->
            runCatching { p.page.render() }.getOrElse {
                UiEmptyNode("插件页面渲染失败", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    val title = page?.page?.title()?.takeIf { it.isNotBlank() }
        ?: page?.title
        ?: manager.records.firstOrNull { it.id == request.pluginId }?.manifest?.name
        ?: "插件"

    val onAction: (String, String?) -> Unit = remember(page) {
        { actionId, value ->
            page?.let { p -> runCatching { p.page.onAction(actionId, value) } }
            Unit
        }
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .overScrollVertical()
                .padding(tabContentPadding(padding))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val failed = error
            when {
                failed != null -> PluginUiTree(UiEmptyNode("插件加载失败", failed), onAction)
                tree == null -> PluginUiTree(UiLoadingNode("正在加载插件…"), onAction)
                else -> PluginUiTree(tree, onAction)
            }
        }
    }
}
