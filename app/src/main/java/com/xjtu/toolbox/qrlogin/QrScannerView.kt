package com.xjtu.toolbox.qrlogin

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 相机取景 + 实时二维码解码的 Compose 组件。
 *
 * CameraX 拿 YUV 帧（`ImageAnalysis`），[QrFrameDecoder] 离线解码（zxing，定位块破损时会补一个再解）。
 * 解出第一个结果后即锁定并回调一次 [onResult]，不再重复触发——由 [decoded] 保证。
 */
@Composable
fun QrScannerView(
    modifier: Modifier = Modifier,
    onResult: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val decoded = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    // 补定位块比常规解码贵（几十毫秒），每 300ms 最多试一次；常规解码每帧都跑
    val lastRepairAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef.get()?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // SurfaceView 会打穿 Compose 层级（取景跑到底栏/标题下面）。TextureView 跟普通 View 同层。
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                if (!previewView.isAttachedToWindow) return@addListener
                val provider = providerFuture.get()
                cameraProviderRef.set(provider)
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // 不设分辨率的话 CameraX 默认给 640x480。一千多字的分享码
                    // 是 27 版（125x125 模块），在 480px 取景里每格不到 2.5px，
                    // 怎么对都扫不出来。要到 1080p 后约 5px/模块，才稳。
                    // CLOSEST_HIGHER_THEN_LOWER：没有 1080p 的机器先往上找，实在没有再往下。
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    if (decoded.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val repair = now - lastRepairAt.get() >= REPAIR_INTERVAL_MS
                    if (repair) lastRepairAt.set(now)
                    val text = decodeQr(proxy, repair)
                    proxy.close()
                    if (text != null && decoded.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(ctx).execute { onResult(text) }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * 解 [proxy] 的 Y 平面。按 rowStride 读：1080p 下很多机型每行末尾有填充字节，
 * 以前按 width 当行宽读，整幅图逐行错位，能扫出来全凭运气。
 */
private fun decodeQr(proxy: ImageProxy, repair: Boolean): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return runCatching {
        QrFrameDecoder.decode(data, plane.rowStride, proxy.width, proxy.height, repair)
    }.getOrNull()
}

private const val REPAIR_INTERVAL_MS = 300L
