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
import top.yukonga.miuix.kmp.basic.TabRowWithContour
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

    var selectedFilter by rememberSaveable { mutableStateOf(CouponFilter.USABLE) }
    var records by remember { mutableStateOf<List<CouponRecord>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable { mutableIntStateOf(1) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
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
        scope.launch {
            try {
                val pageData = withContext(Dispatchers.IO) {
                    api.queryCoupons(filter = filter, page = page, pageSize = 20)
                }
                total = pageData.total
                currentPage = page
                records = if (append) records + pageData.records else pageData.records
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(LoginType.COUPON, Routes.COUPON, onBack)
            } catch (e: Exception) {
                errorMessage = e.message ?: "加载失败"
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
                color = MiuixTheme.colorScheme.surfaceVariant,
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.surfaceVariant
            ) {
                TabRowWithContour(
                    tabs = CouponFilter.entries.map { it.label },
                    selectedTabIndex = CouponFilter.entries.indexOf(selectedFilter),
                    onTabSelected = { selectedFilter = CouponFilter.entries[it] },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

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
    onLoadMore: () -> Unit,
    filter: CouponFilter,
    statusMessage: String?,
    receivingIds: Set<String>,
    onReceive: (CouponRecord) -> Unit
) {
    val leftAmount = records.sumOf { it.leftAmountFen }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            CouponSummaryCard(
                visibleCount = records.size,
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
        if (records.size < total) {
            item {
                Button(
                    onClick = onLoadMore,
                    enabled = !isLoadingMore,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    } else {
                        Text("加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun CouponSummaryCard(
    visibleCount: Int,
    total: Int,
    filter: CouponFilter,
    leftAmountFen: Long,
    statusMessage: String?
) {
    val countLabel = when (filter) {
        CouponFilter.AVAILABLE -> "可领取 $total 张"
        CouponFilter.USABLE -> "可使用 $total 张"
        CouponFilter.USED_UP -> "已用完 $total 张"
        CouponFilter.EXPIRED -> "已过期 $total 张"
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
                Text("当前列表", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "$visibleCount / $total 张",
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
                Text(countLabel, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
