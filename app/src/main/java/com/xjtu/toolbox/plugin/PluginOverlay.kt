package com.xjtu.toolbox.plugin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import com.xjtu.toolbox.ui.glass.CouiToolbarActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.ui.theme.LocalIsDarkTheme
import com.xjtu.toolbox.plugin.api.UiEmptyNode
import com.xjtu.toolbox.plugin.api.UiLoadingNode
import com.xjtu.toolbox.plugin.api.UiNode
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.rememberCouiTopAppBarScrollBehavior
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.reportScrollToTopBar
import com.xjtu.toolbox.ui.glass.tabContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 全局"要打开哪个插件页"的信号；设置页写它，[PluginPageOverlayHost] 读它。 */
internal object PluginOverlay {
    data class Request(val pluginId: String, val pageId: String)

    var current by mutableStateOf<Request?>(null)
        private set

    /**
     * 「打开插件管理页」的请求标志，由 [com.xjtu.toolbox.main.AppRoot] 读走并转成路由
     * `AppRoute.Plugins`。插件管理页本身在导航栈里，不再是覆盖层。
     * [listDemo] 为 true 时用假数据，只看版式。
     */
    var listOpen by mutableStateOf(false)
        private set

    var listDemo by mutableStateOf(false)
        private set

    fun openList(demo: Boolean = false) {
        listDemo = demo
        listOpen = true
    }

    /** 标记「已经在开路由了」，避免 AppRoot 重复消费同一次请求。 */
    fun consumeListRequest() {
        listOpen = false
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
 * 把插件公开页压进导航栈的入口；由 `AppRoot` 注入（它手上有 router）。
 * 没注入时退回覆盖层，至少点得开。
 */
internal object PluginLauncher {
    var openRoute: ((pluginId: String, pageId: String) -> Unit)? = null

    /** 打开某个插件的详情页（设置里的插件列表也用它）。 */
    var openDetail: ((pluginId: String) -> Unit)? = null
}

/**
 * 全屏插件页宿主：标准顶栏 + 插件返回的界面树。
 *
 * 插件那边一个 Compose 类都没有——它只交回数据（[UiNode]），这里用盒子自己的组件画。
 * 所以不存在跨 classloader 的类型身份问题，插件也不必自带 Compose / lifecycle / 协程。
 *
 * 这里不放导航栈：插件页是覆盖层，返回键先关它；账号切换由 [AppRoot] 调 [PluginOverlay.close]。
 * 插件管理页（列表 / 详情）不在这儿，它走路由 `AppRoute.Plugins`。
 */
@Composable
internal fun PluginPageOverlayHost() {
    // 插件管理页（列表/详情）走路由，不在这里画；这里只剩插件自己的公开页兜底
    val request = PluginOverlay.current ?: return
    PluginPageScreen(request.pluginId, request.pageId) { PluginOverlay.close() }
}

/**
 * 插件页本体。两条入口共用：设置里的覆盖层（[PluginPageOverlayHost]），
 * 以及 `AppRoute.Plugin` 压进导航栈的那一页。
 */
@Composable
internal fun PluginPageScreen(pluginId: String, pageId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { PluginManager.get(context) }
    val uriHandler = LocalUriHandler.current
    val glass = rememberPageGlass()
    val scrollBehavior = rememberCouiTopAppBarScrollBehavior()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scroll = rememberScrollState()

    val record = manager.records.firstOrNull { it.id == pluginId }
    val pages = record?.manifest?.pages.orEmpty()

    // 侧栏切页只改这个 id，不动路由；路由带进来的 page 是初始值
    var currentPageId by remember(pluginId, pageId) {
        mutableStateOf(pageId.ifBlank { pages.firstOrNull()?.id.orEmpty() })
    }
    var navOpen by remember { mutableStateOf(false) }

    var opened by remember(pluginId, currentPageId) { mutableStateOf<OpenedPluginPage?>(null) }
    var error by remember(pluginId, currentPageId) { mutableStateOf<String?>(null) }
    var tick by remember(pluginId, currentPageId) { mutableIntStateOf(0) }

    BackHandler(enabled = true) {
        // 侧栏开着时返回先收侧栏
        if (navOpen) navOpen = false else onBack()
    }

    LaunchedEffect(pluginId, currentPageId) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // 冷启动直接从路由进来时还没人读过 registry，先刷一遍再取页面
                manager.refresh()
                // 路由没带 page（插件的一级公开页）：用它清单里的第一页
                val target = currentPageId.ifBlank {
                    manager.records.firstOrNull { it.id == pluginId }
                        ?.manifest?.pages?.firstOrNull()?.id.orEmpty()
                }
                manager.openPage(pluginId, target)
            }
        }
        val page = result.getOrNull()
        if (page != null) {
            opened = page
            // 第一次冷启动补上实际页面 id，侧栏才有选中项（会再触发一次本 effect，无所谓）
            if (currentPageId.isEmpty()) {
                currentPageId = manager.records.firstOrNull { it.id == pluginId }
                    ?.manifest?.pages?.firstOrNull()?.id.orEmpty()
            }
        } else {
            error = result.exceptionOrNull()?.message ?: "插件未启用，或页面不存在"
        }
    }

    val page = opened

    // 插件画图时要用宿主主题（isDarkTheme / primaryColor）；没注入就一直是 false + 0
    val darkTheme = LocalIsDarkTheme.current
    val primaryArgb = MiuixTheme.colorScheme.primary.toArgb()
    LaunchedEffect(page, darkTheme, primaryArgb) {
        val openedPage = page ?: return@LaunchedEffect
        openedPage.runtime.applyTheme(darkTheme, primaryArgb)
        // 第一次 render 时主题还没注入，改完要再要一次树，插件才读得到
        tick++
    }

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
        ?: record?.manifest?.name
        ?: "插件"

    val onAction: (String, String?) -> Unit = remember(page) {
        { actionId, value ->
            page?.let { p -> runCatching { p.page.onAction(actionId, value) } }
            Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                GlassTopAppBar(
                    title = title,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    actions = {
                        // 只有一个页面的插件没什么可导航的，不显示目录按钮
                        if (pages.size > 1) {
                            CouiToolbarActionButton(onClick = { navOpen = true }) {
                                Icon(Icons.Default.Menu, contentDescription = "目录")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 插件页正文也能折叠顶栏；内容不够一屏时不会误折叠
                    .reportScrollToTopBar(scroll, scrollBehavior)
                    .verticalScroll(scroll)
                    .overScrollVertical()
                    .padding(tabContentPadding(padding))
                    // 顶栏毛玻璃采样正文
                    .glassSource(glass)
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

        PluginNavDrawer(
            open = navOpen,
            pages = pages,
            selectedPageId = currentPageId,
            version = record?.versionName.orEmpty(),
            pluginId = pluginId,
            author = record?.manifest?.author.orEmpty(),
            projectUrl = record?.manifest?.projectUrl.orEmpty(),
            onSelect = { id ->
                navOpen = false
                if (id != currentPageId) currentPageId = id
            },
            onOpenProject = {
                navOpen = false
                runCatching { uriHandler.openUri(record?.manifest?.projectUrl.orEmpty()) }
            },
            onOpenSettings = {
                navOpen = false
                PluginLauncher.openDetail?.invoke(pluginId)
            },
            onDismiss = { navOpen = false },
        )
    }
}

/**
 * 插件自己的导航目录：从右侧滑进来，占屏幕 72%。
 *
 * 目录来自清单里的 `pages[]`：有 `group` 的页面归到同名的一级里（可折叠），
 * 没有 `group` 的是顶级条目。底部是插件信息，三行都靠右。
 */
@Composable
private fun PluginNavDrawer(
    open: Boolean,
    pages: List<PluginPageManifest>,
    selectedPageId: String,
    version: String,
    pluginId: String,
    author: String,
    projectUrl: String,
    onSelect: (String) -> Unit,
    onOpenProject: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 一级分组默认展开；记住用户收起了哪几个
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val interaction = remember { MutableInteractionSource() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(interactionSource = interaction, indication = null, onClick = onDismiss),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { width -> width },
            exit = slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { width -> width },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.72f)
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 28.dp),
            ) {
                Text(
                    text = "目录",
                    style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(18.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    pages.filter { it.group.isNotBlank() }
                        .groupBy { it.group }
                        .forEach { (group, items) ->
                            val isCollapsed = collapsed[group] == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { collapsed[group] = !isCollapsed }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group,
                                    style = MiuixTheme.textStyles.title2,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isCollapsed) "展开" else "收起",
                                    modifier = Modifier.rotate(if (isCollapsed) -90f else 0f),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (!isCollapsed) {
                                items.forEach { item ->
                                    NavPageRow(item, item.id == selectedPageId, indent = true) { onSelect(item.id) }
                                }
                            }
                        }

                    pages.filter { it.group.isBlank() }.forEach { item ->
                        NavPageRow(item, item.id == selectedPageId, indent = false) { onSelect(item.id) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 插件信息：三行都靠右，body + 次要色
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "v$version · $pluginId",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        text = "作者：$author",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.End,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // 两个链接用主题主色（跟「可点击文本」一致），不用次要灰
                        Text(
                            text = "项目地址",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = projectUrl.isNotBlank(), onClick = onOpenProject),
                        )
                        Text(
                            text = "插件设置",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onOpenSettings),
                        )
                    }
                }
            }
        }
    }
}

/** 目录里的一个页面条目。二级条目缩进，选中项用主色。 */
@Composable
private fun NavPageRow(
    page: PluginPageManifest,
    selected: Boolean,
    indent: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = page.title.ifBlank { page.id },
        style = MiuixTheme.textStyles.title3,
        color = if (selected) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 14.dp else 0.dp, top = 8.dp, bottom = 8.dp),
    )
}
