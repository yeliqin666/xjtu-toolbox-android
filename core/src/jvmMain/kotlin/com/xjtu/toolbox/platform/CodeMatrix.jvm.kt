package com.xjtu.toolbox.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * jvm（桌面 / serve 模式）侧的码阵实现：**同一份 zxing**。
 *
 * zxing 的 `core` 是一份纯 Java jar（没有平台绑定），所以桌面这一端与 Android 那一支
 * 逐模块同源：参数、纠错等级、静默区都相同（见 androidMain 那份 actual 的对照表）。
 * 这也是「付款码在桌面真出码」的编码侧 —— 渲染在 `:core` 的 `CodeMatrixView`。
 */
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
