package com.xjtu.toolbox.html.dom

/**
 * 纯 Kotlin 的宽容 HTML 小 DOM —— 只为 [com.xjtu.toolbox.html.parser.HtmlParser] 服务。
 *
 * dev 上的解析器建在 jsoup 上，但 jsoup 只有 JVM 版，`feature:lms` 的 commonMain 还要跑在 wasm 上，
 * 所以这里按 jsoup 的几条可见行为自己做一份最小实现：
 * - [HtmlText.text] 把空白（含 `&nbsp;`）折成一个空格，不裁两端（同 jsoup `TextNode.text()`）；
 * - [HtmlElement.wholeText] 原样拼接后代文本，`<br>` 记作换行（同 jsoup `Element.wholeText()`）；
 * - `html` / `head` / `body` 外壳不出现在树里，`head`、`script`、`style`、`title`、注释、doctype 整段跳过；
 * - 遇到块级开标签时自动闭合未闭合的 `<p>`，新的 `<li>` 闭合上一个 `<li>`；`<pre>` 开头紧跟的换行丢掉；
 * - 实体按名字表 + 十进制 / 十六进制数字引用解码，认不出的原样保留。
 */
internal sealed class HtmlNode

internal class HtmlText(val wholeText: String) : HtmlNode() {
    fun text(): String = WHITESPACE.replace(wholeText, " ")

    private companion object {
        val WHITESPACE = Regex("[ \\t\\n\\r\\u000C\\u00A0]+")
    }
}

internal class HtmlElement(
    /** 小写标签名（同 jsoup `normalName()`）。 */
    val tag: String,
    private val attributes: Map<String, String> = emptyMap(),
) : HtmlNode() {
    internal val nodes: MutableList<HtmlNode> = mutableListOf()

    fun childNodes(): List<HtmlNode> = nodes

    fun normalName(): String = tag
    fun attr(name: String): String = attributes[name.lowercase()].orEmpty()
    fun children(): List<HtmlElement> = nodes.filterIsInstance<HtmlElement>()

    /** 深度优先找第一个标签名为 [tag] 的元素（含自身，同 jsoup `selectFirst`）。 */
    fun selectFirst(tag: String): HtmlElement? {
        if (this.tag == tag) return this
        for (child in nodes) {
            if (child is HtmlElement) child.selectFirst(tag)?.let { return it }
        }
        return null
    }

    fun wholeText(): String = buildString { appendWhole(this@HtmlElement) }

    private fun StringBuilder.appendWhole(el: HtmlElement) {
        for (child in el.nodes) {
            when (child) {
                is HtmlText -> append(child.wholeText)
                is HtmlElement -> if (child.tag == "br") append('\n') else appendWhole(child)
            }
        }
    }
}

internal object HtmlDom {

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr",
    )

    /** 内容整段丢弃的元素。 */
    private val SKIP_TAGS = setOf("head", "script", "style", "title", "template", "noscript")

    /** 不进树的外壳。 */
    private val SHELL_TAGS = setOf("html", "body")

    /** 开标签时会自动闭合未闭合 `<p>` 的块级元素（HTML 规范的那张表里常见的部分）。 */
    private val CLOSES_P = setOf(
        "address", "article", "aside", "blockquote", "div", "dl", "fieldset", "figure", "footer",
        "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav", "ol", "p",
        "pre", "section", "table", "ul",
    )

    /** 解析成一个虚拟 `body` 元素。 */
    fun parseBody(html: String): HtmlElement {
        val root = HtmlElement("body")
        val stack = ArrayList<HtmlElement>().apply { add(root) }
        var i = 0
        val n = html.length
        val text = StringBuilder()

        fun flushText() {
            if (text.isEmpty()) return
            val parent = stack.last()
            var raw = decodeEntities(text.toString())
            text.clear()
            if (parent.tag == "pre" && parent.nodes.isEmpty() && raw.startsWith("\n")) raw = raw.substring(1)
            if (raw.isNotEmpty()) parent.nodes += HtmlText(raw)
        }

        fun closeTo(tag: String): Boolean {
            val idx = stack.indexOfLast { it.tag == tag }
            if (idx <= 0) return false
            while (stack.size > idx) stack.removeAt(stack.size - 1)
            return true
        }

        while (i < n) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }

            // 注释 / doctype / CDATA / 处理指令
            if (html.startsWith("<!--", i)) {
                flushText()
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                flushText()
                val end = html.indexOf('>', i)
                i = if (end < 0) n else end + 1
                continue
            }

            val closing = i + 1 < n && html[i + 1] == '/'
            val nameStart = if (closing) i + 2 else i + 1
            if (nameStart >= n || !(html[nameStart] in 'a'..'z' || html[nameStart] in 'A'..'Z')) {
                // 不是标签（比如 "a < b"），当普通文字
                text.append(c); i++; continue
            }
            var j = nameStart
            while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
            val tag = html.substring(nameStart, j).lowercase()
            val tagEnd = findTagEnd(html, j)
            val attrText = html.substring(j, (tagEnd).coerceAtMost(n))
            i = if (tagEnd >= n) n else tagEnd + 1
            flushText()

            if (closing) {
                when {
                    tag in SHELL_TAGS || tag in SKIP_TAGS -> Unit
                    tag == "br" -> stack.last().nodes += HtmlElement("br")
                    tag == "p" && !closeTo("p") -> stack.last().nodes += HtmlElement("p")
                    else -> closeTo(tag)
                }
                continue
            }

            if (tag in SKIP_TAGS) {
                val endIdx = html.indexOf("</$tag", i, ignoreCase = true)
                i = if (endIdx < 0) n else (html.indexOf('>', endIdx).let { if (it < 0) n else it + 1 })
                continue
            }
            if (tag in SHELL_TAGS) continue

            if (tag in CLOSES_P && stack.any { it.tag == "p" }) closeTo("p")
            if (tag == "li") {
                val li = stack.indexOfLast { it.tag == "li" }
                val list = stack.indexOfLast { it.tag == "ul" || it.tag == "ol" }
                if (li > 0 && li > list) closeTo("li")
            }

            val el = HtmlElement(tag, parseAttributes(attrText))
            stack.last().nodes += el
            val selfClosing = attrText.trimEnd().endsWith("/")
            if (tag !in VOID_TAGS && !selfClosing) stack += el
        }
        flushText()
        return root
    }

    /** 从 [from] 起找标签的 `>`，跳过引号里的内容。 */
    private fun findTagEnd(html: String, from: Int): Int {
        var k = from
        var quote: Char? = null
        while (k < html.length) {
            val ch = html[k]
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '>') {
                return k
            }
            k++
        }
        return html.length
    }

    private fun parseAttributes(src: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var k = 0
        val n = src.length
        while (k < n) {
            while (k < n && (src[k].isWhitespace() || src[k] == '/')) k++
            if (k >= n) break
            val start = k
            while (k < n && !src[k].isWhitespace() && src[k] != '=' && src[k] != '/' && src[k] != '>') k++
            val name = src.substring(start, k).lowercase()
            while (k < n && src[k].isWhitespace()) k++
            var value = ""
            if (k < n && src[k] == '=') {
                k++
                while (k < n && src[k].isWhitespace()) k++
                if (k < n && (src[k] == '"' || src[k] == '\'')) {
                    val q = src[k]
                    val end = src.indexOf(q, k + 1).let { if (it < 0) n else it }
                    value = src.substring(k + 1, end)
                    k = (end + 1).coerceAtMost(n)
                } else {
                    val vs = k
                    while (k < n && !src[k].isWhitespace()) k++
                    value = src.substring(vs, k)
                }
            }
            if (name.isNotEmpty() && name !in out) out[name] = decodeEntities(value)
            if (k == start) k++ // 防御：没有前进就跳过一个字符
        }
        return out
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "middot" to "\u00B7", "bull" to "\u2022", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "times" to "\u00D7", "divide" to "\u00F7", "deg" to "\u00B0", "plusmn" to "\u00B1", "laquo" to "\u00AB",
        "raquo" to "\u00BB", "yen" to "\u00A5", "rarr" to "\u2192", "larr" to "\u2190", "le" to "\u2264", "ge" to "\u2265",
    )

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});?")

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull()) ?: m.value
                else -> NAMED_ENTITIES[body] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int?): String? {
        if (cp == null || cp <= 0 || cp > 0x10FFFF) return null
        return if (cp < 0x10000) {
            cp.toChar().toString()
        } else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}