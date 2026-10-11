package com.xjtu.toolbox.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Android 侧的码阵实现：zxing（与搬迁前 `:app` 的 `QrBitmap.generate` +
 * `PaymentCodeScreen.generateBarcode` 同一份参数）。
 *
 * | | 搬迁前（`:app`） | 这里 |
 * |---|---|---|
 * | 二维码 | `QrBitmap.generate(text, 600)`：`CHARACTER_SET=UTF-8`、纠错 `L`、`MARGIN=1`、600×600 位图 | 同一个 `QRCodeWriter`，同一个字符集与纠错等级；`MARGIN=0`（静默区由渲染方补，白边宽度一样）+ 请求尺寸 0（即「一个模块一格」） |
 * | 条形码 | `Code128Writer().encode(text, CODE_128, 800, 200)` | 同一个 `Code128Writer`，同样不带 hints（默认静默区 10 模块照留）；高度给 1（渲染方拉伸，搬迁前那条是 `FillBounds`） |
 *
 * 于是**编出来的码逐模块相同**，差别只在「谁来画」：搬迁前是 `Bitmap.setPixels`，现在是
 * `:core` 那个三端共用的 `Canvas`（见 [CodeMatrix] 的 KDoc）。
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
