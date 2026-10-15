package com.xjtu.toolbox.platform.qr

/** 一帧 ARGB 像素 → 二维码文本。 */
internal object QrReader {

    /** 解不出来（图里没有码、码坏了、或字符集本实现没有）一律返回 null，不抛。 */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null
        return try {
            val luma = ByteArray(width * height)
            for (i in luma.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            // 2. 二值化（局部阈值那一版）
            val source = QrPlanarYuvLuminanceSource(luma, width, width, 0, 0, width, height)
            val matrix = QrHybridBinarizer(source).blackMatrix()
            // 3~5. 定位 + 校正图案 + 透视 + 网格采样
            val bits = QrDetector(matrix).detect(tryHarder = true).bits
            // 6~8. 读码字 + 纠错 + 拼文本
            QrDecoder().decode(bits)
        } catch (_: Exception) {
            null
        }
    }
}
