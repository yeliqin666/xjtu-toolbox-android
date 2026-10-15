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

/**
 * 「浏览器那一端的码阵（`:core` commonMain 的 [QrCodec] / [Code128Codec]）与 Android/jvm 那一端的码阵
 * （zxing）**逐模块相同**」这件事的机器可验版本。
 *
 * ## 为什么要有它
 *
 * wasmJs 上没有 zxing（JVM/Android 的 jar）⇒ 浏览器原先画不出矩阵（付款码只画数字、分享码二维码那一格空着）。
 * 本批把编码器按「三端一份」搬进 commonMain，**androidMain / jvmMain 两份 actual 一行未改**
 * （红线：Android 行为逐字不变）。于是「同一串数在两端看起来是同一张码」不能靠嘴说：
 * 这里拿**同一批输入**同时跑两份实现（jvm 那份 actual 就是 zxing），逐格断言 `width` / `height` / 每一个格子。
 *
 * ## 两侧分别是谁（别把这条测试读反了）
 *
 * | 一侧 | 是谁 | 用在哪 |
 * |---|---|---|
 * | [qrCodeModules] / [code128Modules] | jvmMain 的 actual = zxing（与 androidMain 那份**同一份源码**） | Android / 桌面 / serve 模式 |
 * | [QrCodec] / [Code128Codec] | 本批新增的纯 Kotlin 实现 | wasmJs（浏览器） |
 *
 * 所以这条测试通过 = **三端画出来的矩阵是同一张**（Android 与浏览器的付款码/分享码逐模块一致）。
 *
 * ## 还钉了两件「不是假矩阵」
 *
 * ## 还钉了两件「不是假矩阵」
 *
 * 用 zxing 的**解码器**（`MultiFormatReader`）把这一份纯 Kotlin 编出来的矩阵扫回原文 ——
 * 一张「看着像码、扫不出来」的图在解码器面前是过不去的（那是本批明确不许出现的东西）。
 * 扫的这一侧按**屏幕/相机**的真实形状摆图（每个模块 4 像素 + 静默区），判据是「扫回来逐字等于原文」，
 * 一个字都没放宽 —— 为什么不能按「一个模块一个像素」摆，见 [decode] 的 KDoc（那是解码器的一个死角，不是矩阵的问题）。
 * ⚠️ 夹具全是编造的：付款码那一档按 20 位十进制的形状造一串（与 `testkit` 的 `BAR_CODE` 同长度，
 * 但**不是**那一串），分享码那一档用 base64url 字母表随机造 —— 没有真实课表 / 姓名 / 学号。
 */
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

    /**
     * 二维码的输入：三端真会喂给这条缝的那几档，加上按长度铺开的一串（版本选择 / 纠错分块 / 交织
     * 都随长度变；只测一个长度会漏掉多块交织那段）。
     */
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

    /**
     * 把 [CodeMatrix] 摆成一张**按像素渲染**的图交给 zxing 的解码器。
     *
     * ⚠️ 图的几何按**屏幕/相机**的真实形状给（每个模块 [CELL_PIXELS] 像素 + 静默区），**不是**
     * 「一个模块一个像素」。这不是为了让它容易过 —— 本批实测（就是这条测试第一次红的真因）：
     *
     * | 每模块像素 | 静默区 | zxing 解码 |
     * |---|---|---|
     * | 1 | 无 | 通过 |
     * | 1 | 1~4 个模块 | **失败**（`FinderPatternFinder` 找不到定位图案） |
     * | 2 / 4 | 1~4 个模块 | 通过 |
     *
     * 也就是说失败在**解码器的找定位图案那一步**，与矩阵本身无关（同一个矩阵不留静默区反而扫得出来），
     * 而这种「一个模块一个像素 + 有白边」的图**任何相机与屏幕都不会产生**。所以这里按 4 px/模块 +
     * 4 个模块静默区（扫码器要求的下限）摆图；那时二维码与条形码在 1~4 个模块静默区、2~4 px/模块的
     * 全部组合下都能扫回来 —— 判据一个字没改：扫回来的必须**逐字等于**原文。
     *
     * 按进程内的像素数组（`RGBLuminanceSource`）而不是自己写一个 `LuminanceSource`：与 `:app` 侧
     * zxing 的常规用法同一条路（`RGBLuminanceSource` + `HybridBinarizer`）。
     */
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
