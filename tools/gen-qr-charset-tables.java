/*
 * 生成 `core/src/commonMain/kotlin/com/xjtu/toolbox/platform/qr/QrCjkTables.kt`
 * —— 纯 Kotlin 二维码解码链要用的中日韩（以及 ECI 那一族）字符集码表。
 *
 * ## 怎么跑
 *
 *     cd <仓库根> && java tools/gen-qr-charset-tables.java
 *
 * （单文件源码模式，JDK 11+ 都行；本仓用的是 `openjdk 25.0.4.1`。跑完会把
 *  `QrCjkTables.kt` 整体覆盖写一遍，并把每个字符集的自检结果打到 stdout。）
 *
 * ## 为什么是「从 JDK 生成」而不是「拷一份码表」
 *
 * Kotlin 的 commonMain 没有字符集表，wasmJs 上更没有 `java.nio.charset`。这一份码表的每一个
 * 映射都是**本机 JDK 的 `Charset` 解码器**逐个字节（双字节集就逐个字节对）枚举出来的 ——
 * 没有拷贝 JDK 的任何文件（JDK 的码表是它自己的实现，我们没有复制它的源码或数据文件），
 * 也没有拷贝 zxing 的那份码表（`com.google.zxing.common.CharacterSetECI` 那一族是 Apache-2.0，
 * 本文件里一行都没有它的东西）。之所以要「从 JDK 生成」，是因为 `:core:jvmTest` 的判据是
 * 「与 zxing 逐字相同」，而 zxing 在 JVM 上就是拿 `java.nio.charset.Charset` 解的 ——
 * 从同一份 JDK 生成，判据才有可能真的成立（`QrCjkCharsetParityJvmTest` 逐字节钉住这件事）。
 *
 * ## 为什么确定性
 *
 *  - 输入只有「本机 JDK 的 `Charset` 实现」，没有任何随机数、时间戳、哈希表遍历序；
 *  - 遍历顺序全是 `0..255` / `0x81..0xFE` 这种写死的升序循环；
 *  - 输出里唯一会随环境变的是文件头那句「生成自哪个 JDK」（固定 JDK ⇒ 固定字节）；
 *  - 自检（`verify*`）是**穷举**：单字节全 256、字节对全 65536、三字节全 65536×4、
 *    GB18030 四字节形态全 1587600 —— 每个输入都拿 `new String(bytes, charset)` 对一遍，
 *    对不上就直接抛，不会写出「半对」的码表。
 *    结论：同一个 JDK 上跑两次，`md5sum` 必须一样（汇报里贴了两次生成的 md5）。
 *
 * ## 数据布局（与 `QrCharset.kt` 里手写的 `QrCharsetTable` 对应）
 *
 *  - `singleCps`：256 项，`singleCps[b]` = 这个字节单独解码出来的码位，-1 = 它不是「单字节字符」
 *    （即：要么是首字节、要么这个字节单独出现时 JDK 报 malformed ⇒ 替换成一个 U+FFFD 并只吞 1 字节）；
 *  - `rows` / `cols`：256 项，首字节 → 行号、尾字节 → 列号，-1 = 不是首/尾字节；
 *  - `flat`：行主序的 `rows × nCols` 表，格子值有三种：
 *      * 正常字符     —— 这一对就是它；
 *      * `'\\uFFFD'`  —— 结构合法但没有映射（JDK 报 unmappable、长度 2）⇒ 替换成 U+FFFD 并吞 2 字节；
 *      * `'\\u0000'`  —— 结构不合法（JDK 报 malformed、长度 1）⇒ 替换成 U+FFFD 并只吞 1 字节（尾字节
 *                       会被当成下一个字节重新看）。这两种「坏」的吞字节数不一样，所以必须分开记。
 *  - GB18030 另有四字节形态：指针 → 码位按「常数差」分段（见 `QrGb18030Four`）。
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class GenQrCharsetTables {

    // ─────────────────────────────── 严格解码：读出 JDK 的真实口径 ───────────────────────────────

    private static final int OK = 0;
    private static final int MALFORMED = 1;
    private static final int UNMAPPABLE = 2;

    private static final class R {
        final int kind;
        final int len;
        final String text;

        R(int kind, int len, String text) {
            this.kind = kind;
            this.len = len;
            this.text = text;
        }
    }

    /** 用 REPORT 口径解一段字节：拿到「这一串是 OK / 坏在哪 / 吞了几个字节 / 解出什么」。 */
    private static R strict(Charset cs, byte... bytes) {
        CharsetDecoder d = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return new R(OK, 0, d.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (MalformedInputException e) {
            return new R(MALFORMED, e.getInputLength(), null);
        } catch (UnmappableCharacterException e) {
            return new R(UNMAPPABLE, e.getInputLength(), null);
        } catch (CharacterCodingException e) {
            return new R(MALFORMED, 1, null);
        }
    }

    /** JDK 的宽松口径 —— 就是 zxing 那边 `new String(bytes, charset)` 干的事。 */
    private static String lenient(Charset cs, byte[] bytes) {
        return new String(bytes, cs);
    }

    // ─────────────────────────────── 一个码表的推导结果 ───────────────────────────────

    private static final char INVALID = '\u0000';   // 结构不合法（吞 1 字节）
    private static final char UNMAPPED = '\uFFFD';  // 结构合法但没有映射（吞 2 字节）

    /**
     * 一个码表。三个名字分得很清：
     *
     *  - [kotlinName]：生成的 Kotlin 里那个 `val` 的标识符（`CP437` / `SHIFT_JIS`…）；
     *  - [jdkName]：就当是枚举来源的那个 `Charset`；
     *  - [eciName]：**二维码里 ECI 标出来的那个名字**（`Cp437` / `Shift_JIS`…）—— 解码那一头就是拿它来查表的。
     */
    private static final class Table {
        final String kotlinName;
        final String jdkName;
        final String eciName;
        final String jdkCharsetName;   // Charset.forName(jdkName).name()，写进注释里
        final int[] singleCps = new int[256];
        final int[] rows = new int[256];
        final int[] cols = new int[256];
        int nCols;
        int nRows;
        char[] flat;
        boolean hasDoubleByte;

        Table(String kotlinName, String jdkName, String eciName) {
            this.kotlinName = kotlinName;
            this.jdkName = jdkName;
            this.eciName = eciName;
            this.jdkCharsetName = Charset.forName(jdkName).name();
            Arrays.fill(singleCps, -1);
            Arrays.fill(rows, -1);
            Arrays.fill(cols, -1);
        }

        /** `(lead, trail)` → 格子值；不在表里一律按「结构不合法」算。 */
        char cell(int lead, int trail) {
            if (rows[lead] < 0 || cols[trail] < 0) return INVALID;
            return flat[rows[lead] * nCols + cols[trail]];
        }
    }

    /**
     * 「这一对字节在 JDK 眼里算不算一个双字节单元」。
     *
     * 算：映射出来了（1 个码位）、或者报 unmappable/2、或者报 malformed/2 —— 后两种在宽松口径下
     * 都是「1 个 U+FFFD、吞 2 字节」，可见行为相同。
     * 不算：报 malformed/1（只吞首字节，尾字节会被当成下一个字节重看）。
     */
    private static boolean usable(R r, int singleCpOfLead) {
        if (r.kind == UNMAPPABLE && r.len == 2) return true;
        if (r.kind == MALFORMED && r.len == 2) return true;
        return r.kind == OK && r.text.codePointCount(0, r.text.length()) == 1 && singleCpOfLead < 0;
    }

    /**
     * 推导一个码表。`excludeTrail` 用于 GB18030：`0x30-0x39` 在它那里是「四字节形态的第二字节」，
     * 不能当成二字节的尾字节去归类（那一档由 `walkGb18030` 单独处理）。
     */
    private static Table derive(String kotlinName, String jdkName, String eciName, Set<Integer> excludeTrail) {
        Charset cs = Charset.forName(jdkName);
        Table t = new Table(kotlinName, jdkName, eciName);

        // 1) 单字节
        for (int b = 0; b < 256; b++) {
            R r = strict(cs, (byte) b);
            if (r.kind == OK && r.text.length() == 1) {
                int cp = r.text.charAt(0);
                if (r.text.length() != r.text.codePointCount(0, r.text.length())) {
                    throw new IllegalStateException(kotlinName + "：单字节 " + hex(b) + " 解出的不是一个码位？");
                }
                t.singleCps[b] = cp;
            }
        }

        // 2) 哪些字节能当首字节
        for (int b1 = 0; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                if (excludeTrail.contains(b2)) continue;
                R r = strict(cs, (byte) b1, (byte) b2);
                boolean lead = usable(r, t.singleCps[b1]);
                if (lead) {
                    if (t.singleCps[b1] >= 0) {
                        throw new IllegalStateException(kotlinName + "：字节 " + hex(b1) + " 既是单字节又是首字节，"
                                + "这一份的数据布局（单字节优先）会让它解错");
                    }
                    t.rows[b1] = t.nRows++;
                    break;
                }
            }
        }

        // 3) 哪些字节能当尾字节（只要对某一个首字节成立就收进来；对别的首字节不成立的那些格子
        //    会在第 4 步记成 INVALID）
        if (t.nRows > 0) {
            for (int b2 = 0; b2 < 256; b2++) {
                if (excludeTrail.contains(b2)) continue;
                for (int b1 = 0; b1 < 256; b1++) {
                    if (t.rows[b1] < 0) continue;
                    R r = strict(cs, (byte) b1, (byte) b2);
                    if (usable(r, -1)) {
                        t.cols[b2] = t.nCols++;
                        break;
                    }
                }
            }

            // 4) 填表
            t.flat = new char[t.nRows * t.nCols];
            Arrays.fill(t.flat, INVALID);
            for (int b1 = 0; b1 < 256; b1++) {
                if (t.rows[b1] < 0) continue;
                for (int b2 = 0; b2 < 256; b2++) {
                    if (t.cols[b2] < 0) continue;
                    R r = strict(cs, (byte) b1, (byte) b2);
                    int idx = t.rows[b1] * t.nCols + t.cols[b2];
                    if (r.kind == OK && r.text.codePointCount(0, r.text.length()) == 1) {
                        int cp = r.text.codePointAt(0);
                        if (cp > 0xFFFF) {
                            throw new IllegalStateException(kotlinName + "：双字节 " + hex(b1) + " " + hex(b2)
                                    + " 映射到补充平面码位 U+" + Integer.toHexString(cp)
                                    + "，当前布局（String 逐格）装不下 —— 得改成逐行 IntArray");
                        }
                        t.flat[idx] = (char) cp;
                    } else if (r.kind == MALFORMED && r.len == 1) {
                        t.flat[idx] = INVALID;
                    } else if (r.kind == UNMAPPABLE && r.len == 2) {
                        t.flat[idx] = UNMAPPED;
                    } else if (r.kind == MALFORMED && r.len == 2) {
                        // 「结构合法但解不出来」在 JDK 那里有两种报法（unmappable 与 malformed/2），
                        // 宽松口径下都是「换成 1 个 U+FFFD、吞 2 字节」⇒ 合成同一格
                        t.flat[idx] = UNMAPPED;
                    } else {
                        throw new IllegalStateException(kotlinName + "：双字节 " + hex(b1) + " " + hex(b2)
                                + " 的归类不是「映射 / 吞 2 字节 / 吞 1 字节」："
                                + r.kind + "/" + r.len + "/" + r.text);
                    }
                }
            }
            t.hasDoubleByte = true;
        }
        return t;
    }

    // ─────────────────────────────── 解码走一遍（与 Kotlin 版逐字对应） ───────────────────────────────

    /** `QrCharset.decodeWithTable` 的 Java 原型（Kotlin 那边是同一套走法）。 */
    private static String walk(Table t, byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            int single = t.singleCps[b];
            if (single >= 0) {
                sb.append((char) single);
                i++;
                continue;
            }
            int row = t.rows[b];
            if (row >= 0 && i + 1 < bytes.length) {
                char v = t.cell(b, bytes[i + 1] & 0xFF);
                if (v == INVALID) {
                    sb.append(UNMAPPED);
                    i++;
                } else if (v == UNMAPPED) {
                    sb.append(UNMAPPED);
                    i += 2;
                } else {
                    sb.append(v);
                    i += 2;
                }
                continue;
            }
            sb.append(UNMAPPED);
            i++;
        }
        return sb.toString();
    }

    /** `QrCharset.decodeGb18030` 的 Java 原型。 */
    private static String walkGb18030(Table two, Ranges four, byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        int n = bytes.length;
        int i = 0;
        while (i < n) {
            int b1 = bytes[i] & 0xFF;
            if (b1 < 0x80) {
                sb.append((char) b1);
                i++;
                continue;
            }
            // 0x80 / 0xFF 不是首字节（JDK 报 malformed/1：1 个 U+FFFD、吞 1 字节）
            if (b1 > 0xFE || two.rows[b1] < 0 || i + 1 >= n) {
                sb.append(UNMAPPED);
                i++;
                continue;
            }
            int b2 = bytes[i + 1] & 0xFF;
            if (b2 >= 0x30 && b2 <= 0x39) {
                // 四字节形态：前缀逐字节校，**吞的字节数就是「校到哪一步」**（实测：
                // [L] 或 [L,0xD] 截断 = 吞 2；第三字节不在 0x81..0xFE = 吞 3；
                // 第四字节不在 0x30..0x39 = 吞 4；指针超出定义范围（≥ 39420）= 吞 4）。
                if (i + 2 >= n) {
                    sb.append(UNMAPPED);
                    i += 2;
                    continue;
                }
                int b3 = bytes[i + 2] & 0xFF;
                if (b3 < 0x81 || b3 > 0xFE) {
                    sb.append(UNMAPPED);
                    i += 3;
                    continue;
                }
                if (i + 3 >= n) {
                    sb.append(UNMAPPED);
                    i += 3;
                    continue;
                }
                int b4 = bytes[i + 3] & 0xFF;
                if (b4 < 0x30 || b4 > 0x39) {
                    sb.append(UNMAPPED);
                    i += 4;
                    continue;
                }
                int pointer = ((b1 - 0x81) * 10 + (b2 - 0x30)) * 1260 + (b3 - 0x81) * 10 + (b4 - 0x30);
                int cp = four.lookup(pointer);
                if (cp >= 0) {
                    appendCp(sb, cp);
                } else {
                    sb.append(UNMAPPED);
                }
                i += 4;
                continue;
            }
            char v = two.cell(b1, b2);
            if (v == INVALID) {
                sb.append(UNMAPPED);
                i++;
            } else if (v == UNMAPPED) {
                sb.append(UNMAPPED);
                i += 2;
            } else {
                sb.append(v);
                i += 2;
            }
        }
        return sb.toString();
    }

    private static void appendCp(StringBuilder sb, int cp) {
        if (cp <= 0xFFFF) {
            sb.append((char) cp);
        } else {
            int v = cp - 0x10000;
            sb.append((char) (0xD800 + (v >> 10)));
            sb.append((char) (0xDC00 + (v & 0x3FF)));
        }
    }

    // ─────────────────────────────── GB18030 的四字节形态：指针 → 码位 ───────────────────────────────

    private static final class Ranges {
        final int[] starts;
        final int[] ends;
        final int[] cps;

        Ranges(int[] starts, int[] ends, int[] cps) {
            this.starts = starts;
            this.ends = ends;
            this.cps = cps;
        }

        int lookup(int pointer) {
            int lo = 0;
            int hi = starts.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (pointer < starts[mid]) {
                    hi = mid - 1;
                } else if (pointer > ends[mid]) {
                    lo = mid + 1;
                } else {
                    return cps[mid] + (pointer - starts[mid]);
                }
            }
            return -1;
        }
    }

    /** 把 JDK 的四字节映射按「码位 - 指针 = 常数」切成段（同一段里指针每 +1、码位 +1）。 */
    private static Ranges deriveGb18030Four(Charset cs) {
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<Integer> cps = new ArrayList<>();
        int segStart = -1;
        int segEnd = -1;
        int segDelta = 0;
        int mapped = 0;
        for (int b1 = 0x81; b1 <= 0xFE; b1++) {
            for (int b2 = 0x30; b2 <= 0x39; b2++) {
                for (int b3 = 0x81; b3 <= 0xFE; b3++) {
                    for (int b4 = 0x30; b4 <= 0x39; b4++) {
                        int pointer = ((b1 - 0x81) * 10 + (b2 - 0x30)) * 1260 + (b3 - 0x81) * 10 + (b4 - 0x30);
                        R r = strict(cs, (byte) b1, (byte) b2, (byte) b3, (byte) b4);
                        if (r.kind != OK) {
                            if (r.kind == MALFORMED) {
                                if (r.len != 4) {
                                    throw new IllegalStateException("GB18030 四字节 " + hex(b1) + hex(b2) + hex(b3)
                                            + hex(b4) + " 这个形态居然是 malformed/" + r.len + "（不是 4）");
                                }
                                // 长度 4 的 malformed = 指针超出了 GB18030 定义的范围（≥ 39420）
                                // ⇒ 没映射，落在下面那句「断开这一段」上；解码时替换成一个 U+FFFD 并吞 4 字节
                            }
                            // 没映射 → 断开这一段；**必须先把手上的那一段收进列表**（不然后半段就丢了）
                            if (segStart >= 0) {
                                starts.add(segStart);
                                ends.add(segEnd);
                                cps.add(segStart + segDelta);
                                segStart = -1;
                            }
                            continue;
                        }
                        int cp = r.text.codePointAt(0);
                        if (r.text.codePointCount(0, r.text.length()) != 1) {
                            throw new IllegalStateException("GB18030 四字节 " + hex(b1) + hex(b2) + hex(b3)
                                    + hex(b4) + " 解出多个码位");
                        }
                        mapped++;
                        int delta = cp - pointer;
                        if (segStart >= 0 && pointer == segEnd + 1 && delta == segDelta) {
                            segEnd = pointer;
                        } else {
                            if (segStart >= 0) {
                                starts.add(segStart);
                                ends.add(segEnd);
                                cps.add(segStart + segDelta);
                            }
                            segStart = pointer;
                            segEnd = pointer;
                            segDelta = delta;
                        }
                    }
                }
            }
        }
        if (segStart >= 0) {
            starts.add(segStart);
            ends.add(segEnd);
            cps.add(segStart + segDelta);
        }
        System.out.printf("  GB18030 四字节：映射 %d 个指针、切成 %d 段%n", mapped, starts.size());
        return new Ranges(toIntArray(starts), toIntArray(ends), toIntArray(cps));
    }

    // ─────────────────────────────── 自检（穷举） ───────────────────────────────

    private static long mismatches;

    private static void check(Table t, Charset cs, byte[] bytes, String label) {
        String want = lenient(cs, bytes);
        String got = walk(t, bytes);
        if (!want.equals(got)) {
            mismatches++;
            if (mismatches <= 20) {
                System.out.println("    ✗ " + label + " " + hexBytes(bytes)
                        + "：JDK=" + show(want) + " 本表=" + show(got));
            }
        }
    }

    private static void verifyTable(Table t) {
        Charset cs = Charset.forName(t.jdkName);
        long before = mismatches;
        long n = 0;

        for (int b = 0; b < 256; b++) {
            check(t, cs, new byte[]{(byte) b}, "单字节");
            n++;
        }
        for (int b1 = 0; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                check(t, cs, new byte[]{(byte) b1, (byte) b2}, "字节对");
                n++;
            }
        }
        int[] thirds = {0x40, 0x81, 0xA1, 0xFF};
        for (int b1 = 0; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                for (int b3 : thirds) {
                    check(t, cs, new byte[]{(byte) b1, (byte) b2, (byte) b3}, "三字节");
                    n++;
                }
            }
        }
        System.out.printf("  %-12s（JDK %s）：%d 种输入、%d 处与 JDK 不同%n",
                t.kotlinName, t.jdkCharsetName, n, mismatches - before);
    }

    private static void verifyGb18030(Table two, Ranges four) {
        Charset cs = Charset.forName("GB18030");
        long before = mismatches;
        long n = 0;

        for (int b = 0; b < 256; b++) {
            checkGb18030(two, four, cs, new byte[]{(byte) b});
            n++;
        }
        for (int b1 = 0; b1 < 256; b1++) {
            for (int b2 = 0; b2 < 256; b2++) {
                checkGb18030(two, four, cs, new byte[]{(byte) b1, (byte) b2});
                n++;
            }
        }
        for (int b1 = 0x81; b1 <= 0xFE; b1++) {
            for (int b2 = 0x30; b2 <= 0x39; b2++) {
                for (int b3 : new int[]{0x81, 0x80, 0x40, 0xFF, 0x30}) {
                    checkGb18030(two, four, cs, new byte[]{(byte) b1, (byte) b2, (byte) b3});
                    n++;
                }
            }
        }
        for (int b1 = 0x81; b1 <= 0xFE; b1++) {
            for (int b2 = 0x30; b2 <= 0x39; b2++) {
                for (int b3 = 0x81; b3 <= 0xFE; b3++) {
                    for (int b4 = 0x30; b4 <= 0x39; b4++) {
                        checkGb18030(two, four, cs, new byte[]{(byte) b1, (byte) b2, (byte) b3, (byte) b4});
                        n++;
                    }
                }
            }
        }
        System.out.printf("  %-12s（JDK %s）：%d 种输入、%d 处与 JDK 不同%n",
                "GB18030", cs.name(), n, mismatches - before);
    }

    private static void checkGb18030(Table two, Ranges four, Charset cs, byte[] bytes) {
        String want = lenient(cs, bytes);
        String got = walkGb18030(two, four, bytes);
        if (!want.equals(got)) {
            mismatches++;
            if (mismatches <= 40) {
                String extra = "";
                if (bytes.length == 4) {
                    int b1 = bytes[0] & 0xFF, b2 = bytes[1] & 0xFF, b3 = bytes[2] & 0xFF, b4 = bytes[3] & 0xFF;
                    int pointer = ((b1 - 0x81) * 10 + (b2 - 0x30)) * 1260 + (b3 - 0x81) * 10 + (b4 - 0x30);
                    extra = "［ptr=" + pointer + " lookup=" + four.lookup(pointer) + " 段数="
                            + four.starts.length + "］";
                }
                System.out.println("    ✗ GB18030 " + hexBytes(bytes) + extra
                        + "：JDK=" + show(want) + " 本表=" + show(got));
            }
        }
    }

    // ─────────────────────────────── 生成 Kotlin ───────────────────────────────

    private static final int CHUNK = 3000;

    private static final String HEADER = String.join("\n",
            "package com.xjtu.toolbox.platform.qr",
            "",
            "// ─────────────────────────────────────────────────────────────────────────────",
            "// 这个文件是**生成的**，别手改：",
            "//     cd <仓库根> && java tools/gen-qr-charset-tables.java",
            "// 生成器：tools/gen-qr-charset-tables.java（单文件源码模式；本机 JDK {JDKVERSION}）",
            "//",
            "// 数据来源与许可：",
            "//  - 这一份码表是**枚举本机 JDK 的 java.nio.charset 解码器**得到的（逐个字节 / 逐个字节对，",
            "//    以及 GB18030 的四字节形态逐个指针），**没有拷贝 JDK 的任何文件**（没有拷它的源码，",
            "//    也没有拷它的数据文件）；字节↔码位的对应关系本身来自 JIS X 0208 / GB2312 / Big5 /",
            "//    KS X 1001 / Unicode 这些标准。",
            "//  - 也没有拷贝 zxing 的那份码表（com.google.zxing.common.CharacterSetECI 那一族是",
            "//    Apache-2.0）—— 这个文件里一行 zxing 的东西都没有，只有从 JDK 枚举出来的映射。",
            "//  - 为什么要从 JDK 生成：zxing 在 JVM 上就是拿 java.nio.charset.Charset 解的，判据是",
            "//    「与 zxing 逐字相同」，只有同一份 JDK 生成出来的表才可能真的逐字相同。",
            "//",
            "// 怎么解：见 QrCharset.kt 里手写的 QrCharsetTable / QrGb18030Four（数据布局写在生成器头上）。",
            "//",
            "// 确定性：输入只有本机 JDK 的 Charset 实现，遍历顺序全写死，输出里唯一与环境有关的",
            "// 只有上面那句 JDK 版本 ⇒ 同一个 JDK 上跑两次，md5 一样（生成器自己也会穷举自检）。",
            "// ─────────────────────────────────────────────────────────────────────────────",
            "",
            "internal object QrCjkTables {",
            "");

    private static void emit(StringBuilder sb, Map<String, Table> tables, Ranges four) {
        for (Table t : tables.values()) {
            sb.append("    /** ").append(t.kotlinName).append(" —— JDK `").append(t.jdkCharsetName).append("`");
            if (t.hasDoubleByte) {
                sb.append("（").append(t.nRows).append(" 个首字节 × ").append(t.nCols).append(" 个尾字节）");
            } else {
                sb.append("（单字节集）");
            }
            sb.append("。 */\n");
            if (!t.hasDoubleByte) {
                sb.append("    internal val ").append(t.kotlinName)
                        .append(": QrCharsetTable = QrCharsetTable.single(\n");
                emitIntArray(sb, "        singleCps = ", t.singleCps, "    )\n");
            } else {
                sb.append("    internal val ").append(t.kotlinName).append(": QrCharsetTable = QrCharsetTable.double(\n");
                emitIntArray(sb, "        singleCps = ", t.singleCps, null);
                emitIntArray(sb, "        rows = ", t.rows, null);
                emitIntArray(sb, "        cols = ", t.cols, null);
                sb.append("        nCols = ").append(t.nCols).append(",\n");
                sb.append("        flat =\n");
                for (int off = 0; off < t.flat.length; off += CHUNK) {
                    int len = Math.min(CHUNK, t.flat.length - off);
                    sb.append("            \"").append(escape(t.flat, off, len)).append('"');
                    sb.append(off + len < t.flat.length ? " +\n" : ",\n");
                }
                sb.append("    )\n");
            }
            sb.append("\n");
        }

        sb.append("    /** 二维码里 ECI 标出来的那个名字 → 码表（解码那一头就是拿它查的）。 */\n");
        sb.append("    internal val TABLES: Map<String, QrCharsetTable> = mapOf(\n");
        for (Table t : tables.values()) {
            sb.append("        \"").append(t.eciName).append("\" to ").append(t.kotlinName).append(",\n");
        }
        sb.append("    )\n\n");

        sb.append("    /** GB18030 的四字节形态：指针 → 码位（同一段里指针 +1、码位 +1）。 */\n");
        sb.append("    internal val GB18030_FOUR: QrGb18030Four = QrGb18030Four(\n");
        emitIntArray(sb, "        starts = ", four.starts, null);
        emitIntArray(sb, "        ends = ", four.ends, null);
        emitIntArray(sb, "        cps = ", four.cps, "    )\n");
        sb.append("}\n");
    }

    private static void emitIntArray(StringBuilder sb, String prefix, int[] values, String trailer) {
        sb.append(prefix).append("intArrayOf(\n");
        for (int i = 0; i < values.length; i += 16) {
            sb.append("            ");
            for (int j = i; j < Math.min(i + 16, values.length); j++) {
                sb.append(values[j]);
                if (j + 1 < Math.min(i + 16, values.length)) sb.append(", ");
            }
            sb.append(",\n");
        }
        sb.append("        )");
        sb.append(trailer == null ? ",\n" : "\n");
        if (trailer != null) sb.append(trailer);
    }

    private static String escape(char[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            char c = data[i];
            if (c == '\\' || c == '"' || c == '$') {
                sb.append('\\').append(c);
            } else if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else if (c >= 0xA1 && c <= 0xFFFF && c != 0xFFFD && c != 0x2028 && c != 0x2029) {
                sb.append(c);   // 中日韩字符按原样写进 UTF-8 源码
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    // ─────────────────────────────── 小工具 ───────────────────────────────

    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static String hex(int b) {
        return String.format("%02X", b & 0xFF);
    }

    private static String hexBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(hex(b)).append(' ');
        return sb.toString().trim();
    }

    private static String show(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("U+%04X ", (int) s.charAt(i)));
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────── main ───────────────────────────────

    public static void main(String[] args) throws IOException {
        Path out = Paths.get("core/src/commonMain/kotlin/com/xjtu/toolbox/platform/qr/QrCjkTables.kt");
        if (!Files.isDirectory(out.getParent())) {
            throw new IllegalStateException("请在仓库根目录下跑（找不到 " + out.getParent() + "）");
        }

        // 单字节那一族：ECI 表里出现过、而且本机 JDK 有实现的那几个。
        // 注意 ISO-8859-10 / ISO-8859-14 本机 JDK 没有实现（Charset.isSupported=false），生成不出来。
        String[][] singles = {
                {"CP437", "IBM437", "Cp437"},
                {"ISO_8859_2", "ISO-8859-2", "ISO-8859-2"},
                {"ISO_8859_3", "ISO-8859-3", "ISO-8859-3"},
                {"ISO_8859_4", "ISO-8859-4", "ISO-8859-4"},
                {"ISO_8859_5", "ISO-8859-5", "ISO-8859-5"},
                {"ISO_8859_6", "ISO-8859-6", "ISO-8859-6"},
                {"ISO_8859_7", "ISO-8859-7", "ISO-8859-7"},
                {"ISO_8859_8", "ISO-8859-8", "ISO-8859-8"},
                {"ISO_8859_9", "ISO-8859-9", "ISO-8859-9"},
                {"ISO_8859_11", "ISO-8859-11", "ISO-8859-11"},
                {"ISO_8859_13", "ISO-8859-13", "ISO-8859-13"},
                {"ISO_8859_15", "ISO-8859-15", "ISO-8859-15"},
                {"ISO_8859_16", "ISO-8859-16", "ISO-8859-16"},
                {"WINDOWS_1250", "windows-1250", "windows-1250"},
                {"WINDOWS_1251", "windows-1251", "windows-1251"},
                {"WINDOWS_1252", "windows-1252", "windows-1252"},
                {"WINDOWS_1256", "windows-1256", "windows-1256"},
        };
        // 双字节那一族：Shift_JIS / GB2312 / Big5 / EUC-KR / GB18030
        String[][] doubles = {
                {"SHIFT_JIS", "Shift_JIS", "Shift_JIS"},
                {"GB2312", "GB2312", "GB2312"},
                {"BIG5", "Big5", "Big5"},
                {"EUC_KR", "EUC-KR", "EUC-KR"},
                {"GB18030", "GB18030", "GB18030"},
        };
        Set<Integer> gb18030SecondByte = new LinkedHashSet<>();
        for (int b = 0x30; b <= 0x39; b++) gb18030SecondByte.add(b);

        Map<String, Table> tables = new LinkedHashMap<>();
        System.out.println("推导码表……");
        for (String[] s : singles) {
            Table t = derive(s[0], s[1], s[2], Collections.emptySet());
            tables.put(s[2], t);
            System.out.printf("  %-14s（JDK %-14s / ECI %-13s）单字节 %d 个%n", s[0], t.jdkCharsetName, t.eciName,
                    countMapped(t.singleCps));
        }
        for (String[] s : doubles) {
            Set<Integer> excl = s[2].equals("GB18030") ? gb18030SecondByte : Collections.emptySet();
            Table t = derive(s[0], s[1], s[2], excl);
            tables.put(s[2], t);
            System.out.printf("  %-14s（JDK %-14s / ECI %-13s）单字节 %d 个、%d 首字节 × %d 尾字节、表 %d 格%n",
                    s[0], t.jdkCharsetName, t.eciName, countMapped(t.singleCps), t.nRows, t.nCols, t.flat.length);
        }

        Ranges four = deriveGb18030Four(Charset.forName("GB18030"));

        System.out.println("穷举自检（与 JDK 的 new String(bytes, charset) 逐字对照）……");
        for (Table t : tables.values()) {
            if (t.kotlinName.equals("GB18030")) continue;
            verifyTable(t);
        }
        verifyGb18030(tables.get("GB18030"), four);
        if (mismatches != 0) {
            throw new IllegalStateException("自检没过：与 JDK 有 " + mismatches + " 处不同，不写文件");
        }
        System.out.println("自检全过：每一段输入都与 JDK 逐字相同。");

        StringBuilder sb = new StringBuilder();
        sb.append(HEADER.replace("{JDKVERSION}",
                System.getProperty("java.version") + " / " + System.getProperty("java.vm.name")));
        emit(sb, tables, four);
        Files.write(out, sb.toString().getBytes("UTF-8"));
        System.out.println("写出 " + out + "：" + sb.length() + " 字符");
    }

    private static int countMapped(int[] a) {
        int n = 0;
        for (int v : a) if (v >= 0) n++;
        return n;
    }
}
