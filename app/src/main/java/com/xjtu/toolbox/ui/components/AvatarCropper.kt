package com.xjtu.toolbox.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.platform.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.max

@Composable
fun AvatarCropDialog(uri: Uri, onCancel: () -> Unit, onConfirm: (Bitmap) -> Unit) {
    val context = LocalContext.current
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        source = withContext(Dispatchers.IO) { decodeForCrop(context, uri) }
        failed = source == null
    }
    BackHandler { onCancel() }
    WindowDialog(show = true, title = "调整头像", onDismissRequest = onCancel) {
        val bitmap = source
        when {
            failed -> Text("这张图片读不出来，换一张试试")
            bitmap == null -> CircularProgressIndicator()
            else -> ImageCropContent(
                image = remember(bitmap) { bitmap.asImageBitmap() },
                busy = false, aspect = 1f, circular = true, maxOutput = 512,
                onCancel = onCancel,
                onCrop = { onConfirm(it.asAndroidBitmap()) },
            )
        }
    }
}

private const val TAG = "AvatarCropper"
private const val DECODE_MAX_PX = 2048

/** 解码待裁剪的图片：EXIF 摆正 + 降采样。失败返回 null（已记日志）。 */
private fun decodeForCrop(context: Context, uri: Uri): Bitmap? = runCatching {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        // 硬件 Bitmap 读不到像素，createBitmap / compress 都会失败。
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        val longest = max(info.size.width, info.size.height)
        if (longest > DECODE_MAX_PX) {
            val scale = DECODE_MAX_PX.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}.onFailure { Log.w(TAG, "decode failed: $uri", it) }.getOrNull()
