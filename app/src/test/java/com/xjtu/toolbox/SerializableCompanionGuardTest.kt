package com.xjtu.toolbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `@Serializable` 类的伴生对象不能是 private / internal / protected：序列化插件把 serializer() 放进伴生对象，
 * 别的类要跨类读它；正式版经 R8 内联后变成直接读私有字段，运行时 IllegalAccessError（5.0.8 崩过）。
 * 调试版不开 R8 测不出来，所以在这里扫源码拦住。
 */
class SerializableCompanionGuardTest {

    private val restricted = Regex("""\b(private|internal|protected)\s+companion\s+object\b""")

    @Test
    fun `Serializable 类不许有受限的伴生对象`() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> findOffenders(f.readText()).map { "${f.path}:$it" } }
            .toList()
        assertTrue("这些 @Serializable 类的伴生对象不是 public：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** 返回违规伴生对象所在行号。按花括号建出每个块的「头部文本」，伴生对象的上一层块头带 @Serializable 即违规。 */
    private fun findOffenders(src: String): List<Int> {
        val code = stripStringsAndComments(src)
        val headers = ArrayDeque<String>()
        val result = mutableListOf<Int>()
        var headerStart = 0
        for (i in code.indices) {
            when (code[i]) {
                '{' -> {
                    val header = code.substring(headerStart, i)
                    if (restricted.containsMatchIn(header) && headers.lastOrNull()?.let(::isSerializableDecl) == true) {
                        result += code.substring(0, i).count { it == '\n' } + 1
                    }
                    headers.addLast(header)
                    headerStart = i + 1
                }
                '}' -> {
                    headers.removeLastOrNull()
                    headerStart = i + 1
                }
                ';' -> headerStart = i + 1
            }
        }
        return result
    }

    private val declKeyword = Regex("""\b(class|interface|object)\b""")

    /** 块头里最后一个声明是否带 @Serializable：只看同一行和紧挨在上面的注解行，别把前一个声明的注解算进来。 */
    private fun isSerializableDecl(header: String): Boolean {
        val k = declKeyword.findAll(header).lastOrNull()?.range?.first ?: return false
        val lines = header.substring(0, k).lines()
        val annotations = StringBuilder(lines.last())
        for (line in lines.dropLast(1).asReversed()) {
            if (!line.trim().startsWith("@")) break
            annotations.append(' ').append(line)
        }
        return serializableAnnotation.containsMatchIn(annotations)
    }

    private val serializableAnnotation = Regex("""@(kotlinx\.serialization\.)?Serializable\b""")

    /** 把字符串和注释替换成空格（保留换行），免得里面的花括号干扰计数。 */
    private fun stripStringsAndComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        fun blank(until: Int) {
            while (i < until && i < src.length) {
                out.append(if (src[i] == '\n') '\n' else ' ')
                i++
            }
        }
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> blank(src.indexOf('\n', i).let { if (it < 0) src.length else it })
                src.startsWith("/*", i) -> blank(src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 })
                src.startsWith("\"\"\"", i) -> blank(src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 })
                src[i] == '"' -> blank(stringEnd(src, i))
                src[i] == '\'' -> blank(src.indexOf('\'', if (src.getOrNull(i + 1) == '\\') i + 3 else i + 2).let { if (it < 0) src.length else it + 1 })
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    /** 普通字符串的结束位置（含结尾引号），跳过转义和 `${…}` 模板里嵌套的字符串。 */
    private fun stringEnd(src: String, start: Int): Int {
        var i = start + 1
        while (i < src.length) {
            when {
                src[i] == '\\' -> i += 2
                src.startsWith("\${", i) -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        when (src[i]) {
                            '{' -> depth++
                            '}' -> depth--
                            '"' -> { i = stringEnd(src, i) - 1 }
                        }
                        i++
                    }
                }
                src[i] == '"' -> return i + 1
                else -> i++
            }
        }
        return src.length
    }
}
