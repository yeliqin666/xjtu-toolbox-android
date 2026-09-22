package com.xjtu.toolbox.calendar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xjtu.toolbox.lms.LmsDownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 校历图片加载状态：原图字节（下载用）+ 按屏宽采样后的位图（显示用）。 */
class LoadedCalendarImage(val image: SchoolCalendarImage, val bytes: ByteArray, val bitmap: Bitmap)

/**
 * 解码成显示用位图。原图约 4600×2000，全尺寸解码要 30 多 MB，
 * 这里压到最长边不超过 [maxSide]（放大到 3 倍仍然清楚），下载保存的仍是原图。
 */
fun decodeCalendarBitmap(bytes: ByteArray, maxSide: Int = 2400): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** 校历页里的「教务处校历原图」卡片，点击全屏查看。 */
@Composable
fun SchoolCalendarImageCard(
    loaded: LoadedCalendarImage,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        cornerRadius = 16.dp,
    ) {
        Column(Modifier.clickable(onClick = onOpen).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(loaded.image.label, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                    Text(
                        "教务处发布 · 点击放大、保存",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Icon(Icons.Default.ZoomIn, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(10.dp))
            val bitmap = remember(loaded) { loaded.bitmap.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = loaded.image.label,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(loaded.bitmap.width.toFloat() / loaded.bitmap.height)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

/** 全屏查看：双指缩放 / 拖动，双击在 1 倍和 2.5 倍之间切换；右上角保存原图到下载管理。 */
@Composable
fun SchoolCalendarImageViewer(loaded: LoadedCalendarImage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    val bitmap = remember(loaded) { loaded.bitmap.asImageBitmap() }

    // 平移限制在放大后多出来的那部分内，别把图拖出屏幕
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { boxSize = it }
                .pointerInput(loaded) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    })
                }
                .pointerInput(loaded) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = clamp(offset + pan, scale)
                    }
                }
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = loaded.image.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    loaded.image.label,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                LmsDownloadStore.saveBytes(
                                    context,
                                    loaded.image.fileName,
                                    loaded.image.mimeType,
                                    loaded.bytes,
                                    LmsDownloadStore.CATEGORY_OTHER,
                                )
                            }
                            saving = false
                            Toast.makeText(
                                context,
                                if (uri != null) "已保存到下载管理" else "保存失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Icon(Icons.Default.Download, contentDescription = "保存原图", tint = Color.White)
                }
            }
        }
    }
}
