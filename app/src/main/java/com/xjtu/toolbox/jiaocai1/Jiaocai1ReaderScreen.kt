package com.xjtu.toolbox.jiaocai1

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.ui.components.AppDropdownMenu
import com.xjtu.toolbox.ui.components.AppDropdownMenuItem
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DEFAULT_ASPECT = 700f / 1050f

/**
 * 教材全文阅读器。
 *
 * 不用 WebView：原站水印是 reader.js 在图片加载后用 canvas 叠的，走 WebView 会把这段
 * 脚本一起跑了。这里按页取 JPEG 自己渲染，也便于做缓存和预取。
 */
@Composable
fun Jiaocai1ReaderScreen(
    site: SiteSession,
    ssno: String,
    fallbackTitle: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appLoginState = LocalAppLoginState.current
    val vm: Jiaocai1ReaderViewModel = viewModel()
    vm.bind(context, site, ssno, fallbackTitle)
    Jiaocai1UsageNotice()

    if (vm.authExpired) {
        LaunchedEffect(ssno) {
            vm.authExpired = false
            appLoginState.handleAuthExpired(
                LoginType.JIAOCAI,
                Routes.jiaocai1Reader(ssno, fallbackTitle),
                onBack,
            )
        }
    }

    val title = vm.handle?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle.ifBlank { "教材全文" }
    val loader = vm.loader

    when {
        vm.loading || loader == null -> ReaderShell(title, onBack) { LoadingState("正在准备阅读…") }
        vm.error != null || vm.handle == null -> ReaderShell(title, onBack) {
            ErrorState(vm.error ?: "打开失败", onRetry = { vm.retry() })
        }
        else -> ReaderContent(
            vm = vm,
            handle = vm.handle!!,
            loader = loader,
            title = title,
            onBack = onBack,
        )
    }
}

@Composable
private fun ReaderShell(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                color = MiuixTheme.colorScheme.surface,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { body() }
    }
}

@Composable
private fun ReaderContent(
    vm: Jiaocai1ReaderViewModel,
    handle: Jiaocai1BookHandle,
    loader: Jiaocai1PageLoader,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = handle.pages

    var vertical by remember { mutableStateOf(Jiaocai1Prefs.verticalScroll(context)) }
    var chromeVisible by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var aspect by remember(handle.ssno) { mutableFloatStateOf(DEFAULT_ASPECT) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    var sliderValue by remember { mutableFloatStateOf(vm.pageIndex.toFloat()) }

    val pagerState = rememberPagerState(
        initialPage = vm.pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        pageCount = { pages.size },
    )
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    )

    LaunchedEffect(pagerState, vertical) {
        if (vertical) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collect { vm.setPage(it) }
    }
    val verticalIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(verticalIndex, vertical) {
        if (vertical) vm.setPage(verticalIndex)
    }
    LaunchedEffect(vm.pageIndex, vertical) {
        sliderValue = vm.pageIndex.toFloat()
        if (!vertical && pagerState.currentPage != vm.pageIndex) {
            pagerState.scrollToPage(vm.pageIndex)
        }
        if (vertical && listState.firstVisibleItemIndex != vm.pageIndex) {
            listState.scrollToItem(vm.pageIndex)
        }
    }

    fun jumpTo(index: Int) {
        vm.setPage(index)
        scope.launch {
            if (vertical) listState.scrollToItem(index)
            else pagerState.scrollToPage(index)
        }
    }

    Scaffold(
        topBar = {
            if (chromeVisible) {
                SmallTopAppBar(
                    title = title,
                    color = MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showToc = true }) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "目录")
                            }
                            AppDropdownMenu(
                                expanded = showToc,
                                onDismissRequest = { showToc = false },
                            ) {
                                Jiaocai1Paging.sectionStarts(pages).forEach { (typeIndex, start) ->
                                    AppDropdownMenuItem(
                                        text = { Text(Jiaocai1Paging.TYPE_NAMES[typeIndex]) },
                                        trailingIcon = {
                                            Text(
                                                "第 ${start + 1} 页",
                                                style = MiuixTheme.textStyles.footnote2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        },
                                        onClick = {
                                            showToc = false
                                            jumpTo(start)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                vertical = !vertical
                                Jiaocai1Prefs.setVerticalScroll(context, vertical)
                            }
                        ) {
                            Icon(
                                if (vertical) Icons.Default.SwapHoriz else Icons.Default.SwapVert,
                                contentDescription = if (vertical) "改为左右翻页" else "改为上下滚动",
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MiuixTheme.colorScheme.surfaceVariant)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val targetPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
                if (vertical) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(pages, key = { _, p -> p.fileName }) { index, page ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(aspect.takeIf { it > 0f } ?: DEFAULT_ASPECT)
                            ) {
                                PageSlot(
                                    handle = handle,
                                    page = page,
                                    loader = loader,
                                    targetWidthPx = targetPx,
                                    aspect = aspect,
                                    vm = vm,
                                    isCurrent = index == vm.pageIndex,
                                    currentScale = if (index == vm.pageIndex) currentScale else 1f,
                                    passHorizontalAtEdge = false,
                                    passVerticalAtEdge = true,
                                    onAspectResolved = { if (page.index == 0 || aspect == DEFAULT_ASPECT) aspect = it },
                                    onTap = { chromeVisible = !chromeVisible },
                                    onScaleChange = { if (index == vm.pageIndex) currentScale = it },
                                )
                            }
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { index ->
                        val page = pages[index]
                        PageSlot(
                            handle = handle,
                            page = page,
                            loader = loader,
                            targetWidthPx = targetPx,
                            aspect = aspect,
                            vm = vm,
                            isCurrent = index == vm.pageIndex,
                            currentScale = if (index == vm.pageIndex) currentScale else 1f,
                            passHorizontalAtEdge = true,
                            passVerticalAtEdge = false,
                            onAspectResolved = { if (page.index == 0 || aspect == DEFAULT_ASPECT) aspect = it },
                            onTap = { chromeVisible = !chromeVisible },
                            onScaleChange = { if (index == pagerState.currentPage) currentScale = it },
                        )
                    }
                }
            }

            if (chromeVisible && pages.isNotEmpty()) {
                val previewIndex = sliderValue.toInt().coerceIn(0, pages.lastIndex)
                val previewPage = pages[previewIndex]
                ReaderBottomBar(
                    label = previewPage.label,
                    position = "${previewIndex + 1} / ${pages.size}",
                    pageValue = sliderValue,
                    pageCount = pages.size,
                    onPageChange = { sliderValue = it },
                    onPageChangeFinished = { jumpTo(sliderValue.toInt().coerceIn(0, pages.lastIndex)) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }

}

@Composable
private fun PageSlot(
    handle: Jiaocai1BookHandle,
    page: Jiaocai1Page,
    loader: Jiaocai1PageLoader,
    targetWidthPx: Int,
    aspect: Float,
    vm: Jiaocai1ReaderViewModel,
    isCurrent: Boolean,
    currentScale: Float,
    passHorizontalAtEdge: Boolean,
    passVerticalAtEdge: Boolean,
    onAspectResolved: (Float) -> Unit,
    onTap: () -> Unit,
    onScaleChange: (Float) -> Unit,
) {
    Jiaocai1ZoomablePage(
        aspect = aspect,
        passHorizontalAtEdge = passHorizontalAtEdge,
        passVerticalAtEdge = passVerticalAtEdge,
        onTap = onTap,
        onScaleChange = onScaleChange,
    ) {
        PageImage(
            handle = handle,
            page = page,
            loader = loader,
            targetWidthPx = targetWidthPx,
            tier = if (isCurrent && currentScale > 1.5f) Jiaocai1DecodeTier.DETAIL else Jiaocai1DecodeTier.SCREEN,
            onAspectResolved = onAspectResolved,
            onFailed = { vm.onTokenExpired() },
        )
    }
}

@Composable
private fun PageImage(
    handle: Jiaocai1BookHandle,
    page: Jiaocai1Page,
    loader: Jiaocai1PageLoader,
    targetWidthPx: Int,
    tier: Jiaocai1DecodeTier,
    onAspectResolved: (Float) -> Unit,
    onFailed: () -> Unit,
) {
    var bitmap by remember(page.fileName) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page.fileName) { mutableStateOf(false) }

    LaunchedEffect(page.fileName, targetWidthPx, tier) {
        if (targetWidthPx <= 0) return@LaunchedEffect
        val raw = loader.load(handle, page, targetWidthPx, tier)
        if (raw == null) {
            if (bitmap == null) failed = true
            return@LaunchedEffect
        }
        failed = false
        bitmap = raw
        if (raw.height > 0) onAspectResolved(raw.width.toFloat() / raw.height)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = page.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${page.label} 加载失败",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(text = "重新打开", onClick = onFailed)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    page.label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    label: String,
    position: String,
    pageValue: Float,
    pageCount: Int,
    onPageChange: (Float) -> Unit,
    onPageChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.background.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    position,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (pageCount > 1) {
            Spacer(Modifier.height(6.dp))
            Slider(
                value = pageValue.coerceIn(0f, (pageCount - 1).toFloat()),
                onValueChange = onPageChange,
                onValueChangeFinished = onPageChangeFinished,
                valueRange = 0f..(pageCount - 1).toFloat(),
                steps = (pageCount - 2).coerceAtLeast(0),
                colors = SliderDefaults.sliderColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
