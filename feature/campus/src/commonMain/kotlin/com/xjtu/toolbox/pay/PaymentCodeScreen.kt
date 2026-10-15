package com.xjtu.toolbox.pay

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Checkbox

import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.platform.CodeMatrix
import com.xjtu.toolbox.ui.components.CodeMatrixView
import com.xjtu.toolbox.platform.KeepScreenOn
import com.xjtu.toolbox.platform.classifyNetworkFailure
import com.xjtu.toolbox.platform.code128Modules
import com.xjtu.toolbox.platform.qrCodeModules
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val REFRESH_SECONDS = 12
private const val AUTH_TIMEOUT_MS = 20_000L
private const val FETCH_TIMEOUT_MS = 10_000L

/**
 * 付款码屏，Android 与桌面同一份。
 *
 * ## 两端各自的入口
 *
 * - Android：`AppRoot` 里那个 `Dialog` + `AwaitSite` 包着 [com.xjtu.toolbox.pay.PaymentCodeDialog]
 *   （`:app` 的适配，含窗口亮度）；
 * - 桌面：`AppRoute.PaymentCode` → `DesktopSiteGate(校园卡)` → 本屏（`onDismiss` = 回图书馆首页）。
 *
 * @param source 取数（`:data` 的 `PaymentCodeApi`）
 * @param selections 加餐券勾选的落盘
 * @param onDismiss 关闭这一屏（Android = 关弹窗；桌面 = 回图书馆首页）
 */
@Composable
fun PaymentCodeScreen(
    source: PaymentCodeSource,
    selections: PaymentSelectionStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var loadingStage by remember { mutableStateOf("连接中…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var barCodeNumber by remember { mutableStateOf("") }
    var qrMatrix by remember { mutableStateOf<CodeMatrix?>(null) }
    var barMatrix by remember { mutableStateOf<CodeMatrix?>(null) }
    var countdown by remember { mutableIntStateOf(REFRESH_SECONDS) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var vouchers by remember { mutableStateOf<List<PaymentVoucher>>(emptyList()) }
    var voucherLoadAttempted by remember { mutableStateOf(false) }
    var isVoucherLoading by remember { mutableStateOf(false) }
    var voucherError by remember { mutableStateOf<String?>(null) }
    var selectedVoucherIds by remember(selections) { mutableStateOf(selections.selectedIds()) }
    var selectionConfigured by remember(selections) { mutableStateOf(selections.isConfigured()) }
    var isVoucherSyncing by remember { mutableStateOf(false) }
    var voucherStatusMessage by remember { mutableStateOf<String?>(null) }
    val latestSelectedVoucherIds by rememberUpdatedState(selectedVoucherIds)
    val latestSelectionConfigured by rememberUpdatedState(selectionConfigured)

    BackHandler(enabled = isLoading) {
        onDismiss()
    }

    // 出示付款码时保持亮屏。
    KeepScreenOn(true)

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        errorMessage = null
        loadingStage = "连接中…"

        val authOk = try {
            withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                source.authenticate()
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = if (classifyNetworkFailure(e) != null) {
                "网络连接失败，请检查网络"
            } else {
                "认证失败，请稍后重试"
            }
            isLoading = false
            return@LaunchedEffect
        }
        if (authOk == null) {
            errorMessage = "认证超时，请检查网络"
            isLoading = false
            return@LaunchedEffect
        }

        if (!voucherLoadAttempted) {
            voucherLoadAttempted = true
            isVoucherLoading = true
            voucherError = null
            runCatching {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { source.getVouchers() }
                    ?: throw RuntimeException("获取超时")
            }.onSuccess {
                vouchers = it
            }.onFailure {
                voucherError = com.xjtu.toolbox.error.FriendlyError.of(it, "加载加餐券")
            }
            isVoucherLoading = false
        }

        loadingStage = "加载付款码…"

        while (isActive) {
            try {
                val code = withTimeoutOrNull(FETCH_TIMEOUT_MS) { source.getBarCode() }
                    ?: throw RuntimeException("获取超时")
                if (latestSelectionConfigured) {
                    source.updateVoucherStatus(latestSelectedVoucherIds)
                    voucherStatusMessage = null
                }
                barCodeNumber = code
                qrMatrix = qrCodeModules(code)
                barMatrix = code128Modules(code)
                isLoading = false
                errorMessage = null
            } catch (e: Exception) {
                if (!isActive) return@LaunchedEffect
                errorMessage = com.xjtu.toolbox.error.FriendlyError.of(e, "刷新")
                isLoading = false
            }

            countdown = REFRESH_SECONDS
            repeat(REFRESH_SECONDS) {
                if (!isActive) return@LaunchedEffect
                delay(1000)
                countdown--
            }
        }
    }

    // UI: Card (used inside NavHost dialog() route)
    // 限宽 480dp：弹窗关掉了平台默认宽度（手机上要贴满屏宽），平板横屏时卡片会横跨整屏，
    // 条形码按 FillBounds 被拉成一长条，扫码枪反而读不准。
    Card(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "付款码",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(loadingStage, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }

                errorMessage != null -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            errorMessage!!,
                            color = MiuixTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(text = "点击重试", onClick = { refreshTrigger++ })
                    }
                }

                else -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { refreshTrigger++ }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "向商家出示此码 · 点击刷新",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )

                        Spacer(Modifier.height(16.dp))

                        // 编码器编不出（null）时只画下面那串数字，不画假码。
                        if (qrMatrix == null && barMatrix == null) {
                            Text(
                                "本端没有条码 / 二维码编码器，画不出可扫的码；下面这串数字就是付款码本身。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            CodeMatrixView(
                                matrix = qrMatrix,
                                // 外边留 1 个模块的白边。
                                quietZoneModules = 1,
                                modifier = Modifier.size(200.dp),
                            )

                            Spacer(Modifier.height(16.dp))

                            CodeMatrixView(
                                matrix = barMatrix,
                                // Code128 阵列已含静默区。
                                quietZoneModules = 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(70.dp),
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        Text(
                            barCodeNumber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.outline,
                            letterSpacing = 1.5.sp
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "${countdown}s",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.outline
                        )

                        Spacer(Modifier.height(14.dp))

                        VoucherSelectorCard(
                            vouchers = vouchers,
                            // 这一端能不能改勾选：serve 契约没有那个写端点（`canSelectVouchers`）⇒ false 时
                            // 那几行券只读（不画勾选框、整行不可点），不是画一个点了会报错的勾选框
                            canSelect = source.canSelectVouchers,
                            selectedIds = selectedVoucherIds,
                            isLoading = isVoucherLoading,
                            isSyncing = isVoucherSyncing,
                            error = voucherError,
                            statusMessage = voucherStatusMessage,
                            onToggle = { voucher ->
                                val next = if (voucher.showCardId in selectedVoucherIds) {
                                    selectedVoucherIds - voucher.showCardId
                                } else {
                                    selectedVoucherIds + voucher.showCardId
                                }
                                selectedVoucherIds = next
                                selectionConfigured = true
                                selections.save(next)
                                voucherStatusMessage = null
                                isVoucherSyncing = true
                                scope.launch {
                                    try {
                                        source.updateVoucherStatus(next)
                                    } catch (e: Exception) {
                                        voucherStatusMessage = "同步失败，请重试"
                                    } finally {
                                        isVoucherSyncing = false
                                    }
                                }
                            },
                            onRetry = {
                                isVoucherLoading = true
                                voucherError = null
                                scope.launch {
                                    try {
                                        vouchers = source.getVouchers()
                                    } catch (e: Exception) {
                                        voucherError = com.xjtu.toolbox.error.FriendlyError.of(e, "加载加餐券")
                                    } finally {
                                        isVoucherLoading = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}


/** 加餐券那一栏。 */
@Composable
private fun VoucherSelectorCard(
    vouchers: List<PaymentVoucher>,
    /** `false` ⇒ 只读展示（不画勾选框、整行也不可点）—— 见 `PaymentCodeSource.canSelectVouchers`。 */
    canSelect: Boolean,
    selectedIds: Set<String>,
    isLoading: Boolean,
    isSyncing: Boolean,
    error: String?,
    statusMessage: String?,
    onToggle: (PaymentVoucher) -> Unit,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // 一行标题，状态用右边一个词说：暂无可用 / 未使用 / 已选 N 张（只读那一档见 [canSelect]）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "加餐券抵扣",
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                val selected = vouchers.count { it.showCardId in selectedIds }
                when {
                    isLoading || isSyncing -> CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    error != null -> Unit
                    vouchers.isEmpty() -> Text("暂无可用", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    !canSelect -> Text("共 ${vouchers.size} 张 · 本端只读", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    selected > 0 -> Text("已选 $selected 张", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    else -> Text("未使用", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            when {
                isLoading || (error == null && vouchers.isEmpty()) -> Unit
                error != null -> Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        error,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(text = "重试", onClick = onRetry)
                }
                else -> Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vouchers.take(4).forEach { voucher ->
                        val checked = voucher.showCardId in selectedIds
                        Surface(
                            // 只读那一档（canSelect = false）：没有勾选，也就没有可点的地方
                            modifier = if (canSelect) {
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onToggle(voucher) }
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                            },
                            color = if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                            else MiuixTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (canSelect) {
                                    Checkbox(
                                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                                        onClick = { onToggle(voucher) }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        voucher.voucherName,
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${formatYuan(voucher.amountFen)} · 至 ${voucher.endDate.ifBlank { "—" }}",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (vouchers.size > 4) {
                        Text(
                            "还有 ${vouchers.size - 4} 张",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            // 只剩出错时才有：成功的状态由标题行右边那个词体现
            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    statusMessage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 分转元（`"¥%.2f".format(fen / 100.0)` 那一句是 JVM 专属写法，`:core` 还要编到 wasm）。 */
private fun formatYuan(fen: Long): String {
    val sign = if (fen < 0) "-" else ""
    val abs = if (fen < 0) -fen else fen
    return "$sign¥${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
