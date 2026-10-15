package com.xjtu.toolbox.platform

/**
 * 一个**只有黑白两态**的格子图：二维码的模块阵列，或条形码的条纹阵列（高度 1）。
 *
 * ## 为什么是「格子」而不是「位图」
 *
 * 付款码原先在 `:app` 里是 `android.graphics.Bitmap`（zxing 的 `BitMatrix` + `Bitmap.setPixels`），
 * 那三样（`Bitmap` / `Bitmap.createBitmap` / `asImageBitmap`）只有 Android 有；桌面要画同一张码
 * 就得再写一份 skiko 的 `Image.makeRaster` + 手工摊 RGBA 字节 —— 两端各一份像素搬运，且都不可测。
 *
 * 换成格子阵列之后：
 *  - **渲染**只需要 Compose 的 `Canvas`（三端同一份，见 [com.xjtu.toolbox.pay.PaymentCodeScreen] 里
 *    那个 `CodeMatrixView`），不需要任何平台的像素缓冲 API；
 *  - **可验证**：格子阵列是纯数据，「这一串数字真的编成了一张能读回来的码」可以在单测里拿
 *    解码器钉住（`Decoder().decode(bitMatrix)`），而位图要在测试里先解像素才能验。
 *
 * [width] × [height] 个格子，`bits` 行优先；`true` = 黑（渲染方把它画成前景色）。
 */
class CodeMatrix(val width: Int, val height: Int, private val bits: BooleanArray) {
    init {
        require(bits.size == width * height) {
            "格子数与尺寸对不上：${width}x$height 应有 ${width * height} 个，实际 ${bits.size} 个"
        }
    }

    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]
}
/**
 * 平台能力：**把一段文本编成二维码的模块阵列**（不含静默区，渲染方自己按需留白）。
 *
 * 编码器在两端各有一份实现，而**编出来的矩阵逐模块相同**（`:core:jvmTest` 的
 * `CodeMatrixParityJvmTest` 拿同一批输入逐格断言）：
 *  - Android / jvm：zxing（`QRCodeWriter`，一份纯 Java jar —— 与 `:data` 里 jsoup 的情况同型，
 *    进不了 `:core` 的 commonMain），参数与搬迁前 `:app` 逐字相同（`CHARACTER_SET=UTF-8`、
 *    纠错 `L`、静默区留 0 个模块由渲染方补 —— 搬迁前那一份是 `QrBitmap` 的 `MARGIN=1`，
 *    渲染出来的白边宽度一致，见 `PaymentCodeScreen` 的 `CodeMatrixView`）；
 *  - wasmJs：**没有 zxing**（它是 JVM/Android 的 jar），所以 2026-10-15（D12 ①）把编码器按
 *    「三端一份」搬进了 commonMain —— 纯 Kotlin 的 [QrCodec]。那一份不是「web 侧另写一份」：
 *    `:web` 里一行编码逻辑都没有，wasmJs 的 actual 只是转调它。
 *
 * 装不下（文本太长、超出该纠错等级的最大版本）返回 null，不抛给调用方。
 */
expect fun qrCodeModules(text: String): CodeMatrix?

/**
 * 平台能力：**把一段文本编成 Code128 条形码的条纹阵列**（宽度 = 模块数，高度 1，渲染方拉伸）。
 *
 * 与 [qrCodeModules] 同一条缝、同一份 zxing。宽度按「一个模块一格」给（搬迁前是让
 * `Code128Writer` 直接渲染成 800×200 的位图，那 800 像素里含它默认留的 10 个模块静默区，
 * 比例与这里的 `模块数 + 20` 等价），渲染方按可用宽度拉伸 —— 与搬迁前那条条形码
 * `ContentScale.FillBounds` 的观感一致。
 */
expect fun code128Modules(text: String): CodeMatrix?
