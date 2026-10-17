package com.xjtu.toolbox.platform

/** Web（wasmJs）侧：转调 `:core` commonMain 里那份纯 Kotlin 实现（[ZlibCodec]）。 */
actual fun deflateBytes(raw: ByteArray): ByteArray? = ZlibCodec.deflate(raw)

actual fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray? = ZlibCodec.inflate(bytes, maxBytes)

actual val deflateAvailable: Boolean = true
