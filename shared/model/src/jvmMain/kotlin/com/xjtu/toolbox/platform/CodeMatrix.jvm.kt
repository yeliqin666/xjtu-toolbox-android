package com.xjtu.toolbox.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** jvm（桌面 / serve 模式）侧的码阵实现：同一份 zxing。 */
actual fun qrCodeModules(text: String): CodeMatrix? = runCatching {
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
        ),
    ).toCodeMatrix()
}.getOrNull()

actual fun code128Modules(text: String): CodeMatrix? = runCatching {
    Code128Writer().encode(text, BarcodeFormat.CODE_128, 0, 1).toCodeMatrix()
}.getOrNull()

/** zxing 的位矩阵 → 盒子阵列（行优先）。 */
internal fun BitMatrix.toCodeMatrix(): CodeMatrix {
    val bits = BooleanArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            bits[offset + x] = get(x, y)
        }
    }
    return CodeMatrix(width, height, bits)
}
