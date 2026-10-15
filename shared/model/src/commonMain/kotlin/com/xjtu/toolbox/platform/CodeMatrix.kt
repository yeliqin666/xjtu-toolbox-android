package com.xjtu.toolbox.platform

/** 一个只有黑白两态的格子图：二维码的模块阵列，或条形码的条纹阵列（高度 1）。 */
class CodeMatrix(val width: Int, val height: Int, private val bits: BooleanArray) {
    init {
        require(bits.size == width * height) {
            "格子数与尺寸对不上：${width}x$height 应有 ${width * height} 个，实际 ${bits.size} 个"
        }
    }

    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]
}
/** 平台能力：把一段文本编成二维码的模块阵列（不含静默区，渲染方自己按需留白）。 */
expect fun qrCodeModules(text: String): CodeMatrix?

/** 平台能力：把一段文本编成 Code128 条形码的条纹阵列（宽度 = 模块数，高度 1，渲染方拉伸）。 */
expect fun code128Modules(text: String): CodeMatrix?
