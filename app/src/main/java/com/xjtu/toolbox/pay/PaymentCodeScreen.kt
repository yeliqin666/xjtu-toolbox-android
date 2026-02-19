package com.xjtu.toolbox.pay

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

private const val REFRESH_SECONDS = 12
private const val AUTH_TIMEOUT_MS = 20_000L
private const val FETCH_TIMEOUT_MS = 10_000L

// ── 条形码/二维码生成 ──────────────────

private fun BitMatrix.toBitmap(foreground: Int, background: Int): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (x in 0 until width) for (y in 0 until height)
        bmp.setPixel(x, y, if (get(x, y)) foreground else background)
    return bmp
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

// ── 弹窗式付款码（配合 NavHost dialog() 使用） ──────────────────

@Composable
fun PaymentCodeDialog(
    client: OkHttpClient,
    onDismiss: () -> Unit
) {
    val api = remember { PaymentCodeApi(client) }
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var loadingStage by remember { mutableStateOf("正在连接校园认证…") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var barCodeNumber by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var barBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var countdown by remember { mutableIntStateOf(REFRESH_SECONDS) }
    var refreshTrigger by remember { mutableIntStateOf(0) } // 点击递增 → 重跑循环

    // 加载中拦截返回键（防止用户在认证完成前误退出，导致"首次加载不出"）
    BackHandler(enabled = isLoading) {
        // 加载中按返回时：直接允许退出，不再拦截
        onDismiss()
    }

    // 最大亮度 + 屏幕常亮
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

    // 认证 + 定时刷新（refreshTrigger 变化时重跑整个循环）
    LaunchedEffect(refreshTrigger) {
        isLoading = true
        errorMessage = null
        loadingStage = "正在连接校园认证…"

        // 带超时的认证
        val authOk = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { api.authenticate() }
            true
        }
        if (authOk == null) {
            errorMessage = "认证超时，请检查网络"
            isLoading = false
            return@LaunchedEffect
        }

        loadingStage = "认证成功，获取付款码…"

        // 循环刷新
        while (isActive) {
            try {
                val code = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { api.getBarCode() }
                } ?: throw RuntimeException("获取超时")
                barCodeNumber = code
                qrBitmap = generateQrCode(code)
                barBitmap = generateBarcode(code)
                isLoading = false
                errorMessage = null
            } catch (e: Exception) {
                if (!isActive) return@LaunchedEffect
                errorMessage = "刷新失败: ${e.message}"
                isLoading = false
            }

            // 倒计时
            countdown = REFRESH_SECONDS
            repeat(REFRESH_SECONDS) {
                if (!isActive) return@LaunchedEffect
                delay(1000)
                countdown--
            }
        }
    }

    // ── UI：白色卡片弹窗 ──
    Card(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .wrapContentHeight(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        when {
            isLoading -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(loadingStage, fontSize = 13.sp, color = Color.Gray)
                }
            }

            errorMessage != null -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { refreshTrigger++ }) {
                        Text("点击重试")
                    }
                }
            }

            else -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { refreshTrigger++ }  // 点击任意位置刷新
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "付款码",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color(0xFF333333)
                    )

                    Text(
                        "向商家出示此码 · 点击刷新",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    // 二维码
                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "付款二维码",
                            modifier = Modifier.size(200.dp),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 条形码
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

                    // 号码（低调灰色）
                    Text(
                        barCodeNumber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        letterSpacing = 1.5.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    // 低调倒计时（仅文字，无进度条）
                    Text(
                        "${countdown}s",
                        fontSize = 11.sp,
                        color = Color(0xFFCCCCCC)
                    )
                }
            }
        }
    }
}
