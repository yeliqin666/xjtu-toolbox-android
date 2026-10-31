package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import kotlin.coroutines.resume

@Composable
actual fun rememberHeroImagePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember {
        {
            scope.launch {
                val bytes = pickImageBytes()
                if (bytes != null) latest.value(bytes)
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun pickImageBytes(): ByteArray? = suspendCancellableCoroutine { cont ->
    val input = document.createElement("input") as? HTMLInputElement
    if (input == null) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    input.type = "file"
    input.accept = "image/*"
    input.style.display = "none"
    document.body?.appendChild(input)
    input.onchange = {
        val file = input.files?.item(0)
        if (file == null) {
            input.remove()
            cont.resume(null)
        } else {
            val reader = FileReader()
            reader.onload = {
                input.remove()
                val dataUrl = reader.result as? String
                val base64 = dataUrl?.substringAfter(",", "")
                val bytes = if (base64.isNullOrEmpty()) null else runCatching { Base64.Default.decode(base64) }.getOrNull()
                cont.resume(bytes)
            }
            reader.onerror = {
                input.remove()
                cont.resume(null)
            }
            reader.readAsDataURL(file)
        }
    }
    input.click()
}

actual fun encodeHeroImage(bitmap: ImageBitmap): ByteArray? = runCatching {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.WEBP, 86)?.bytes
}.getOrNull()
