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

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.xjtu.toolbox.auth.CampusCardLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val REFRESH_SECONDS = 12
private const val AUTH_TIMEOUT_MS = 20_000L
private const val FETCH_TIMEOUT_MS = 10_000L

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
    login: CampusCardLogin,
    onDismiss: () -> Unit
) {
    val api = remember(login) { PaymentCodeApi(login) }
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var loadingStage by remember { mutableStateOf("连接中…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var barCodeNumber by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var barBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var countdown by remember { mutableIntStateOf(REFRESH_SECONDS) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

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

        loadingStage = "加载付款码…"

        while (isActive) {
            try {
                val code = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { api.getBarCode() }
                } ?: throw RuntimeException("获取超时")
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
        Column(Modifier.padding(20.dp)) {
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
                    }
                }
            }
        }
    }
}
