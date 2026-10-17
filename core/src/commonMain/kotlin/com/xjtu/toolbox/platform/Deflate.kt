package com.xjtu.toolbox.platform

/** 平台能力：zlib 格式的压缩 / 解压（分享码本体的那一步）。 */

/** 压一段字节（zlib 流，与 `java.util.zip.Deflater(BEST_COMPRESSION)` 同一种封装）。 */
expect fun deflateBytes(raw: ByteArray): ByteArray?

/** 解压一段 zlib 流。 */
expect fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray?

/** 这一端有没有上面那两件。`false` 的端上 [deflateBytes] / [inflateBytes] 永远给 null。 */
expect val deflateAvailable: Boolean
