package com.xjtu.toolbox.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 浏览器端码阵（[QrCodec] / [Code128Codec]）与 zxing 逐模块相同的机器验证。 */
class CodeMatrixParityJvmTest {

    // ── 二维码：两实现逐格相同 ──────────────────────────────

    @Test
    fun `二维码：纯 Kotlin 那份与 zxing 那份逐模块相同`() {
        for (text in qrCorpus()) {
            val byZxing = assertNotNull(
                qrCodeModules(text),
                "jvm 这一端（zxing）应当能编出二维码：${text.take(24)}…",
            )
            val byPure = assertNotNull(
                QrCodec.encode(text),
                "纯 Kotlin 那份应当也能编出同一张二维码：${text.take(24)}…",
            )
            assertSameMatrix(byZxing, byPure, "二维码「${text.take(24)}…」（${text.length} 字）")
        }
    }

    @Test
    fun `二维码：装不下的输入两边都如实返回 null`() {
        // 40 版 L 的容量是 7089 个数字 ⇒ 八千个数字谁都不该装下（不是「慢」或者「编出来半张」）。
        val tooLong = "1234567890".repeat(800)
        assertNull(qrCodeModules(tooLong), "zxing 那份不该装下 8000 位数字")
        assertNull(QrCodec.encode(tooLong), "纯 Kotlin 那份也不该装下 8000 位数字")
    }

    @Test
    fun `纯 Kotlin 编出来的二维码能被 zxing 的解码器扫回原文`() {
        for (text in qrCorpus().filter { it.isNotEmpty() }) {
            val matrix = assertNotNull(QrCodec.encode(text), "纯 Kotlin 编不出二维码：${text.take(24)}…")
            val decoded = decode(matrix, BarcodeFormat.QR_CODE, quietZoneModules = 4)
            assertEquals(text, decoded, "这一张矩阵扫回来的不是原文（码是假的？）：${text.take(24)}…")
        }
    }

    // ── 条形码：两实现逐格相同 ──────────────────────────────

    @Test
    fun `条形码：纯 Kotlin 那份与 zxing 那份逐模块相同`() {
        for (text in code128Corpus()) {
            val byZxing = assertNotNull(code128Modules(text), "jvm 这一端（zxing）应当能编出条形码：$text")
            val byPure = assertNotNull(Code128Codec.encode(text), "纯 Kotlin 那份应当也能编出同一张条形码：$text")
            assertSameMatrix(byZxing, byPure, "条形码「$text」（${text.length} 字）")
        }
    }

    @Test
    fun `条形码：非 ASCII 与空串两边都如实返回 null`() {
        for (text in listOf("", "付款码 12345", "12345\u00e9")) {
            assertNull(code128Modules(text), "zxing 那份不该编得出：$text")
            assertNull(Code128Codec.encode(text), "纯 Kotlin 那份也不该编得出：$text")
        }
    }

    @Test
    fun `纯 Kotlin 编出来的条形码能被 zxing 的解码器扫回原文`() {
        for (text in code128Corpus()) {
            val matrix = assertNotNull(Code128Codec.encode(text), "纯 Kotlin 编不出条形码：$text")
            val decoded = decode(matrix, BarcodeFormat.CODE_128, quietZoneModules = 10)
            assertEquals(text, decoded, "这一条条纹扫回来的不是原文（码是假的？）：$text")
        }
    }

    // ── 夹具与工具 ──────────────────────────────────────────

    /** 二维码的输入：三端真会喂给这条缝的那几档，加上按长度铺开的一串（版本选择 / 纠错分块 / 交织都随长度变；只测一个长度会漏掉多块交织那段）。 */
    private fun qrCorpus(): List<String> {
        val fixed = listOf(
            // 付款码那一档的形状：20 位十进制（夹具是编造的，不是 testkit 里那一串）
            "12345678901234567890",
            "1",
            "12",
            "1234567",
            "0".repeat(77),
            // 字母数字模式（'-' '.' ':' '/' 空格都在那张表里）
            "HELLO WORLD 1234-\$%*+./:",
            // 字节模式：'_' 不在字母数字表里 ⇒ 整段走字节（会带 ECI 26，正是分享码二维码那一档）
            "abc_def-GHI_123",
            "分享码 ABC-123_xyz·中文",
        )
        val byLength = listOf(1, 3, 8, 20, 40, 77, 100, 160, 240, 400, 600, 900, 1200, 1500, 1800)
            .map { length -> "1234567890".repeat(length / 10 + 1).take(length) }
        val random = Random(20261015)
        val fuzz = (0 until 40).map {
            val length = 1 + random.nextInt(200)
            val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_./: 中文"
            buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
        return fixed + byLength + fuzz
    }

    /** 条形码的输入：付款码那一档（纯数字、20 位）与几种会走不同码集 / 起始符的串。 */
    private fun code128Corpus(): List<String> = listOf(
        "12345678901234567890",
        "1",
        "12",
        "123",
        "1234",
        "12345",
        "123456",
        "0000000000000000000000123456789",
        "ABC123",
        "abc-123",
        "A1B2C3D4",
        "XJTU-TOOLBOX-20261015",
        " ",
        "~!@#\$%^&*()",
    )

    /** 逐格比：尺寸先比，再比每一个格子（失败信息里带上坐标，方便定位是哪一版/哪一段）。 */
    private fun assertSameMatrix(expected: CodeMatrix, actual: CodeMatrix, what: String) {
        assertEquals(expected.width, actual.width, "$what 的宽度不同")
        assertEquals(expected.height, actual.height, "$what 的高度不同")
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertTrue(
                    expected[x, y] == actual[x, y],
                    "$what 在 ($x, $y) 这一格不同：zxing=${expected[x, y]}，纯 Kotlin=${actual[x, y]}",
                )
            }
        }
    }

    /** 把 [CodeMatrix] 摆成一张按像素渲染的图交给 zxing 的解码器。 */
    private fun decode(matrix: CodeMatrix, format: BarcodeFormat, quietZoneModules: Int): String {
        val cell = CELL_PIXELS
        // 两个方向都要留静默区：条形码那个阵列只有 1 个模块高（渲染方拉伸），上下同样要留白，
        // 否则「这一行」会被当成整张图 —— 剪掉边上的白，解码器连定位图案都找不到（本批踩过）。
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
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(format))
        return MultiFormatReader().decode(bitmap, hints).text
    }

    private companion object {
        /** 每个模块几个像素（见 [decode]：按屏幕/相机的真实密度给，不是 1）。 */
        const val CELL_PIXELS = 4

        /** 0xFF000000：不透明黑（zxing 的 `RGBLuminanceSource` 取 `(r + 2g + b) / 4` 当灰度）。 */
        const val BLACK_PIXEL = -0x1000000

        /** 0xFFFFFFFF：不透明白。 */
        const val WHITE_PIXEL = -0x1
    }
}
