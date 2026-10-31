package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@Composable
actual fun rememberHeroImagePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember {
        {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "webp", "bmp", "gif")
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { chooser.selectedFile.readBytes() }.getOrNull()
                    }
                    if (bytes != null) latest.value(bytes)
                }
            }
        }
    }
}

actual fun encodeHeroImage(bitmap: ImageBitmap): ByteArray? = runCatching {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.WEBP, 86)?.bytes
}.getOrNull()
