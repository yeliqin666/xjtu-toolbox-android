package com.xjtu.toolbox.platform

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** jvm 侧：`java.util.zip`。 */
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
