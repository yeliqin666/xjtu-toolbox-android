package com.xjtu.toolbox.social

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.platform.BackHandler
import com.xjtu.toolbox.qrlogin.QrScannerView
import com.xjtu.toolbox.util.QrBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 匹配交友在 Android 上的装配：给 `:core` 的 [MatchScreen] 交三件端上的东西。 */
@Composable
fun AppMatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val host = remember(context) { AppMatchHost(context) }

    // 「扫一扫」：取景层在等的那个 Deferred（null = 现在没有取景层）。
    var scanning by remember { mutableStateOf<CompletableDeferred<MatchScanOutcome>?>(null) }
    // 「从相册选图」：系统图库还在前台时挂在这上面（null = 没有正在等的选图）。
    var picking by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> picking?.complete(uri) }

    val scanner = remember(context) {
        object : MatchScanner {
            override suspend fun scan(): MatchScanOutcome {
                val waiter = CompletableDeferred<MatchScanOutcome>()
                scanning = waiter
                return try {
                    waiter.await()
                } finally {
                    // 覆盖层自己关掉时也会走到这（取消、或屏被切走）——把引用清干净。
                    scanning = null
                }
            }

            override suspend fun fromImage(): MatchScanOutcome {
                val waiter = CompletableDeferred<Uri?>()
                picking = waiter
                val uri = try {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                    waiter.await()
                } finally {
                    picking = null
                }
                if (uri == null) return MatchScanOutcome()
                val scanned = withContext(Dispatchers.IO) {
                    runCatching { readCodeFromImage(context, uri) }.getOrNull()
                }
                return if (scanned == null) {
                    MatchScanOutcome(reason = "图里没找到二维码。换一张完整点、清楚点的截图试试。")
                } else {
                    MatchScanOutcome(code = scanned)
                }
            }
        }
    }

    MatchScreen(host = host, onBack = onBack, scanner = scanner)

    scanning?.let { waiter ->
        ScannerOverlay(
            onResult = { code -> waiter.complete(MatchScanOutcome(code = code)) },
            onClose = { waiter.complete(MatchScanOutcome()) },
        )
    }
}

/** 从一张相册截图里读二维码。 */
private fun readCodeFromImage(context: Context, uri: Uri): String? {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val edge = maxOf(info.size.width, info.size.height)
        if (edge > 1600) {
            val ratio = 1600f / edge
            decoder.setTargetSize(
                (info.size.width * ratio).toInt().coerceAtLeast(1),
                (info.size.height * ratio).toInt().coerceAtLeast(1),
            )
        }
    }
    return QrBitmap.read(bitmap).also { bitmap.recycle() }
}

/** 全屏取景。相机权限就地要，不必为一次扫码把用户丢去系统设置再走回来。 */
@Composable
private fun ScannerOverlay(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            QrScannerView(Modifier.fillMaxSize(), onResult = onResult)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (granted) "对准对方屏幕上的二维码" else "得先允许用一下相机",
                style = MiuixTheme.textStyles.body2,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            if (!granted) {
                TextButton(
                    text = "允许使用相机",
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(Modifier.height(8.dp))
            }
            TextButton(text = "取消", onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}
