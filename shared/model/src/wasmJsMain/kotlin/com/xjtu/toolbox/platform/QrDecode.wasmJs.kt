package com.xjtu.toolbox.platform

import com.xjtu.toolbox.platform.qr.QrReader

/** Web（wasmJs）侧的二维码解码：转调 `:core` commonMain 里那份纯 Kotlin 解码链。 */
actual fun qrDecodeArgb(pixels: IntArray, width: Int, height: Int): String? =
    QrReader.decodeArgb(pixels, width, height)
