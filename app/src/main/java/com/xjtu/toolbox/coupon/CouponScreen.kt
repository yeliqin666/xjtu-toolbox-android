package com.xjtu.toolbox.coupon

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.LocalAppLoginState
import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginType
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
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun CouponScreen(
    site: SiteSession,
    onBack: () -> Unit
) {
    val appLoginState = LocalAppLoginState.current
    val api = remember(site) { CouponApi(site) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // 首页摘要用：-1 表示本次会话还没查过该分类，不参与拼接
    var pendingCount by remember { mutableIntStateOf(-1) }
    var usableCount by remember { mutableIntStateOf(-1) }

    var selectedFilter by rememberSaveable { mutableStateOf(CouponFilter.USABLE) }
    var records by remember { mutableStateOf<List<CouponRecord>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var receivingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val pullToRefreshState = rememberPullToRefreshState()

    fun loadPage(filter: CouponFilter = selectedFilter, page: Int = 1, append: Boolean = false, silent: Boolean = false) {
        when {
            append -> isLoadingMore = true
            silent -> {}  // silent: 由外部 isRefreshing 控制下拉指示器，保留当前列表
            else -> isLoading = true
        }
        errorMessage = null
        loadMoreError = null
        scope.launch {
            try {
                val pageData = withContext(Dispatchers.IO) {
                    api.queryCoupons(filter = filter, page = page, pageSize = 20)
                }
                total = pageData.total
                currentPage = page
                records = if (append) records + pageData.records else pageData.records
                // 顺手把摘要留给首页（首页自己不发请求，见 HomeStats）。
                // 只在第一页、且是「可领取/可使用」这两个用户真正关心的分类时记，
                // 「已用完/已过期」的条数写上去只会误导。
                if (!append && filter == CouponFilter.AVAILABLE) pendingCount = pageData.total
                if (!append && filter == CouponFilter.USABLE) usableCount = pageData.total
                if (pendingCount >= 0 || usableCount >= 0) {
                    val parts = buildList {
                        if (pendingCount > 0) add("$pendingCount 个待领取")
                        if (usableCount > 0) add("$usableCount 个待使用")
                    }
                    com.xjtu.toolbox.home.HomeStats.push(
                        appContext,
                        Routes.COUPON,
                        parts.firstOrNull() ?: "暂无可用",
                        parts.drop(1).firstOrNull()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.COUPON, Routes.COUPON, onBack)
            } catch (e: Exception) {
                // 翻页失败只提示，保住已加载的列表；整页失败才切到错误页
                if (append) {
                    loadMoreError = e.message ?: "加载更多失败"
                } else {
                    errorMessage = e.message ?: "加载失败"
                }
            } finally {
                isLoading = false
                isLoadingMore = false
                isRefreshing = false
            }
        }
    }

    fun receiveCoupon(coupon: CouponRecord) {
        val id = coupon.showCardId
        if (id.isBlank() || id in receivingIds) return
        receivingIds = receivingIds + id
        statusMessage = null
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    val fetched = runCatching { api.getCouponDetail(id) }.getOrNull()
                    api.activateCoupon(id)
                    fetched
                }
                statusMessage = detail?.title?.takeIf { it.isNotBlank() }
                    ?: "已领取 ${coupon.voucherName}"
                loadPage(selectedFilter, page = 1, append = false, silent = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.COUPON, Routes.COUPON, onBack)
            } catch (e: Exception) {
                statusMessage = "领取失败：${e.message ?: "网络异常"}"
            } finally {
                receivingIds = receivingIds - id
            }
        }
    }

    LaunchedEffect(selectedFilter) {
        records = emptyList()
        total = 0
        currentPage = 1
        loadPage(selectedFilter)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "加餐券",
                largeTitle = "加餐券",
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppSegmentedTabs(
                tabs = CouponFilter.entries.map { it.label },
                selectedTabIndex = CouponFilter.entries.indexOf(selectedFilter),
                onTabSelected = { selectedFilter = CouponFilter.entries[it] },
            )

            PullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    loadPage(selectedFilter, page = 1, append = false, silent = true)
                },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = scrollBehavior,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    isLoading -> LazyColumn(Modifier.fillMaxSize()) {
                        item { Box(Modifier.fillParentMaxSize()) { LoadingState("正在加载加餐券...", Modifier.fillMaxSize()) } }
                    }
                    errorMessage != null -> LazyColumn(Modifier.fillMaxSize()) {
                        item { Box(Modifier.fillParentMaxSize()) {
                            ErrorState(
                                message = errorMessage ?: "加载失败",
                                onRetry = { loadPage(selectedFilter) },
                                modifier = Modifier.fillMaxSize(),
                                icon = Icons.Default.ErrorOutline
                            )
                        } }
                    }
                    records.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item { Box(Modifier.fillParentMaxSize()) {
                            EmptyState(
                                title = selectedFilter.emptyTitle,
                                subtitle = "下拉可刷新重试",
                                icon = Icons.Outlined.ConfirmationNumber,
                                modifier = Modifier.fillMaxSize()
                            )
                        } }
                    }
                    else -> CouponList(
                        site = site,
                        records = records,
                        total = total,
                        filter = selectedFilter,
                        statusMessage = statusMessage,
                        receivingIds = receivingIds,
                        onReceive = ::receiveCoupon,
                        isLoadingMore = isLoadingMore,
                        // 翻页失败时停止自动加载，否则会对着挂掉的接口无限重试
                        loadMoreError = loadMoreError,
                        onLoadMore = { loadPage(selectedFilter, currentPage + 1, append = true) }
                    )
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
    onReceive: (CouponRecord) -> Unit
) {
    val leftAmount = records.sumOf { it.leftAmountFen }
    val hasMore = records.size < total && loadMoreError == null
    val listState = rememberLazyListState()

    // 接近底部（还剩 3 项可见）时自动取下一页，避免用户反复点按钮。
    // hasMore 是普通局部值而非 State，必须作为 key，否则闭包会一直读到首次组合时的旧值。
    val shouldLoadMore by remember(hasMore) {
        derivedStateOf {
            if (!hasMore) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, isLoadingMore) {
        if (shouldLoadMore && !isLoadingMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
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
            item {
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
            item {
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
