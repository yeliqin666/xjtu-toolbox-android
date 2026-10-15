package com.xjtu.toolbox.platform

import com.xjtu.toolbox.platform.qr.QrGenericGf
import com.xjtu.toolbox.platform.qr.QrReader
import com.xjtu.toolbox.platform.qr.QrReedSolomonDecoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「浏览器那一端的二维码**解码**（`:core` commonMain 里那份纯 Kotlin 链）与 Android/jvm 那一端
 * （zxing）**从同一批像素解出同一段文本**」这件事的机器可验版本。
 *
 * ## 为什么要有它
 *
 * wasmJs 上没有 zxing（JVM/Android 的 jar）⇒ 浏览器原先**没有二维码解码器**：匹配交友那一屏的
 * 「从相册选一张图」与相机取景两档在 `:web` 上都做不了（D12 ③ 的结论）。本批把解码链按「三端一份」
 * 搬进 commonMain（`platform/qr/`），**androidMain / jvmMain 两份 actual 一行未改**（红线：Android
 * 行为逐字不变 —— `:app` 还走它自己那份 `qrlogin/QrFrameDecoder`）。于是「浏览器认得出 Android 认得
 * 出的那张码」不能靠嘴说：这里拿**同一批 ARGB 像素**同时跑两份实现，逐字断言解出来的是同一段文本。
 *
 * ## 两侧分别是谁（别把这条测试读反了）
 *
 * | 一侧 | 是谁 | 用在哪 |
 * |---|---|---|
 * | [qrDecodeArgb] | jvmMain 的 actual = zxing（`PlanarYUVLuminanceSource` + `HybridBinarizer` + `QRCodeReader`，与 `:app` 的 `QrFrameDecoder` 同参数） | 桌面 / serve 模式 |
 * | [QrReader.decodeArgb] | 本批新增的纯 Kotlin 解码链（`platform/qr/`） | wasmJs（浏览器） |
 *
 * ## 摆图：为什么有两档密度，以及一个**实测**的坑
 *
 * 图的几何按屏幕/相机的真实形状给（每个模块 [CELL_PIXELS] 或 [COARSE_CELL_PIXELS] 像素 + 静默区），
 * 像素用 `0xFF000000` / `0xFFFFFFFF`（不透明黑/白，就是 `Bitmap.getPixels` 那批 int 的语义）——
 * 与 `CodeMatrixParityJvmTest.decode` 同一个理由（「一个模块一个像素 + 有白边」那种图任何相机与屏幕
 * 都不会产生，`FinderPatternFinder` 在它上面找不到定位图案）。
 *
 * ⚠️ **实测挖出来的一个坑**：**21 版（101 个模块）在每模块 4 像素时，zxing 自己也认不出来**（同一条
 * 载荷在每模块 2 像素时两端都解得对；其它版本如 13 版 69 模块、28 版 129 模块在两档密度下都解得对）。
 * 那是**参考实现的脾气**，不是这一份的差异（两端在任何一档密度下的结论都一致）⇒ 所以「必须解回原文」
 * 那一档按**每模块 2 像素**摆图（[COARSE_CELL_PIXELS]），另一档（每模块 4 像素）只断言**两端一致**。
 * 这不是放宽判据，是把「谁的脾气」分开：前者钉「解得出来且解的是原文」，后者钉「两端同结论」。
 *
 * ⚠️ 夹具全是编造的：数字串是编的 20 位（与 `testkit` 那一串同形状但**不是**它），分享码那一档按
 * base64url 字母表造（`Random(20261016)` ⇒ 每次一样）—— 没有真实课表 / 姓名 / 学号。
 */
class QrDecodeParityJvmTest {

    // ── 解出来的是同一段文本，而且就是原文 ──────────────────

    @Test
    fun `同一批像素：两端解出来的都是原文`() {
        for (text in payloads()) {
            // 出码这一侧也走两条路：zxing 编的（= App/桌面那份）与纯 Kotlin 编的（= 浏览器那份）
            for (matrix in listOf(
                assertNotNull(qrCodeModules(text), "jvm 这一端（zxing）应当能编出二维码：${text.take(24)}…"),
                assertNotNull(QrCodec.encode(text), "纯 Kotlin 那份应当也能编出二维码：${text.take(24)}…"),
            )) {
                for (cell in intArrayOf(COARSE_CELL_PIXELS, CELL_PIXELS)) {
                    val image = render(matrix, cell, quietZoneModules = 4)
                    val byZxing = qrDecodeArgb(image.pixels, image.width, image.height)
                    val byPure = QrReader.decodeArgb(image.pixels, image.width, image.height)

                    assertEquals(
                        byZxing,
                        byPure,
                        "两端解出来的不一样（${text.length} 字、每模块 $cell 像素）：「${text.take(24)}…」：" +
                            "zxing=${byZxing?.take(24)}，纯 Kotlin=${byPure?.take(24)}",
                    )
                    if (cell == COARSE_CELL_PIXELS) {
                        assertEquals(
                            text,
                            byPure,
                            "这一张码没解回原文（每模块 $cell 像素、解出来的是 ${byPure?.take(24)}…）：" +
                                "${text.take(24)}…（${text.length} 字）",
                        )
                    }
                }
            }
        }
    }

    // ── 图里没有码：两边都不能编一段文本出来 ────────────────

    @Test
    fun `图里没有码时两端都返回 null`() {
        // ① 一整片白（对比度太低：HybridBinarizer / 全局直方图那一步就该放弃）
        val plainWhite = IntArray(200 * 200) { WHITE_PIXEL }
        assertNull(qrDecodeArgb(plainWhite, 200, 200), "一片白里不该解出码（zxing）")
        assertNull(QrReader.decodeArgb(plainWhite, 200, 200), "一片白里不该解出码（纯 Kotlin）")

        // ② 一整片黑
        val plainBlack = IntArray(200 * 200) { BLACK_PIXEL }
        assertNull(qrDecodeArgb(plainBlack, 200, 200), "一片黑里不该解出码（zxing）")
        assertNull(QrReader.decodeArgb(plainBlack, 200, 200), "一片黑里不该解出码（纯 Kotlin）")

        // ③ 单像素级噪声（有对比度，但没有任何 1:1:3:1:1 的定位图案）
        val random = Random(20261016)
        val noise = IntArray(200 * 200) { if (random.nextBoolean()) BLACK_PIXEL else WHITE_PIXEL }
        assertEquals(
            qrDecodeArgb(noise, 200, 200),
            QrReader.decodeArgb(noise, 200, 200),
            "噪声图两端该给同一个结论（都不该认出码）",
        )

        // ④ 太小的一张图（进的是 HybridBinarizer 的「退回全局直方图」那一支）
        val tiny = IntArray(10 * 10) { BLACK_PIXEL }
        assertNull(qrDecodeArgb(tiny, 10, 10), "10x10 里不该解出码（zxing）")
        assertNull(QrReader.decodeArgb(tiny, 10, 10), "10x10 里不该解出码（纯 Kotlin）")

        // ⑤ 参数就不对
        assertNull(qrDecodeArgb(IntArray(0), 0, 0), "宽高为 0 该返回 null（zxing）")
        assertNull(QrReader.decodeArgb(IntArray(0), 0, 0), "宽高为 0 该返回 null（纯 Kotlin）")
        assertNull(qrDecodeArgb(IntArray(4), 100, 100), "像素不够 `width*height` 个该返回 null（zxing）")
        assertNull(QrReader.decodeArgb(IntArray(4), 100, 100), "像素不够 `width*height` 个该返回 null（纯 Kotlin）")
    }

    // ── 码被遮掉一块：纠错那一步的结论两端必须相同 ──────────

    @Test
    fun `码被遮掉一块时两端的结论相同`() {
        val text = "XJTU-PARITY-DAMAGE-0001-fake"
        val matrix = assertNotNull(qrCodeModules(text), "这一条夹具应当编得出码")

        for (cell in intArrayOf(COARSE_CELL_PIXELS, CELL_PIXELS)) {
            val base = render(matrix, cell, quietZoneModules = 4)

            // ① 轻伤：中心擦掉一个 1~2 个模块见方的小补丁 —— Reed-Solomon 应当能纠回来
            for (patchModules in intArrayOf(1, 2)) {
                val damaged = base.copy()
                patch(damaged, patchModules * cell)
                val byZxing = qrDecodeArgb(damaged.pixels, damaged.width, damaged.height)
                val byPure = QrReader.decodeArgb(damaged.pixels, damaged.width, damaged.height)
                assertEquals(byZxing, byPure, "擦掉 $patchModules 个模块见方之后两端的结论该相同（每模块 $cell 像素）")
                assertEquals(
                    text,
                    byPure,
                    "擦掉 $patchModules 个模块见方该被纠错兜住（每模块 $cell 像素）—— 兜不住说明这条夹具选错了",
                )
            }

            // ② 重伤：把右半边整片涂白 —— 纠错救不回来，两端都不该编出什么来（结论必须一致，且都为空）
            val heavy = base.copy()
            whitenColumns(heavy, fromX = heavy.width / 2, toX = heavy.width - 1)
            val byZxing = qrDecodeArgb(heavy.pixels, heavy.width, heavy.height)
            assertEquals(
                byZxing,
                QrReader.decodeArgb(heavy.pixels, heavy.width, heavy.height),
                "半张码被涂掉之后两端的结论该相同（每模块 $cell 像素）",
            )
            assertNull(byZxing, "半张码被涂掉之后不该解出东西来（每模块 $cell 像素）")
        }
    }

    // ── Reed-Solomon 那一层：同一条被弄错的码字，两端纠出同一个结果 ──

    @Test
    fun `Reed-Solomon 那一层与 zxing 逐个码字相同`() {
        val zxingField = com.google.zxing.common.reedsolomon.GenericGF.QR_CODE_FIELD_256
        val pureDecoder = QrReedSolomonDecoder(QrGenericGf.QR_CODE_FIELD_256)
        val random = Random(20261016)
        var recovered = 0

        repeat(40) {
            val dataLen = 1 + random.nextInt(60)
            val ecLen = 2 * (1 + random.nextInt(15))
            // 用 zxing 的编码器造一串**合法**码字（数据 + 纠错），再往里按错 —— 这样两端吃的是同一份坏数据
            val codewords = IntArray(dataLen + ecLen)
            for (i in 0 until dataLen) codewords[i] = random.nextInt(256)
            com.google.zxing.common.reedsolomon.ReedSolomonEncoder(zxingField).encode(codewords, ecLen)

            val maxErrors = ecLen / 2
            val errorCount = if (maxErrors == 0) 0 else random.nextInt(maxErrors + 1)
            val corrupted = codewords.copyOf()
            repeat(errorCount) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256) }

            val forZxing = corrupted.copyOf()
            val forPure = corrupted.copyOf()
            val byZxing = runCatching {
                com.google.zxing.common.reedsolomon.ReedSolomonDecoder(zxingField).decodeWithECCount(forZxing, ecLen)
            }.getOrNull()
            val byPure = runCatching { pureDecoder.decodeWithEcCount(forPure, ecLen) }.getOrNull()

            assertEquals(
                byZxing,
                byPure,
                "纠出错的个数不同（数据 $dataLen 字、纠错 $ecLen 字、弄错 $errorCount 处）：" +
                    "zxing=$byZxing，纯 Kotlin=$byPure",
            )
            assertTrue(
                forZxing.contentEquals(forPure),
                "纠完之后两端手上的码字不同（数据 $dataLen 字、纠错 $ecLen 字、弄错 $errorCount 处）",
            )
            if (byZxing != null) recovered++
        }
        assertTrue(recovered > 30, "这一条测试里绝大多数轮次都该纠回来（实测纠回 $recovered/40）—— 否则它在空跑")
    }

    // ── 夹具与工具 ──────────────────────────────────────────

    /**
     * 真会喂给这条缝的那几档：付款码形状的纯数字、字母数字模式、字节模式（含 ECI 26 的 UTF-8）、
     * 以及按长度铺开的（版本选择 / 纠错分块 / 交织 / 版本信息都随长度变，只测一个长度会漏掉
     * 多块交织与版本信息那两段）。
     */
    private fun payloads(): List<String> = listOf(
        // 付款码那一档的形状：20 位十进制（编造的，不是 testkit 里那一串）
        "12345678901234567890",
        "1",
        "12",
        "1234567",
        "0".repeat(77),
        // 字母数字模式（'-' '.' ':' '/' 空格都在那张 45 字表里）
        "HELLO WORLD 1234-\$%*+./:",
        // 字节模式：'_' 不在字母数字表里 ⇒ 整段走字节（`CHARACTER_SET=UTF-8` ⇒ 会带 ECI 26）
        "abc_def-GHI_123",
        "分享码 ABC-123_xyz·中文",
        // 分享码那一档的长度：base64url 字母表、上百到上千字（13 / 21 / 28 版，模块密、容错只有 L 级）
        fakeShareCode(120),
        fakeShareCode(400),
        fakeShareCode(900),
        fakeShareCode(1500),
    )

    /** 按 base64url 字母表造一串**编造的**分享码形状的载荷（`Random(20261016)` ⇒ 每次一样）。 */
    private fun fakeShareCode(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val random = Random(20261016)
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    /**
     * 把 [CodeMatrix] 摆成一张按像素渲染的图（每个模块 `cell` 像素、四周各 `quietZoneModules`
     * 个模块的静默区）。理由见类 KDoc。
     */
    private fun render(matrix: CodeMatrix, cell: Int, quietZoneModules: Int): Rendered {
        val width = (matrix.width + 2 * quietZoneModules) * cell
        val height = (matrix.height + 2 * quietZoneModules) * cell
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val matrixY = y / cell - quietZoneModules
            for (x in 0 until width) {
                val matrixX = x / cell - quietZoneModules
                val black = matrixY in 0 until matrix.height &&
                    matrixX in 0 until matrix.width &&
                    matrix[matrixX, matrixY]
                pixels[y * width + x] = if (black) BLACK_PIXEL else WHITE_PIXEL
            }
        }
        return Rendered(pixels, width, height)
    }

    /** 在正中间涂一块 `edge` 像素见方的白补丁（模拟「码被手指/水印遮掉一小块」）。 */
    private fun patch(image: Rendered, edge: Int) {
        val half = edge / 2
        val cx = image.width / 2
        val cy = image.height / 2
        for (y in (cy - half)..(cy + half)) {
            if (y < 0 || y >= image.height) continue
            for (x in (cx - half)..(cx + half)) {
                if (x < 0 || x >= image.width) continue
                image.pixels[y * image.width + x] = WHITE_PIXEL
            }
        }
    }

    /** 把 `[fromX, toX]` 这些列（整列）涂白 —— 模拟「码被遮掉半张」。 */
    private fun whitenColumns(image: Rendered, fromX: Int, toX: Int) {
        for (y in 0 until image.height) {
            for (x in fromX..minOf(toX, image.width - 1)) {
                image.pixels[y * image.width + x] = WHITE_PIXEL
            }
        }
    }

    /** 一张摆好的图（像素 + 宽高）—— 两端都吃这三个数。 */
    private class Rendered(val pixels: IntArray, val width: Int, val height: Int) {
        fun copy(): Rendered = Rendered(pixels.copyOf(), width, height)
    }

    private companion object {
        /** 「必须解回原文」那一档的密度：每个模块 2 像素（见类 KDoc 里那个实测的坑）。 */
        const val COARSE_CELL_PIXELS = 2

        /** 「两端同结论」那一档的密度：每个模块 4 像素（屏幕/相机的真实形状）。 */
        const val CELL_PIXELS = 4

        /** 0xFF000000：不透明黑。 */
        const val BLACK_PIXEL = -0x1000000

        /** 0xFFFFFFFF：不透明白。 */
        const val WHITE_PIXEL = -0x1
    }
}
