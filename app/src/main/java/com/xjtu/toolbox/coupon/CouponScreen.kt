package com.xjtu.toolbox.coupon

import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.FullPageState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjtu.toolbox.ui.adaptive.readableWidth
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import androidx.compose.foundation.lazy.staggeredgrid.items
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.handleAuthExpired
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.*
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.xjtu.toolbox.nav.AppRoute

@Composable
fun CouponScreen(
    site: SiteSession,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: CouponViewModel = viewModel(key = "coupon-${System.identityHashCode(site)}") { CouponViewModel(context, site) }
    LaunchedEffect(vm) { vm.authExpired.collect { appLoginState.handleAuthExpired(AppRoute.Coupon, onBack) } }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val selectedFilter = vm.filter

    // 玻璃顶栏（经典风格下为 null，一切照旧），用法见 ui/glass/GlassTopBar.kt
    val glass = rememberPageGlass()
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = "加餐券",
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                // 分段标签不跟着滚：挂在顶栏里和顶栏一起做一整块玻璃，券卡从它下面滚过去
                bottomContent = {
                    CompositionLocalProvider(LocalOnGlassBar provides (glass != null)) {
                        AppSegmentedTabs(
                            tabs = CouponFilter.entries.map { it.label },
                            selectedTabIndex = CouponFilter.entries.indexOf(selectedFilter),
                            onTabSelected = { vm.selectFilter(CouponFilter.entries[it]) },
                            modifier = Modifier.readableWidth(),
                        )
                    }
                },
            )
        }
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        // 宽屏不再整页限宽 720：券卡分两三列铺开，只有标签行还限宽居中
        Column(
            Modifier
                .padding(padding.withoutTop(glass))
                .glassSource(glass)
                .fillMaxSize()
        ) {
            AppPullToRefresh(
                isRefreshing = vm.isRefreshing,
                onRefresh = vm::refresh,
                scrollBehavior = scrollBehavior,
                // 下拉指示器从玻璃顶栏（含标签行）下面出来
                topPadding = glassTop,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 四个分类左右滑动切换，和别的分段标签页一样用 AppTabPager。
                // 列表只有当前分类那一份（切分类时重新请求），所以滑动途中露出来的相邻页先显示加载中，
                // 停稳、定下分类以后 LaunchedEffect(selectedFilter) 去拉它的数据。
                val filterIndex = CouponFilter.entries.indexOf(selectedFilter)
                com.xjtu.toolbox.ui.components.AppTabPager(
                    pageCount = CouponFilter.entries.size,
                    selectedTabIndex = filterIndex,
                    onTabSelected = { vm.selectFilter(CouponFilter.entries[it]) },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                when {
                    page != filterIndex -> LoadingState("正在加载加餐券...", Modifier.fillMaxSize().padding(top = glassTop))
                    vm.isLoading -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) { LoadingState("正在加载加餐券...", Modifier.fillMaxSize()) }
                    vm.errorMessage != null -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) {
                        ErrorState(
                            message = vm.errorMessage ?: "加载失败",
                            onRetry = { vm.load() },
                            modifier = Modifier.fillMaxSize(),
                            icon = Icons.Default.ErrorOutline
                        )
                    }
                    vm.records.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = glassTop)) {
                        EmptyState(
                            title = selectedFilter.emptyTitle,
                            subtitle = "下拉可刷新重试",
                            icon = Icons.Outlined.ConfirmationNumber,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> CouponList(
                        site = site,
                        records = vm.records,
                        total = vm.total,
                        filter = selectedFilter,
                        statusMessage = vm.statusMessage,
                        receivingIds = vm.receivingIds,
                        onReceive = vm::receive,
                        isLoadingMore = vm.isLoadingMore,
                        // 翻页失败时停止自动加载，否则会对着挂掉的接口无限重试
                        loadMoreError = vm.loadMoreError,
                        onLoadMore = vm::loadMore,
                        topPadding = glassTop,
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun CouponList(
    site: SiteSession,
    records: List<CouponRecord>,
    total: Int,
    isLoadingMore: Boolean,
    loadMoreError: String?,
    onLoadMore: () -> Unit,
    filter: CouponFilter,
    statusMessage: String?,
    receivingIds: Set<String>,
    onReceive: (CouponRecord) -> Unit,
    /** 玻璃顶栏（含标签行）的高度，放进列表顶部留白。 */
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val leftAmount = records.sumOf { it.leftAmountFen }
    val hasMore = records.size < total && loadMoreError == null
    val listState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()

    // 接近底部（还剩 3 项可见）时自动取下一页，避免用户反复点按钮。
    // hasMore 是普通局部值而非 State，必须作为 key，否则闭包会一直读到首次组合时的旧值。
    val shouldLoadMore by remember(hasMore) {
        derivedStateOf {
            if (!hasMore) return@derivedStateOf false
            // 瀑布流里可见项不一定按下标排好，取最大的下标
            val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, isLoadingMore) {
        if (shouldLoadMore && !isLoadingMore) onLoadMore()
    }

    // 宽屏券卡分两三列（见 AdaptiveCardGrid），汇总卡和翻页提示横跨全宽
    com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        spacing = 10.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + topPadding, bottom = 12.dp)
    ) {
        fullLineItem {
            CouponSummaryCard(
                total = total,
                filter = filter,
                leftAmountFen = leftAmount,
                statusMessage = statusMessage
            )
        }
        items(records, key = { it.showCardId.ifBlank { it.sendId } }) { coupon ->
            CouponRecordCard(
                site = site,
                coupon = coupon,
                filter = filter,
                isReceiving = coupon.showCardId in receivingIds,
                onReceive = onReceive
            )
        }
        // 触底自动加载，不再让用户一页一页点"加载更多"。
        // 正常情况只显示一个轻量指示器；只有翻页失败时才需要用户介入重试。
        if (hasMore) {
            fullLineItem {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                }
            }
        } else if (loadMoreError != null) {
            fullLineItem {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        loadMoreError,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onLoadMore, enabled = !isLoadingMore) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun CouponSummaryCard(
    total: Int,
    filter: CouponFilter,
    leftAmountFen: Long,
    statusMessage: String?
) {
    val stateLabel = when (filter) {
        CouponFilter.AVAILABLE -> "可领取"
        CouponFilter.USABLE -> "可使用"
        CouponFilter.USED_UP -> "已用完"
        CouponFilter.EXPIRED -> "已过期"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Restaurant,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stateLabel, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "$total 张",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold
                )
                if (!statusMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        statusMessage,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("剩余面额", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(
                    "¥%.2f".format(leftAmountFen / 100.0),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CouponRecordCard(
    site: SiteSession,
    coupon: CouponRecord,
    filter: CouponFilter,
    isReceiving: Boolean,
    onReceive: (CouponRecord) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CouponImage(site = site, url = coupon.imageUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        coupon.voucherName,
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    CouponStatusPill(coupon, filter)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    coupon.typeName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "¥%.2f".format(coupon.leftAmountYuan),
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "剩余 / 面额 ¥%.2f".format(coupon.amountYuan),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "${coupon.startDate} 至 ${coupon.endDate}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (filter == CouponFilter.AVAILABLE) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onReceive(coupon) },
                        enabled = !isReceiving && coupon.showCardId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isReceiving) {
                            CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        } else {
                            Text("领取")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CouponImage(site: SiteSession, url: String) {
    var imageBytes by remember(url) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(url) {
        imageBytes = null
        if (url.isBlank()) return@LaunchedEffect
        imageBytes = withContext(Dispatchers.IO) {
            runCatching {
                site.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()
                }
            }.getOrNull()
        }
    }

    val bitmap = remember(imageBytes) {
        imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.ConfirmationNumber,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun CouponStatusPill(coupon: CouponRecord, filter: CouponFilter) {
    val gray = Color(0xFF7A7F87)
    val (text, color) = when (filter) {
        CouponFilter.AVAILABLE -> "可领取" to MiuixTheme.colorScheme.primary
        CouponFilter.USABLE -> "可使用" to MiuixTheme.colorScheme.primary
        CouponFilter.USED_UP -> "已用完" to gray
        CouponFilter.EXPIRED -> "已过期" to gray
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}
