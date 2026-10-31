package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** 选一张本地图片，回调原始字节（在主线程调用）。返回触发选择器的动作。 */
@Composable
expect fun rememberHeroImagePicker(onPicked: (ByteArray) -> Unit): () -> Unit

/** 把裁好的位图压成 WebP 字节；失败返回 null。 */
expect fun encodeHeroImage(bitmap: ImageBitmap): ByteArray?
