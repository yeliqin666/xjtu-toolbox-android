package com.xjtu.toolbox.venue

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.ui.adaptive.AdaptiveCardGrid
import com.xjtu.toolbox.ui.adaptive.fullLineItem
import com.xjtu.toolbox.ui.components.AppCardColor
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.SkeletonList
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import com.xjtu.toolbox.ui.glass.CouiTopBarScrollBehavior
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
 * [canPay] / [canCancel] 是「这一端做不做得到」，与订单模型自己的同名字段（服务端允不允许）是两回事。
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
    scrollBehavior: CouiTopBarScrollBehavior? = null,
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
        isLoading && orders.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = topPadding)) { SkeletonList(Modifier.fillMaxSize(), rowHeight = 88.dp) }

        error != null && orders.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = topPadding)) { ErrorState(message = error, onRetry = onRetry, modifier = Modifier.fillMaxSize()) }

        orders.isEmpty() -> FullPageState(Modifier.fillMaxSize().padding(top = topPadding)) { EmptyState(title = "还没订过场馆", modifier = Modifier.fillMaxSize()) }

        else -> {
            val gridState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()
            // 新单插到最前面时，列表默认按 key 守住原来那张卡：人在顶上也会被悄悄往下挪一格，
            // 新单藏在顶栏上面，顶栏的折叠跟着这一挪乱跳。原本就在顶上就留在顶上（新单露出来）；
            // 已经往下翻了就照旧守住正在看的那张。requestScrollToItem 和数据变化在同一次测量里生效，不会先跳再回。
            val firstOrderId = orders.firstOrNull()?.orderId
            var shownFirstId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(firstOrderId) }
            if (firstOrderId != shownFirstId) {
                if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
                    gridState.requestScrollToItem(0)
                }
                shownFirstId = firstOrderId
            }
            // 宽屏订单卡分两三列（见 AdaptiveCardGrid）
            AdaptiveCardGrid(
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = com.xjtu.toolbox.ui.glass.tabContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp + topPadding, bottom = 10.dp)),
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
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onDetail),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = AppCardColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // 标题 + 状态胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    order.venueName.ifBlank { "体育场馆订单" },
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    order.statusText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }

            // 场地明细：空的明细行不参与拼接，字段缺失时不要把空串冒充成一条明细
            //（campus-api 的订单没有 areaName，不过滤会画出一个光秃秃的「、」）
            val summary = order.details
                .map { detail ->
                    listOf(detail.date, detail.timeSlot, detail.areaName)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
                .filter { it.isNotBlank() }
                .joinToString("、")
            Spacer(Modifier.height(6.dp))
            Text(
                summary.ifBlank { "暂无场地明细" },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 下单时间（订单号放详情里）+ 金额
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    order.createdAt,
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                Text(
                    "¥${money2(order.price)}",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            // 整张卡点开就是详情，底下只放真正的操作
            val pay = canPay && order.canPay
            // 订单已经到了起效时间就不再给取消
            val cancel = canCancel && order.canCancel && !order.hasStarted(com.xjtu.toolbox.ui.rememberCampusNow())
            if (pay || cancel) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    val cs = MiuixTheme.colorScheme
                    if (cancel) OrderAction("取消订单", cs.error.copy(alpha = 0.10f), cs.error, onCancel)
                    if (pay) OrderAction("去支付", cs.primary, cs.onPrimary, onPay)
                }
            }
        }
    }
}

@Composable
private fun OrderAction(text: String, container: Color, content: Color, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        color = content,
    )
}
