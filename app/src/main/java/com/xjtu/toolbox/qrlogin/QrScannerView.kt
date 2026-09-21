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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 相机取景 + 实时二维码解码的 Compose 组件。
 *
 * CameraX 拿 YUV 帧（`ImageAnalysis`），zxing（本仓库已依赖 `zxing.core`）离线解码。
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
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    // 分享码那种上千字的码是 25 版以上，模块密、
                    // 容错又只有 L 级，不开 TRY_HARDER 基本扫不出来。
                    // 代价是每帧慢几十毫秒，但 KEEP_ONLY_LATEST 会丢帧，
                    // 取景不会卡，只是分析帧率低一些。
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }

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
                    val text = decodeQr(proxy, reader)
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

/** 解 [proxy] 的 Y 平面为一帧灰度图并交给 zxing；未识别到返回 null。 */
private fun decodeQr(proxy: ImageProxy, reader: MultiFormatReader): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val w = proxy.width
    val h = proxy.height
    val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
