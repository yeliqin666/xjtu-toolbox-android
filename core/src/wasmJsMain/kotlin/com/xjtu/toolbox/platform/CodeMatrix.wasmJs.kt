package com.xjtu.toolbox.platform

/** Web（wasmJs）侧的码阵实现：转调 `:core` commonMain 里那份纯 Kotlin 编码器。 */
actual fun qrCodeModules(text: String): CodeMatrix? = QrCodec.encode(text)

actual fun code128Modules(text: String): CodeMatrix? = Code128Codec.encode(text)
