package com.xjtu.toolbox.pay

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Checkbox

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.xjtu.toolbox.auth.SiteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val REFRESH_SECONDS = 12
private const val AUTH_TIMEOUT_MS = 20_000L
private const val FETCH_TIMEOUT_MS = 10_000L
private const val PAYMENT_PREFS = "payment_code"
private const val PREF_SELECTED_VOUCHERS = "selected_voucher_ids"
private const val PREF_SELECTION_CONFIGURED = "voucher_selection_configured"

private fun BitMatrix.toBitmap(foreground: Int, background: Int): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (get(x, y)) foreground else background
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private fun generateQrCode(text: String, size: Int = 600): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        .toBitmap(android.graphics.Color.BLACK, android.graphics.Color.WHITE)
}

private fun generateBarcode(text: String, width: Int = 800, height: Int = 200): Bitmap {
    return Code128Writer().encode(text, BarcodeFormat.CODE_128, width, height)
        .toBitmap(android.graphics.Color.BLACK, android.graphics.Color.WHITE)
}

@Composable
fun PaymentCodeDialog(
    site: SiteSession,
    onDismiss: () -> Unit
) {
    val api = remember(site) { PaymentCodeApi(site) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var loadingStage by remember { mutableStateOf("连接中…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var barCodeNumber by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var barBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var countdown by remember { mutableIntStateOf(REFRESH_SECONDS) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var vouchers by remember { mutableStateOf<List<PaymentVoucher>>(emptyList()) }
    var voucherLoadAttempted by remember { mutableStateOf(false) }
    var isVoucherLoading by remember { mutableStateOf(false) }
    var voucherError by remember { mutableStateOf<String?>(null) }
    var selectedVoucherIds by remember(context) { mutableStateOf(loadSelectedVoucherIds(context)) }
    var selectionConfigured by remember(context) { mutableStateOf(hasVoucherSelectionConfigured(context)) }
    var isVoucherSyncing by remember { mutableStateOf(false) }
    var voucherStatusMessage by remember { mutableStateOf<String?>(null) }
    val latestSelectedVoucherIds by rememberUpdatedState(selectedVoucherIds)
    val latestSelectionConfigured by rememberUpdatedState(selectionConfigured)

    BackHandler(enabled = isLoading) {
        onDismiss()
    }

    val activity = context as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val orig = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = orig }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        errorMessage = null
        loadingStage = "连接中…"

        val authOk = try {
            withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { api.authenticate() }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = when (e) {
                is java.io.IOException -> "网络连接失败，请检查网络"
                else -> "认证失败，请稍后重试"
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
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { api.getVouchers() }
                } ?: throw RuntimeException("获取超时")
            }.onSuccess {
                vouchers = it
            }.onFailure {
                voucherError = it.message ?: "加餐券加载失败"
            }
            isVoucherLoading = false
        }

        loadingStage = "加载付款码…"

        while (isActive) {
            try {
                val code = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { api.getBarCode() }
                } ?: throw RuntimeException("获取超时")
                if (latestSelectionConfigured) {
                    withContext(Dispatchers.IO) { api.updateVoucherStatus(latestSelectedVoucherIds) }
                    voucherStatusMessage = if (latestSelectedVoucherIds.isEmpty()) {
                        "已关闭加餐券抵扣"
                    } else {
                        "已启用 ${latestSelectedVoucherIds.size} 张加餐券"
                    }
                }
                barCodeNumber = code
                qrBitmap?.recycle()
                qrBitmap = generateQrCode(code)
                barBitmap?.recycle()
                barBitmap = generateBarcode(code)
                isLoading = false
                errorMessage = null
            } catch (e: Exception) {
                if (!isActive) return@LaunchedEffect
                errorMessage = "刷新失败: ${e.message}"
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
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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

                        qrBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "付款二维码",
                                modifier = Modifier.size(200.dp),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        barBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "付款条形码",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(70.dp),
                                contentScale = ContentScale.FillBounds
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
                                saveSelectedVoucherIds(context, next)
                                voucherStatusMessage = "正在同步加餐券选择…"
                                isVoucherSyncing = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { api.updateVoucherStatus(next) }
                                        voucherStatusMessage = if (next.isEmpty()) {
                                            "已关闭加餐券抵扣"
                                        } else {
                                            "已启用 ${next.size} 张加餐券，付款时自动抵扣"
                                        }
                                    } catch (e: Exception) {
                                        voucherStatusMessage = "同步失败：${e.message ?: "网络异常"}"
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
                                        vouchers = withContext(Dispatchers.IO) { api.getVouchers() }
                                    } catch (e: Exception) {
                                        voucherError = e.message ?: "加餐券加载失败"
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("加餐券抵扣", style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.Bold)
                    Text(
                        "在本页勾选后写入付款会话，付款时自动抵扣",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                if (isSyncing) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(10.dp))
            when {
                isLoading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("加载可用加餐券…", style = MiuixTheme.textStyles.footnote1)
                }
                error != null -> Row(
                    Modifier.fillMaxWidth(),
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
                vouchers.isEmpty() -> Text(
                    "暂无可用于付款码抵扣的加餐券",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        "¥%.2f · 至 %s%s".format(
                                            voucher.amountYuan,
                                            voucher.endDate.ifBlank { "未知" },
                                            if (voucher.serverFlag == "1" && !checked) " · 网页曾启用" else ""
                                        ),
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
                            "还有 ${vouchers.size - 4} 张，可在加餐券页面管理",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    statusMessage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (statusMessage.contains("失败")) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun loadSelectedVoucherIds(context: Context): Set<String> {
    val raw = context.getSharedPreferences(PAYMENT_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_SELECTED_VOUCHERS, "")
        .orEmpty()
    return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

private fun hasVoucherSelectionConfigured(context: Context): Boolean =
    context.getSharedPreferences(PAYMENT_PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_SELECTION_CONFIGURED, false)

private fun saveSelectedVoucherIds(context: Context, ids: Set<String>) {
    context.getSharedPreferences(PAYMENT_PREFS, Context.MODE_PRIVATE).edit()
        .putString(PREF_SELECTED_VOUCHERS, ids.joinToString(","))
        .putBoolean(PREF_SELECTION_CONFIGURED, true)
        .apply()
}
