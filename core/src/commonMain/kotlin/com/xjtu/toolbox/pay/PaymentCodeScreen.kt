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
import com.xjtu.toolbox.platform.KeepScreenOn
import com.xjtu.toolbox.platform.classifyNetworkFailure
import com.xjtu.toolbox.platform.code128Modules
import com.xjtu.toolbox.platform.qrCodeModules
import androidx.compose.foundation.Canvas
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
 * 付款码屏（`AppRoute.PaymentCode` / `:app/pay`）—— 从 `:app` 同包同名搬进 `:core`，
 * Android 与桌面画**同一份**。
 *
 * ## 搬进来的改动只有五处（都是「宿主能力」那一类，不是业务）
 *
 * | 搬迁前（`:app`） | 这里 |
 * |---|---|
 * | `PaymentCodeApi(site)`（okhttp + `SiteSession`） | [PaymentCodeSource] 端口（实现仍在 `:data` 的 `PaymentCodeApi`，两端共用同一份） |
 * | `WindowManager`：窗口满亮 + `FLAG_KEEP_SCREEN_ON` | [KeepScreenOn]（`:core` 既有缝，Android actual 就是同一个「屏幕常亮」）+ **窗口亮度那一半留在 `:app` 的适配里**（见 `:app` 的 `pay/AppPaymentCode.kt`：Android 专属的 `WindowManager` 不该进 commonMain） |
 * | `Bitmap` + `QrBitmap`/`Code128Writer` | `:core` 的码阵缝（[qrCodeModules] / [code128Modules]），渲染是这里那 20 行 `Canvas` |
 * | `context.getSharedPreferences("payment_code")` | [PaymentSelectionStore]（同一个存储名、同一批键） |
 * | `withContext(Dispatchers.IO)`（3 处） | 删（实现方自己负责 IO，见 [PaymentCodeSource] 的 KDoc） |
 *
 * 其余逐字未动：12 秒一轮的刷新与倒计时、三档加载文案、错误分档（网络/认证/超时）、
 * 点击重试、加餐券勾选与「同步失败，请重试」、以及每一处排版与文案。
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

    // 「向商家出示」时屏幕不该自己黑掉。搬迁前是在 `WindowManager` 上打
    // FLAG_KEEP_SCREEN_ON，现在走 `:core` 的既有缝（Android 的 actual 就是同一个标记）。
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

                        CodeMatrixView(
                            matrix = qrMatrix,
                            // 与搬迁前那张 600×600 的位图同一个口径：外边留 1 个模块的白边
                            //（`QrBitmap.generate` 的 `MARGIN=1`），码本身居于正中。
                            quietZoneModules = 1,
                            modifier = Modifier.size(200.dp),
                        )

                        Spacer(Modifier.height(16.dp))

                        CodeMatrixView(
                            matrix = barMatrix,
                            // 静默区已经含在 Code128 的条纹阵列里（writer 默认留 10 个模块，
                            // 与搬迁前那 800 像素宽的位图同一个比例）⇒ 这里不再另留。
                            quietZoneModules = 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(70.dp),
                        )

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

/**
 * 把一个 [CodeMatrix] 画成码 —— **三端同一份**，不需要任何平台的像素缓冲 API
 * （搬迁前是 `Bitmap` + `Image(asImageBitmap)` 那一套：Android 有，桌面要另写一份 skiko 的）。
 *
 * 两处刻意的对齐（见 [CodeMatrix] 的 KDoc）：
 *  - **整块白底**：码必须是深色模块落在白底上才扫得动，而卡片的底色是主题色（不是白）；
 *  - **静默区**：`quietZoneModules` 个模块的白边（二维码 1 个模块，与搬迁前 `QrBitmap` 的
 *    `MARGIN=1` 同宽；条形码 0 —— 它的静默区已经编在条纹阵列里）。
 *
 * 格子按**整像素**取（`floor`）并居中：避免半个像素的格子把边缘糊成灰的（那种图扫得动，
 * 但没必要），末尾多出来的那一两个像素就是更大的白边。
 */
@Composable
private fun CodeMatrixView(
    matrix: CodeMatrix?,
    quietZoneModules: Int,
    modifier: Modifier,
) {
    if (matrix == null) return
    Canvas(modifier) {
        drawRect(color = Color.White, topLeft = Offset.Zero, size = size)
        val cols = matrix.width + 2 * quietZoneModules
        val rows = matrix.height + 2 * quietZoneModules
        val cellW = (size.width / cols).coerceAtLeast(1f)
        val cellH = (size.height / rows).coerceAtLeast(1f)
        val originX = (size.width - cellW * cols) / 2f
        val originY = (size.height - cellH * rows) / 2f
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(
                        originX + (x + quietZoneModules) * cellW,
                        originY + (y + quietZoneModules) * cellH,
                    ),
                    size = Size(cellW, cellH),
                )
            }
        }
    }
}

@Composable
private fun VoucherSelectorCard(
    vouchers: List<PaymentVoucher>,
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
            // 一行标题，状态用右边一个词说：暂无可用 / 未使用 / 已选 N 张。勾选框本身就说明了用法
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onToggle(voucher) },
                            color = if (checked) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                            else MiuixTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                                    onClick = { onToggle(voucher) }
                                )
                                Spacer(Modifier.width(8.dp))
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

/**
 * 分转元（`"¥%.2f".format(fen / 100.0)` 那一句是 JVM 专属写法，`:core` 还要编到 wasm）。
 * 与 `:core` 既有先例（`coupon/CouponScreen.kt` 的 `formatYuan`）同一口径：整数分算，
 * 不经过 double —— `500` 分 → `¥5.00`，`505` 分 → `¥5.05`。
 */
private fun formatYuan(fen: Long): String {
    val sign = if (fen < 0) "-" else ""
    val abs = if (fen < 0) -fen else fen
    return "$sign¥${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
