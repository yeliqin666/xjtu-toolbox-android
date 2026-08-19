package com.xjtu.toolbox.venue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.LoadingState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「我的订单」页。
 *
 * 列表本身只负责呈现和分页触发，详情、取消确认、支付引导由父页面统一持有，
 * 这样从场馆页切换 Tab 时不会丢失弹窗状态，也能复用同一套认证错误处理。
 */
@Composable
fun VenueOrdersContent(
    orders: List<VenueApi.OrderInfo>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    error: String?,
    hasMore: Boolean,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDetail: (VenueApi.OrderInfo) -> Unit,
    onCancel: (VenueApi.OrderInfo) -> Unit,
    onPay: (VenueApi.OrderInfo) -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null
) {
    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefresh(
        isRefreshing = isLoading && orders.isNotEmpty(),
        onRefresh = onRefresh,
        pullToRefreshState = pullToRefreshState,
        topAppBarScrollBehavior = scrollBehavior,
        modifier = modifier.fillMaxSize()
    ) {
    when {
        isLoading && orders.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            item { Box(Modifier.fillParentMaxSize()) { LoadingState(message = "加载订单...", modifier = Modifier.fillMaxSize()) } }
        }

        error != null && orders.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            item { Box(Modifier.fillParentMaxSize()) { ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize()) } }
        }

        orders.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            item { Box(Modifier.fillParentMaxSize()) { EmptyState(title = "暂无订单", subtitle = "预约场馆后，订单会显示在这里", modifier = Modifier.fillMaxSize()) } }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (error != null) {
                    item(key = "refresh-error") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MiuixTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    error,
                                    modifier = Modifier.weight(1f),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onErrorContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(text = "重试", onClick = onRetry)
                            }
                        }
                    }
                }

                items(orders, key = { it.orderId }) { order ->
                    OrderCard(
                        order = order,
                        onDetail = { onDetail(order) },
                        onCancel = { onCancel(order) },
                        onPay = { onPay(order) }
                    )
                }

                item(key = "pagination") {
                    if (hasMore) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isLoadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                TextButton(text = "加载更多", onClick = onLoadMore)
                            }
                        }
                    } else {
                        Text(
                            "已显示全部订单",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun OrderCard(
    order: VenueApi.OrderInfo,
    onDetail: () -> Unit,
    onCancel: () -> Unit,
    onPay: () -> Unit
) {
    val statusColor = when (order.status) {
        0 -> Color(0xFFE27818)
        1 -> Color(0xFF2E8B57)
        2 -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetail),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    order.venueName.ifBlank { "体育场馆订单" },
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    order.statusText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.width(6.dp))
                val summary = order.details.joinToString("、") { detail ->
                    listOf(detail.date, detail.timeSlot, detail.areaName)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
                Text(
                    summary.ifBlank { "暂无场地明细" },
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "查看详情",
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "订单号 ${order.orderId}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (order.createdAt.isNotBlank()) {
                        Text(
                            order.createdAt,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                Text(
                    "¥${"%.2f".format(order.price)}",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(text = "详情", onClick = onDetail)
                if (order.canPay) {
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onPay) { Text("去支付") }
                }
                if (order.canCancel) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(text = "取消", onClick = onCancel)
                }
            }
        }
    }
}
