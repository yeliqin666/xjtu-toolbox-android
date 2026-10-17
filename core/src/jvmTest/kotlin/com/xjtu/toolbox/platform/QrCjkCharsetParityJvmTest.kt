package com.xjtu.toolbox.platform

import com.xjtu.toolbox.platform.qr.QrCharset
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「commonMain 里那份**纯 Kotlin 码表**（[com.xjtu.toolbox.platform.qr.QrCjkTables]）解出来的字符串，
 * 与 `java.nio.charset.Charset` 解出来的**逐字相同**」这件事的机器可验版本。
 *
 * ## 为什么这条判据就是「与 zxing 相同」
 *
 * zxing 3.5.4 解 BYTE / KANJI / HANZI 段时干的就是 `new String(bytes, charset)`（`Charset` = ECI 指到的那份、
 * 或者 `StringUtils` 里那几个常量）—— 所以「我们与 JDK 的 `Charset` 逐字相同」⇒「我们与 zxing 逐字相同」。
 * 码表是 `tools/gen-qr-charset-tables.java` 从**同一份 JDK** 枚举出来的，这里把那条判据钉在测试里：
 *
 *  - 单字节那一族（17 个）：**全 256 个字节**逐个对；
 *  - 双字节那一族（Shift_JIS / GB2312 / Big5 / EUC-KR）：**全部 65536 个字节对**逐个对，再加一批抽样的
 *    三字节串（盯「吞 1 字节还是吞 2 字节」那种坏输入）；GB18030 的字节对在它自己那条用例里一起扫；
 *  - GB18030 的四字节形态：抽样的四字节串、全部「四字节前缀截断」的三字节串、以及指针边界 ±1。
 *
 * 生成器那边是**穷举**（单字节全 256、字节对全 65536、三字节全 65536×4、GB18030 四字节形态全 1587600），
 * 那条穷举是离线跑的；这里留的是它的一份「每次编译都重跑」的缩小版。
 *
 * ## 夹具
 *
 * 没有真实数据：字节串全是 `0..255` 这种枚举出来的，不是谁的内容。
 */
class QrCjkCharsetParityJvmTest {

    // ── 单字节那一族 ────────────────────────────────────────────

    @Test
    fun `单字节那一族的全 256 个字节与 JDK 的 Charset 逐字节相同`() {
        for ((name, jdkName) in SINGLE_BYTE) {
            val cs = Charset.forName(jdkName)
            for (b in 0..255) assertSameAsJdk(name, jdkName, cs, byteArrayOf(b.toByte()))
        }
    }

    // ── 双字节那一族：全部字节对 ────────────────────────────────

    @Test
    fun `双字节那一族的全部 65536 个字节对与 JDK 的 Charset 相同`() {
        for ((name, jdkName) in MULTI_BYTE) {
            val cs = Charset.forName(jdkName)
            for (b1 in 0..255) {
                for (b2 in 0..255) assertSameAsJdk(name, jdkName, cs, byteArrayOf(b1.toByte(), b2.toByte()))
            }
        }
    }

    @Test
    fun `双字节那一族的抽样三字节串与 JDK 的 Charset 相同`() {
        // 第三个字节特意挑「合法尾字节」与「不是尾字节」两种：这决定了前两个字节是吞 1 个还是吞 2 个
        val thirds = intArrayOf(0x40, 0xFF)
        for ((name, jdkName) in MULTI_BYTE) {
            val cs = Charset.forName(jdkName)
            for (b1 in 0..255) {
                for (b2 in 0..255 step 7) {
                    for (b3 in thirds) {
                        assertSameAsJdk(name, jdkName, cs, byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte()))
                    }
                }
            }
        }
    }

    // ── GB18030：二字节 + 四字节形态（含截断与越界） ────────────

    @Test
    fun `GB18030 的二字节与四字节形态都与 JDK 的 Charset 相同`() {
        val cs = Charset.forName("GB18030")

        // ① 二字节那一半也扫一遍（含首字节后面跟 0x30-0x39 那种「四字节被打断」的形状）
        for (b1 in 0..255) {
            for (b2 in 0..255) assertSameAsJdk("GB18030", "GB18030", cs, byteArrayOf(b1.toByte(), b2.toByte()))
        }

        // ② 抽样的四字节串：首字节挑几个代表（低端 / 汉字区 / 边界前 / 高位），第三字节按步长扫
        for (b1 in intArrayOf(0x81, 0x82, 0x84, 0x90, 0xA0, 0xC0, 0xE3, 0xE4, 0xFE)) {
            for (b2 in 0x30..0x39) {
                for (b3 in 0x81..0xFE step 5) {
                    for (b4 in 0x30..0x39) {
                        assertSameAsJdk(
                            "GB18030", "GB18030", cs,
                            byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte()),
                        )
                    }
                }
            }
        }

        // ③ 四字节前缀被截断的那些三字节串（「吞 3 还是吞 4」就看这里）
        for (b1 in 0x81..0xFE) {
            for (b2 in 0x30..0x39) {
                for (b3 in intArrayOf(0x30, 0x80, 0x81, 0xA1, 0xFE, 0xFF)) {
                    assertSameAsJdk("GB18030", "GB18030", cs, byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte()))
                }
            }
        }

        // ④ 指针边界：39419/39420 是「GB18030 定义到哪为止」，另一个是补充平面那一段的两端
        for (pointer in intArrayOf(39418, 39419, 39420, 39421, 188999, 189000, 189001, 1237574, 1237575, 1237576)) {
            for (p in intArrayOf(pointer - 1, pointer, pointer + 1)) {
                if (p < 0 || p > 1587599) continue
                assertSameAsJdk("GB18030", "GB18030", cs, bytesOfPointer(p))
            }
        }
    }

    // ── ECI 那两档「连 zxing 自己都不认」的 ─────────────────────

    @Test
    fun `ECI 12 与 16 这一端解不出来，而且 zxing 那边也查不到`() {
        // zxing 3.5.4 的 VALUE_TO_ECI 里没有 12 / 16 ⇒ 它在这一步就抛 FormatException
        assertNull(
            com.google.zxing.common.CharacterSetECI.getCharacterSetECIByValue(12),
            "zxing 那边 ECI 12 应当是查不到的（查得到的话这条边界表就写错了）",
        )
        assertNull(
            com.google.zxing.common.CharacterSetECI.getCharacterSetECIByValue(16),
            "zxing 那边 ECI 16 应当是查不到的（查得到的话这条边界表就写错了）",
        )

        // 这一端：名字认得出来（便于诊断），但解不出来 ⇒ 调用方抛 FormatException，两端结论一样
        assertEquals("ISO-8859-10", QrCharset.eciNameFor(12))
        assertEquals("ISO-8859-14", QrCharset.eciNameFor(16))
        assertFalse(QrCharset.canDecode("ISO-8859-10"))
        assertFalse(QrCharset.canDecode("ISO-8859-14"))
        assertNull(QrCharset.decode("ISO-8859-10", byteArrayOf(0x41)))
        assertNull(QrCharset.decode("ISO-8859-14", byteArrayOf(0x41)))

        // 这两档生成不出码表的原因也钉一下：本机 JDK 就没有这两个字符集
        assertFalse(Charset.isSupported("ISO-8859-10"), "本机 JDK 居然有 ISO-8859-10？那这条边界该重写")
        assertFalse(Charset.isSupported("ISO-8859-14"), "本机 JDK 居然有 ISO-8859-14？那这条边界该重写")
    }

    @Test
    fun `ECI 表里剩下的名字这一端都解得出来`() {
        for (value in 0..30) {
            val name = QrCharset.eciNameFor(value) ?: continue
            if (name == "ISO-8859-10" || name == "ISO-8859-14") continue
            assertTrue(QrCharset.canDecode(name), "ECI $value（$name）应当是解得出来的")
        }
        assertTrue(QrCharset.canDecode("US-ASCII"), "ECI 170 是 US-ASCII，应当解得出来")
        assertNull(QrCharset.eciNameFor(14), "ECI 14 zxing 查不到，这一端也不该认")
        assertNull(QrCharset.eciNameFor(19), "ECI 19 zxing 查不到，这一端也不该认")
        assertNull(QrCharset.eciNameFor(31), "ECI 31 zxing 查不到，这一端也不该认")
        assertNull(QrCharset.eciNameFor(900), "ECI 900 越界，zxing 直接抛、这一端返回 null")
        assertNull(QrCharset.eciNameFor(-1), "负的 ECI value，zxing 直接抛、这一端返回 null")
    }

    // ── 夹具与工具 ──────────────────────────────────────────────

    /**
     * 一段字节：这一端解出来的必须与 JDK 的 `Charset` 解出来的**逐字相同**。
     *
     * 断言写成「自己 throw」而不是 `assertEquals`：这一端解不出来时返回的是 null，
     * 那种情形要报的是「它本该解得出来」，与「解得出来但不一样」是两句不同的话。
     */
    private fun assertSameAsJdk(name: String, jdkName: String, cs: Charset, bytes: ByteArray) {
        val want = String(bytes, cs)
        val got = QrCharset.decode(name, bytes)
            ?: throw AssertionError(
                "「$name」（JDK $jdkName）在 ${hexBytes(bytes)} 上解不出来（返回 null）：JDK 那边是 ${show(want)}",
            )
        if (want != got) {
            throw AssertionError(
                "「$name」（JDK $jdkName）在 ${hexBytes(bytes)} 上与 JDK 不同：JDK=${show(want)}，本表=${show(got)}",
            )
        }
    }

    /** 指针 → GB18030 四字节形态的那四个字节。 */
    private fun bytesOfPointer(pointer: Int): ByteArray {
        val b1 = 0x81 + pointer / 12600
        val rem = pointer % 12600
        val b2 = 0x30 + rem / 1260
        val rem2 = rem % 1260
        return byteArrayOf(
            b1.toByte(),
            b2.toByte(),
            (0x81 + rem2 / 10).toByte(),
            (0x30 + rem2 % 10).toByte(),
        )
    }

    private fun hexBytes(bytes: ByteArray): String = bytes.joinToString(" ") { hex(it.toInt() and 0xFF) }

    private fun hex(b: Int): String = b.toString(16).uppercase().padStart(2, '0')

    private fun show(s: String): String =
        s.map { "U+" + it.code.toString(16).uppercase().padStart(4, '0') }.joinToString(" ")

    private companion object {
        /** 单字节那一族：这一端有码表的这几个（ECI 表里出现过、本机 JDK 也有实现）。 */
        val SINGLE_BYTE = listOf(
            "Cp437" to "IBM437",
            "ISO-8859-2" to "ISO-8859-2",
            "ISO-8859-3" to "ISO-8859-3",
            "ISO-8859-4" to "ISO-8859-4",
            "ISO-8859-5" to "ISO-8859-5",
            "ISO-8859-6" to "ISO-8859-6",
            "ISO-8859-7" to "ISO-8859-7",
            "ISO-8859-8" to "ISO-8859-8",
            "ISO-8859-9" to "ISO-8859-9",
            "ISO-8859-11" to "ISO-8859-11",
            "ISO-8859-13" to "ISO-8859-13",
            "ISO-8859-15" to "ISO-8859-15",
            "ISO-8859-16" to "ISO-8859-16",
            "windows-1250" to "windows-1250",
            "windows-1251" to "windows-1251",
            "windows-1252" to "windows-1252",
            "windows-1256" to "windows-1256",
        )

        /** 双字节那一族（GB18030 在它自己那条用例里扫）。 */
        val MULTI_BYTE = listOf(
            "Shift_JIS" to "Shift_JIS",
            "GB2312" to "GB2312",
            "Big5" to "Big5",
            "EUC-KR" to "EUC-KR",
        )
    }
}
