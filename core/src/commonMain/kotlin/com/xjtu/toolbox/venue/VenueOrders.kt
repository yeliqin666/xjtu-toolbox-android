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
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.LoadingState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「我的订单」页。
 *
 * 列表本身只负责呈现和分页触发，详情、取消确认、支付引导由父页面统一持有，
 * 这样从场馆页切换 Tab 时不会丢失弹窗状态，也能复用同一套认证错误处理。
 *
 * 从 `:app` 搬进 `:core` 时加了 [canPay] / [canCancel] 两个参数：订单模型自己的
 * `canPay`/`canCancel` 只说「**服务端**允不允许」，而「**这一端**做不做得到」是另一端的事
 *（campus-api 的场馆模块只读 ⇒ Web 两条都是 false）。点了必然失败的按钮一个都不画，
 * 所以这里按两个开关关掉「去支付」「取消」。
 *
 * 订单字段缺失时这块 UI 会如实留白：campus-api 的订单明细没有场地名与场馆名
 *（见 `CampusVenueApi` 的 KDoc），于是 [OrderCard] 的行摘要落到「暂无场地明细」、
 * 标题落到「体育场馆订单」——这是 :app 原本就有的兜底，不是新编出来的内容。
 */
@Composable
fun VenueOrdersContent(
    orders: List<OrderInfo>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    error: String?,
    hasMore: Boolean,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDetail: (OrderInfo) -> Unit,
    onCancel: (OrderInfo) -> Unit,
    onPay: (OrderInfo) -> Unit,
    /** 本端能不能支付（[VenueSource.canBook]）。false ⇒ 订单卡上没有「去支付」。 */
    canPay: Boolean = true,
    /** 本端能不能取消（[VenueSource.canCancel]）。false ⇒ 订单卡上没有「取消」。 */
    canCancel: Boolean = true,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    /** 玻璃顶栏（含标签行）的高度，放进列表顶部留白。 */
    topPadding: Dp = 0.dp,
) {
    AppPullToRefresh(
        isRefreshing = isLoading && orders.isNotEmpty(),
        onRefresh = onRefresh,
        scrollBehavior = scrollBehavior,
        topPadding = topPadding,
        modifier = modifier.fillMaxSize(),
    ) {
    when {
        isLoading && orders.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = topPadding)) { LoadingState(message = "加载订单...", modifier = Modifier.fillMaxSize()) }

        error != null && orders.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = topPadding)) { ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize()) }

        orders.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = topPadding)) { EmptyState(title = "还没订过场馆", subtitle = "预约场馆后，订单会显示在这里", modifier = Modifier.fillMaxSize()) }

        else -> {
            // 宽屏订单卡分两三列（见 AdaptiveCardGrid）
            AdaptiveCardGrid(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp + topPadding, bottom = 10.dp),
                spacing = 10.dp,
            ) {
                if (error != null) {
                    fullLineItem(key = "refresh-error") {
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
                        onPay = { onPay(order) },
                        canPay = canPay,
                        canCancel = canCancel,
                    )
                }

                fullLineItem(key = "pagination") {
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
                            textAlign = TextAlign.Center
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
    order: OrderInfo,
    onDetail: () -> Unit,
    onCancel: () -> Unit,
    onPay: () -> Unit,
    /** 见 [VenueOrdersContent]：本端做不做得到（在订单自己的 `canPay`/`canCancel` 之上再与一次）。 */
    canPay: Boolean,
    canCancel: Boolean,
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
                // 空的明细行不参与拼接：**字段缺失时不要把空串冒充成一条明细**。
                // :app 那侧上游字段齐全，每一行都拼得出来 ⇒ 这条过滤对 Android 的输出没有影响；
                // 而 Web 那侧 campus-api 的白名单里没有 `areaName`（见 CampusVenueApi 的 KDoc），
                // 不过滤就会画出一个光秃秃的「、」。全部为空时下面那个 ifBlank 会落到「暂无场地明细」。
                val summary = order.details
                    .map { detail ->
                        listOf(detail.date, detail.timeSlot, detail.areaName)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("、")
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
                    "¥${money2(order.price)}",
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
                if (canPay && order.canPay) {
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onPay) { Text("去支付") }
                }
                if (canCancel && order.canCancel) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(text = "取消", onClick = onCancel)
                }
            }
        }
    }
}
