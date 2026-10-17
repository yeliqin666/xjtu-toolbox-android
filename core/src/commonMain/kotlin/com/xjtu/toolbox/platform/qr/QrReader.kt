package com.xjtu.toolbox.platform.qr

/**
 * 一帧 ARGB 像素 → 二维码文本。**这一端（wasmJs）的解码链**，纯 Kotlin，没有 zxing。
 *
 * ## 这是 D12 ③ 里那条「浏览器没有二维码解码器」的补法
 *
 * `:app` 的匹配交友屏有两档「怎么拿到对方的码」：相机取景（`QrScannerView`）与从相册选图
 * （`ImageDecoder` + `QrBitmap.read`）。两档最后都落到**同一个解码器**上 —— `:app` 的
 * `com.xjtu.toolbox.qrlogin.QrFrameDecoder`，而它是 zxing（`HybridBinarizer` + `QRCodeReader`）
 * 的那一份。zxing 是 JVM/Android 的 jar，进不了 wasmJs，于是「选一张图识别」在浏览器里本来做不了。
 *
 * 这里按与 D12 ① 编码器（[com.xjtu.toolbox.platform.QrCodec]）**同一套做法**，把 zxing 的**解码链**
 * 逐条搬进 `:core` 的 commonMain。**这不是「另写一份解码器」**：底下那一族 `Qr*.kt` 每一个文件头上
 * 都有一张「zxing 的那个类 ⇄ 这里的这个类」对照表，逐行是同一份算法。
 *
 * ## 整条链（顺序就是调用顺序）
 *
 * | 步 | 谁 | zxing 那边 |
 * |---|---|---|
 * | 1. ARGB → 灰度 | [QrReader.decodeArgb] 里那一段 | `:app` 的 `QrFrameDecoder.decodeArgb`（`(r*299 + g*587 + b*114) / 1000`）；**不是** `RGBLuminanceSource` 的 `(r+2g+b)/4` |
 * | 2. 灰度 → 黑/白位矩阵 | [QrHybridBinarizer] | `HybridBinarizer`（局部阈值，8×8 块 + 5×5 邻块平均） |
 * | 3. 找三个定位图案 | [QrFinderPatternFinder] | `FinderPatternFinder`（1:1:3:1:1 游程 + 三个方向的交叉验证） |
 * | 4. 找校正图案、算维度 | [QrDetector] | `Detector` |
 * | 5. 透视变换 + 网格采样 | [QrPerspectiveTransform] / [QrGridSampler] | `PerspectiveTransform` / `DefaultGridSampler` |
 * | 6. 读格式/版本/码字 | [QrBitMatrixParser] | `BitMatrixParser` |
 * | 7. 拆块 + Reed-Solomon 纠错 | [QrDataBlock] / [QrReedSolomonDecoder] | `DataBlock` / `ReedSolomonDecoder` |
 * | 8. 码字 → 文本 | [QrDecodedBitStreamParser] | `DecodedBitStreamParser` |
 *
 * ## 三处如实（第二处本批已补齐）
 *
 * 1. **「补定位块」那一档没有**：`:app` 的 `QrFrameDecoder` 在常规解码失败后还有一手
 *    `decodeWithRepairedFinder`（两个定位块推第三个、画上去再解一次，为图书馆那种磨掉一角的桌贴）。
 *    那一手要 `FinderCandidates` 那一整套候选配对与重画逻辑（`:app` 自己的一份），本批没搬 ⇒
 *    **磨掉一角的码在这一端可能扫不出来**（Android 能）。常规码不受影响。
 * 2. **字符集有码表了**（这一处本来是本端最大的缺口，已补齐）：中日韩那一族的码表按「三端一份」搬进了
 *    commonMain（[QrCjkTables] 是 `tools/gen-qr-charset-tables.java` 从本机 JDK 枚举出来的纯数据），
 *    BYTE 段猜成 Shift_JIS、或码里写的是 KANJI / HANZI 段，现在都解得出（见 [QrCharset] 的边界表：
 *    剩下解不出来的只有 ECI 12 / 16 这两档 —— zxing 自己也不认）。分享码那一路是 base64url（纯 ASCII），
 *    至始至终不受影响。
 * 3. **`hints` 一个都不收**：`PURE_BARCODE` / `CHARACTER_SET` / `NEED_RESULT_POINT_CALLBACK` 那几张表
 *    的消费方在这一端都不存在（本端只解一帧、不画取景点、不指定字符集），[QrDetector] 只留了
 *    `TRY_HARDER` 一个布尔 —— 那正是 `:app` 传的那一个。
 *
 * ## 与 Android 的关系（判据不是嘴说）
 *
 * `:core:jvmTest` 的 `QrDecodeParityJvmTest` 拿**同一批 ARGB 像素**同时跑这一份与 jvm 那一端的
 * actual（= zxing 那条同样的链）并断言**解出来的文本逐字相同**（含「图里没有码」时两边都返回 null），
 * 输入既有 zxing 编的码、也有 pure Kotlin 的 `QrCodec` 编的码。
 */
internal object QrReader {

    /** 解不出来（图里没有码、码坏了、或字符集本实现没有）一律返回 null，不抛。 */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null
        return try {
            // 1. ARGB → 灰度 —— 与 `:app` 的 `QrFrameDecoder.decodeArgb` 逐字相同
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
