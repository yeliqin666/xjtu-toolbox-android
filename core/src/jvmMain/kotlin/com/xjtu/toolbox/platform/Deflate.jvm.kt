package com.xjtu.toolbox.platform

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * jvm（桌面 / serve 模式）侧：**同一份 `java.util.zip`**，与 androidMain 那份逐字相同
 * （参数、分块、上限判据都一致）—— 所以桌面算出来的分享码与 Android 逐字节相同，
 * 「同一条码在两端解得开」是可核的（见 `:desktop` 与 `:app` 的夹具用例）。
 */
actual fun deflateBytes(raw: ByteArray): ByteArray? = runCatching {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(raw)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    try {
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    } finally {
        deflater.end()
    }
    out.toByteArray()
}.getOrNull()

actual fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray? = runCatching {
    val inflater = Inflater()
    inflater.setInput(bytes)
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    try {
        while (!inflater.finished() && out.size() <= maxBytes) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
    } finally {
        inflater.end()
    }
    out.toByteArray()
}.getOrNull()

actual val deflateAvailable: Boolean = true
