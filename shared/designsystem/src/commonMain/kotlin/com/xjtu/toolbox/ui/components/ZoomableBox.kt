package com.xjtu.toolbox.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 全屏看图用的缩放层：双指缩放 / 拖动，双击在 1 倍和 2.5 倍之间切换，平移不超出放大后多出来的部分。
 * [content] 拿到的 Modifier 已经带上缩放和平移，挂到图片上即可。校历原图、社区帖子图片共用。
 *
 * @param key 换了图就把缩放复位
 * @param onTap 单击（没放大时常用来关闭）
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    key: Any? = Unit,
    onTap: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(key) {
                detectTapGestures(
                    onTap = { if (scale <= 1f) onTap?.invoke() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f },
                )
            }
            .pointerInput(key) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = clamp(offset + pan, scale)
                }
            }
    ) {
        content(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
    }
}
