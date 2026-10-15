package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧：**转调 `:core` commonMain 里那份纯 Kotlin 实现**（[ZlibCodec]）。
 *
 * 原先这一端如实返回 null（浏览器只有流式异步的 `CompressionStream`，而分享码这条链在共享层是同步的）。
 * 2026-10-15（D12 ②）改成「纯 Kotlin 一份、三端共享」：压缩是固定 Huffman + 贪心 LZ77 的 **zlib 流**
 * （头 + deflate + Adler-32，与 `java.util.zip.Deflater(BEST_COMPRESSION)` 同一种封装），
 * 解压三种块（存储 / 固定 / 动态 Huffman）都认 —— 所以 Android/jvm 编出来的分享码在这一端解得开，
 * 反过来也一样（两个方向都钉在 `:core:jvmTest` 的 `ShareCodeInteropJvmTest` 里）。
 *
 * ⚠️ 与 Android/jvm 那两份 actual 的差别只有一处，且是**允许**的那一处：同一段明文压出来的**字节数
 * 不同**（这边不做动态 Huffman）。分享码的语义是「对方解得开」，不是「字节相同」，见 [ZlibCodec] 文件头。
 */
actual fun deflateBytes(raw: ByteArray): ByteArray? = ZlibCodec.deflate(raw)

actual fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray? = ZlibCodec.inflate(bytes, maxBytes)

actual val deflateAvailable: Boolean = true
