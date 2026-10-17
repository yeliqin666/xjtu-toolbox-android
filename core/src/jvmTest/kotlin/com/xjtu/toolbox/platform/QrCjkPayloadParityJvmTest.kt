package com.xjtu.toolbox.platform

import com.google.zxing.EncodeHintType
import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.xjtu.toolbox.platform.qr.QrReader
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「**中文 / 日文**的二维码，Android/jvm 那一端（zxing）与浏览器那一端（`:core` commonMain 里那份纯 Kotlin
 * 解码链）解出**同一段文本**」这件事的机器可验版本 —— 也就是本批把中日韩码表搬进 commonMain 之后，
 * 那条尾巴（「中文/日文二维码扫不出」）被钉住的样子。
 *
 * ## 覆盖了哪几档（每一档都要真的走过那条分支，不是「随便一张码」）
 *
 * | 用例 | 段长什么样 | 谁编的 |
 * |---|---|---|
 * | KANJI 段（**无 ECI**） | mode `1000` + 13 位/字 | zxing 的编码器（`CHARACTER_SET=Shift_JIS` 时它自己会选 KANJI，用例里用 `Encoder.chooseMode` 钉住这一点） |
 * | HANZI 段（**无 ECI**） | mode `1101` + 子集 `0001` + 13 位/字 | **手搭**的 v1-L 码（zxing 的编码器不发 HANZI 段，只能自己按规范搭字节流 —— 见 [buildImage]） |
 * | BYTE + ECI 29（GB18030） | mode `0100` + ECI + 字节 | zxing 的编码器 |
 * | BYTE + ECI 26（UTF-8） | 同上 | zxing 的编码器，以及**纯 Kotlin** 的 [QrCodec] |
 * | BYTE + **没有** ECI（猜成 Shift_JIS） | mode `0100` + Shift_JIS 字节 | 手搭（走 [com.xjtu.toolbox.platform.qr.QrCharset.guessCharset] 那条猜的路） |
 *
 * 每一档都是：**两端的编码器 × 两端的解码器**，四格都对（同一条载荷：zxing 编的图与纯 Kotlin 编的图，
 * 各拿两个解码器解，解出来必须是原文、而且两边一模一样）。
 *
 * ## 失败态也一起钉
 *
 * 截断的 KANJI / BYTE 段、以及 ECI 12/16 这两档「连 zxing 自己都不认」的：**两端都必须解不出来**（都是 null）
 * —— 先拿同一套搭码工具做一张好码当对照，证明「解不出来」不是因为图摆坏了。
 *
 * ## 夹具
 *
 * 全是编造的短句（校名 / 通用词 / 假编号），没有真人姓名、学号、课表。
 */
class QrCjkPayloadParityJvmTest {

    // ── 中日文载荷：两端的编码器 × 两端的解码器 ──────────────────

    @Test
    fun `日文载荷（KANJI 段）：zxing 编的与纯 Kotlin 编的，两端都解回原文`() {
        for (text in japaneseTexts()) {
            // 这条夹具本来就该是 KANJI 段：整段每个字符在 Shift_JIS 里都占两个字节 ——
            // 这正是 zxing 的 `isOnlyDoubleByteKanji` 那个条件，再加上下面那个 `CHARACTER_SET=Shift_JIS`，
            // zxing 选模式那一步就只能落到 KANJI 上。KANJI 段本身就说清楚了编码 ⇒ 它不会带 ECI
            //（zxing 的 `appendECI` 只在 BYTE 那一支里）。
            assertTrue(
                text.all { it.toString().toByteArray(Charset.forName("Shift_JIS")).size == 2 },
                "这条夹具的每个字符在 Shift_JIS 里都该是双字节：$text",
            )
            assertBothDecode(
                text,
                zxingImage(text, mapOf(EncodeHintType.CHARACTER_SET to "Shift_JIS")),
                "zxing 编（KANJI 段、无 ECI）",
            )
            // 再拿**手搭**的 KANJI 段（mode 1000，不靠 zxing 的选模式）钉一次
            assertBothDecode(text, kanjiImage(text), "手搭（KANJI 段、无 ECI）")
            assertBothDecode(text, pureImage(text), "纯 Kotlin 编（BYTE + ECI 26）")
        }
    }

    @Test
    fun `中文载荷（BYTE + ECI 29 = GB18030）：zxing 编的与纯 Kotlin 编的，两端都解回原文`() {
        for (text in chineseTexts()) {
            assertBothDecode(
                text,
                zxingImage(text, mapOf(EncodeHintType.CHARACTER_SET to "GB18030")),
                "zxing 编（BYTE + ECI 29）",
            )
            assertBothDecode(text, pureImage(text), "纯 Kotlin 编（BYTE + ECI 26）")
        }
    }

    @Test
    fun `中文载荷（BYTE + ECI 26 = UTF-8）：zxing 编的与纯 Kotlin 编的，两端都解回原文`() {
        for (text in chineseTexts()) {
            assertBothDecode(
                text,
                zxingImage(text, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")),
                "zxing 编（BYTE + ECI 26）",
            )
        }
    }

    // ── HANZI 段：zxing 的编码器不发这一段，自己按规范搭一张 v1-L ──

    @Test
    fun `中文载荷（HANZI 段、无 ECI）：两端都解回原文`() {
        val text = "西安交通大学"
        val image = hanziImage(text)
        // 对照：同一套搭码工具搭出来的图两端都认得出来（下面那些失败态才有意义）
        assertBothDecode(text, image, "手搭（HANZI 段、无 ECI）")
    }

    @Test
    fun `日文载荷（BYTE 段、没有 ECI）：两端都按猜出来的 Shift_JIS 解回原文`() {
        val text = "ｱｲｳｴｵｶﾞ" // 半角片假名：Shift_JIS 是单字节，走的是「猜编码」那条路
        val image = byteImage(text, eci = null, charset = "Shift_JIS")
        val (byZxing, byPure) = decodeBoth(image)
        assertSameBoth(byZxing, byPure, "手搭（BYTE 段、没有 ECI、被猜成 Shift_JIS）")
        assertIsText(text, byPure, "手搭（BYTE 段、没有 ECI、被猜成 Shift_JIS）")
    }

    // ── 失败态：坏码 / 截断 / 两端都不认的 ECI ───────────────────

    @Test
    fun `截断与不认的 ECI：两端一样解不出来`() {
        // ① KANJI 段说 200 个字，实际只给了两三个 —— 位数根本不够
        assertBothFail(kanjiImage("東京都", declaredCount = 200), "截断的 KANJI 段")
        // ② BYTE 段说 30 个字，实际只有几个字节
        assertBothFail(byteImage("abcdef", declaredCount = 30, eci = null, charset = "UTF-8"), "截断的 BYTE 段")
        // ③ ECI 16（ISO-8859-14）：zxing 的 VALUE_TO_ECI 里没有这个值 ⇒ 它在这一步就抛；
        //    这一端认得出名字但没有码表 ⇒ 也在这一步失败。两端都是 null。
        assertBothFail(byteImage("abc", declaredCount = 3, eci = 16, charset = "ISO-8859-1"), "ECI 16（两端都不认）")
        // ④ ECI 12（ISO-8859-10）：同上
        assertBothFail(byteImage("abc", declaredCount = 3, eci = 12, charset = "ISO-8859-1"), "ECI 12（两端都不认）")
    }

    // ── 解码两端（同一条判据的两个方向） ────────────────────────

    /** 解一张图，返回（zxing 那一端的结果，纯 Kotlin 那一端的结果）。 */
    private fun decodeBoth(image: Image): Pair<String?, String?> {
        val pixels = render(image, cell = CELL_PIXELS, quietZoneModules = 4)
        return qrDecodeArgb(pixels.data, pixels.width, pixels.height) to
            QrReader.decodeArgb(pixels.data, pixels.width, pixels.height)
    }

    private fun assertBothDecode(text: String, image: Image, note: String) {
        val (byZxing, byPure) = decodeBoth(image)
        assertSameBoth(byZxing, byPure, note)
        assertIsText(text, byPure, "$note（纯 Kotlin 那一端）")
        assertIsText(text, byZxing, "$note（zxing 那一端）")
    }

    /**
     * 两端解出来的必须是同一段文本。
     *
     * 自己 throw 而不是 `assertEquals`：两个都是 `String?`（「解不出来」也是合法结论），
     * 而 `assertEquals` 那两个 String 重载只吃非空实参。
     */
    private fun assertSameBoth(byZxing: String?, byPure: String?, note: String) {
        if (byZxing != byPure) {
            throw AssertionError(
                "两端解出来的不一样（$note）：zxing=${byZxing?.take(24)}，纯 Kotlin=${byPure?.take(24)}",
            )
        }
    }

    /** 解出来的必须是原文。 */
    private fun assertIsText(text: String, got: String?, note: String) {
        if (got != text) throw AssertionError("没解回原文（$note）：解出来的是 ${got?.take(24)}…")
    }

    private fun assertBothFail(image: Image, note: String) {
        val (byZxing, byPure) = decodeBoth(image)
        assertNull(byZxing, "zxing 那边不该解出东西来（$note）：${byZxing?.take(24)}…")
        assertNull(byPure, "纯 Kotlin 那边不该解出东西来（$note）：${byPure?.take(24)}…")
    }

    // ── 两端的编码器 ────────────────────────────────────────────

    /** zxing（= App/桌面那一端）编的图。 */
    private fun zxingImage(text: String, hints: Map<EncodeHintType, Any> = emptyMap()): Image {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.L, hints)
        val matrix = qr.matrix
        return Image(matrix.width, matrix.height) { x, y -> matrix.get(x, y).toInt() == 1 }
    }

    /** 纯 Kotlin（= 浏览器那一端）编的图 —— 它总是 BYTE + UTF-8 + ECI 26。 */
    private fun pureImage(text: String): Image {
        val matrix = assertNotNull(QrCodec.encode(text), "纯 Kotlin 那份应当编得出二维码：${text.take(24)}…")
        return Image(matrix.width, matrix.height) { x, y -> matrix[x, y] }
    }

    // ── 手搭的 v1-L 码（只为了让 HANZI / 无 ECI 的 BYTE 段能被摆成一张真码） ──

    /** HANZI 段（mode 1101 + 子集 1 + 13 位/字），文本按 GB2312 编。 */
    private fun hanziImage(text: String, declaredCount: Int = -1): Image {
        val bytes = text.toByteArray(Charset.forName("GB2312"))
        val count = if (declaredCount >= 0) declaredCount else text.length
        return buildImage { bits ->
            bits.write(MODE_HANZI, 4)
            bits.write(HANZI_SUBSET_GB2312, 4)
            bits.write(count, 8) // v1 的字符数位数就是 8
            for (i in 0 until minOf(bytes.size / 2, count)) {
                val hi = bytes[i * 2].toInt() and 0xFF
                val lo = bytes[i * 2 + 1].toInt() and 0xFF
                bits.write(hanziValue(hi, lo), 13)
            }
        }
    }

    /** KANJI 段（mode 1000 + 13 位/字），文本按 Shift_JIS 编。 */
    private fun kanjiImage(text: String, declaredCount: Int = -1): Image {
        val bytes = text.toByteArray(Charset.forName("Shift_JIS"))
        val count = if (declaredCount >= 0) declaredCount else bytes.size / 2
        return buildImage { bits ->
            bits.write(MODE_KANJI, 4)
            bits.write(count, 8)
            for (i in 0 until minOf(bytes.size / 2, count)) {
                val hi = bytes[i * 2].toInt() and 0xFF
                val lo = bytes[i * 2 + 1].toInt() and 0xFF
                bits.write(kanjiValue(hi, lo), 13)
            }
        }
    }

    /** BYTE 段（mode 0100[+ ECI] + 字节），[charset] 决定文本编成什么字节。 */
    private fun byteImage(text: String, declaredCount: Int = -1, eci: Int?, charset: String): Image {
        val bytes = text.toByteArray(Charset.forName(charset))
        val count = if (declaredCount >= 0) declaredCount else bytes.size
        return buildImage { bits ->
            if (eci != null) {
                bits.write(MODE_ECI, 4)
                bits.write(eci, 8)
            }
            bits.write(MODE_BYTE, 4)
            bits.write(count, 8)
            for (i in 0 until minOf(bytes.size, count)) bits.write(bytes[i].toInt() and 0xFF, 8)
        }
    }

    /**
     * 把 [write] 写出来的位流摆成一张**真的** v1-L 二维码。
     *
     * zxing 那份编码器不发 HANZI 段（`Mode.HANZI` 只存在于解码侧），所以这几张图只能自己按规范搭：
     * 位流 → 挑一个装得下的版本（只从 `QR_VERSIONS` 里挑**单块**的 v1..v5 —— 多块要交织，这里用不上）
     * → 终止符 + 补零 + `0xEC`/`0x11` 交替填充（与 zxing 的 `terminateBits` 同一套）→ 纠错码字用
     * zxing 的 `ReedSolomonEncoder` 算（反正判据是「解回来」）→ 单块直接串起来 → 交给 commonMain 里那份
     * [com.xjtu.toolbox.platform.QrLayout.buildMatrix]（掩码用 0；格式信息里的纠错等级是它写死的 L，
     * 与下面那套单块参数同一档）。
     */
    private fun buildImage(write: (Bits) -> Unit): Image {
        val bits = Bits()
        write(bits)
        val version = QR_VERSIONS.firstOrNull {
            it.numBlocks == 1 && it.versionNumber <= 9 && bits.size <= it.numDataCodewords * 8
        } ?: throw IllegalStateException("这条夹具塞不进 v1-L..v5-L：${bits.size} 位")
        val dataCodewords = version.numDataCodewords
        val ecCodewords = version.totalEcCodewords
        val capacityBits = dataCodewords * 8
        repeat(minOf(4, capacityBits - bits.size)) { bits.write(0, 1) }
        while (bits.size % 8 != 0) bits.write(0, 1)
        val data = ByteArray(dataCodewords)
        bits.toBytes().copyInto(data)
        var pad = 0xEC
        for (i in bits.size / 8 until dataCodewords) {
            data[i] = pad.toByte()
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }

        val codewords = IntArray(dataCodewords + ecCodewords)
        for (i in data.indices) codewords[i] = data[i].toInt() and 0xFF
        ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(codewords, ecCodewords)

        val dataBits = QrBitBuffer()
        for (cw in codewords) dataBits.appendBits(cw, 8)
        val matrix = QrByteMatrix(version.dimension, version.dimension)
        QrLayout.buildMatrix(dataBits, version, 0, matrix)
        return Image(version.dimension, version.dimension) { x, y -> matrix.get(x, y) == 1 }
    }

    // ── 摆图 ────────────────────────────────────────────────────

    /** 一个只有黑白两态的模块阵列（两端都只吃「像素 + 宽高」，所以这里摊成像素）。 */
    private class Image(val width: Int, val height: Int, private val black: (Int, Int) -> Boolean) {
        operator fun get(x: Int, y: Int): Boolean = black(x, y)
    }

    /** 每个模块 [cell] 像素、四周各 4 个模块静默区；黑 = `0xFF000000`、白 = `0xFFFFFFFF`。 */
    private fun render(image: Image, cell: Int, quietZoneModules: Int): Rendered {
        val width = (image.width + 2 * quietZoneModules) * cell
        val height = (image.height + 2 * quietZoneModules) * cell
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val my = y / cell - quietZoneModules
            for (x in 0 until width) {
                val mx = x / cell - quietZoneModules
                val on = my in 0 until image.height && mx in 0 until image.width && image[mx, my]
                pixels[y * width + x] = if (on) BLACK_PIXEL else WHITE_PIXEL
            }
        }
        return Rendered(pixels, width, height)
    }

    private class Rendered(val data: IntArray, val width: Int, val height: Int)

    /** 位流小工具（写高位在前，与规范一致）。 */
    private class Bits {
        private val values = ArrayList<Boolean>(192)
        val size: Int get() = values.size

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) values.add(((value shr i) and 1) == 1)
        }

        fun toBytes(): ByteArray {
            val out = ByteArray(size / 8)
            for (i in 0 until size) {
                if (values[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
            return out
        }
    }

    /**
     * GB2312 的两个字节 → HANZI 段的 13 位值（zxing `decodeHanziSegment` 那个式子的逆）。
     *
     * 解码那边是：`assembled = (v / 0x60) << 8 | (v % 0x60)`，然后 `+0xA1A1`（assembled < 0xA00）
     * 或 `+0xA6A1`。这里倒过来：`0xA1A1 + 0xA00 = 0xABA1` 是那个分界。
     */
    private fun hanziValue(hi: Int, lo: Int): Int {
        val gb = (hi shl 8) or lo
        val assembled = if (gb < 0xABA1) gb - 0xA1A1 else gb - 0xA6A1
        return (assembled shr 8) * 0x60 + (assembled and 0xFF)
    }

    /** Shift_JIS 的两个字节 → KANJI 段的 13 位值（zxing `decodeKanjiSegment` 那个式子的逆）。 */
    private fun kanjiValue(hi: Int, lo: Int): Int {
        val sjis = (hi shl 8) or lo
        // 解码那边：assembled < 0x1F00 时 +0x8140、否则 +0xC140 ⇒ 0x8140 + 0x1F00 = 0xA040 是分界
        val assembled = if (sjis < 0xA040) sjis - 0x8140 else sjis - 0xC140
        return (assembled shr 8) * 0xC0 + (assembled and 0xFF)
    }

    // ── 夹具 ────────────────────────────────────────────────────

    private fun japaneseTexts(): List<String> = listOf(
        "東京都渋谷区のテスト",
        "日本語の漢字テスト", // 只用 JIS X 0208 里的字（简体「维/码」不在里面，那种码在 Shift_JIS 里编不出来）
        "ひらがな・カタカナ・漢字の混在",
    )

    private fun chineseTexts(): List<String> = listOf(
        "西安交通大学",
        "西安交通大学 兴庆校区 3 号门（编造的）",
        "中文二维码测试·甲乙丙丁 1234",
    )

    private companion object {
        /** 段模式那几位（`QrMode` 里的值，需自行读写这一段所以在这里重列）。 */
        const val MODE_BYTE = 0x4
        const val MODE_ECI = 0x7
        const val MODE_KANJI = 0x8
        const val MODE_HANZI = 0xD
        const val HANZI_SUBSET_GB2312 = 1

        /** 手搭的那几张图用纠错等级 L、掩码 0（见 [buildImage]）。 */

        /** 每模块像素（与 `QrDecodeParityJvmTest` 同一档：屏幕/相机的真实形状）。 */
        const val CELL_PIXELS = 4

        const val BLACK_PIXEL = -0x1000000
        const val WHITE_PIXEL = -0x1
    }
}
