package com.xjtu.toolbox.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 截图缩小的倍数：缩到这么小再拉回原尺寸，本身就糊了一层，再补一遍盒式模糊去掉格子感。 */
private const val DOWNSCALE = 8

/** 小图上盒式模糊的半径（像素）与遍数：三遍近似高斯。 */
private const val BOX_RADIUS = 2
private const val BOX_PASSES = 3

@Composable
actual fun rememberWindowSnapshot(capture: Boolean): WindowSnapshot? {
    val view = LocalView.current
    var shot by remember { mutableStateOf<WindowSnapshot?>(null) }
    LaunchedEffect(capture) {
        if (!capture) {
            shot = null
            return@LaunchedEffect
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@LaunchedEffect
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            PixelCopy.request(window, full, { result ->
                if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        }
        if (!ok) {
            full.recycle()
            return@LaunchedEffect
        }
        val small = withContext(Dispatchers.Default) {
            val scaled = Bitmap.createScaledBitmap(full, (w / DOWNSCALE).coerceAtLeast(1), (h / DOWNSCALE).coerceAtLeast(1), true)
            full.recycle()
            val s = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
            boxBlur(s)
            s
        }
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        shot = WindowSnapshot(small.asImageBitmap(), IntSize(w, h), Offset(loc[0].toFloat(), loc[1].toFloat()))
    }
    return shot
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 小图上原地做几遍横竖盒式模糊（小图只有几万像素，几毫秒）。 */
private fun boxBlur(bitmap: Bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(BOX_PASSES) {
        blurLine(px, tmp, w, h, horizontal = true)
        blurLine(tmp, px, w, h, horizontal = false)
    }
    bitmap.setPixels(px, 0, w, 0, 0, w, h)
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val r = BOX_RADIUS
    val span = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) {
            val p = at(i)
            a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until len) {
            val out = ((a / span) shl 24) or ((rr / span) shl 16) or ((g / span) shl 8) or (b / span)
            if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            val add = at(i + r + 1)
            val sub = at(i - r)
            a += (add ushr 24) - (sub ushr 24)
            rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            b += (add and 0xFF) - (sub and 0xFF)
        }
    }
}
