package com.xjtu.toolbox.agent

/**
 * 把模型写出来的简单 TeX 公式转成能直接显示的 Unicode 文字。
 *
 * 不做真正的排版（没有分数线、根号盖线），只求「读得懂」：`\frac{a}{b}` → a/b、
 * `x^2` → x²、`\sum` → Σ、`\le` → ≤。屁岱的公式基本是 GPA、均分这类算式，这个程度够用；
 * 引一个完整的 LaTeX 渲染库（JLaTeXMath / KaTeX WebView）换来的是几 MB 体积和一套 WebView，不值。
 * 认不出来的命令原样留着，至少不吞内容。
 */
object TexLite {

    fun toUnicode(tex: String): String {
        var s = tex.trim()
        // 从里往外一层层消掉花括号结构，最多十轮，防止怪输入死循环
        repeat(10) {
            val before = s
            s = textCmdRe.replace(s) { it.groupValues[2] }
            // 根号下多于一个字符一律加括号：√2x 会被读成「√2 乘 x」
            s = sqrtRe.replace(s) { m -> m.groupValues[1].trim().let { if (it.length <= 1) "√$it" else "√($it)" } }
            s = fracRe.replace(s) { m -> wrap(m.groupValues[1]) + "/" + wrap(m.groupValues[2]) }
            s = supGroupRe.replace(s) { m -> script(m.groupValues[1], SUP, "^") }
            s = subGroupRe.replace(s) { m -> script(m.groupValues[1], SUB, "_") }
            if (s == before) return@repeat
        }
        s = s.replace("^\\circ", "°").replace("^{\\circ}", "°")
        SYMBOLS.forEach { (k, v) -> s = s.replace(Regex(Regex.escape(k) + "(?![A-Za-z])"), Regex.escapeReplacement(v)) }
        s = supCharRe.replace(s) { m -> script(m.groupValues[1], SUP, "^") }
        s = subCharRe.replace(s) { m -> script(m.groupValues[1], SUB, "_") }
        s = s.replace("\\{", "{").replace("\\}", "}").replace("{", "").replace("}", "")
        return s.replace(Regex(" {2,}"), " ").trim()
    }

    /** 单个记号不加括号，复合式加括号：a/b，(a+b)/(c+d)。 */
    private fun wrap(x: String): String {
        val t = x.trim()
        return if (t.length <= 1 || t.all { it.isLetterOrDigit() || it == '.' }) t else "($t)"
    }

    /** 能全部换成上 / 下标字符就换，换不了就老实写成 ^(…) / _(…)。 */
    private fun script(x: String, table: Map<Char, Char>, mark: String): String {
        val t = x.trim()
        return if (t.isNotEmpty() && t.all { it in table }) t.map { table.getValue(it) }.joinToString("")
        else "$mark(${t})"
    }

    private val textCmdRe = Regex("""\\(text|mathrm|mathbf|mathit|operatorname|textbf|boldsymbol)\{([^{}]*)\}""")
    private val sqrtRe = Regex("""\\sqrt\{([^{}]*)\}""")
    private val fracRe = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""")
    private val supGroupRe = Regex("""\^\{([^{}]*)\}""")
    private val subGroupRe = Regex("""_\{([^{}]*)\}""")
    // 单字符上下标不认括号：换不了上标时退回的 ^(…) 已经带括号，不能再被转一次
    private val supCharRe = Regex("""\^([A-Za-z0-9+\-=])""")
    private val subCharRe = Regex("""_([A-Za-z0-9+\-=])""")

    private val SUP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'k' to 'ᵏ', 'T' to 'ᵀ',
    )
    private val SUB = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ',
        'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )

    /** 长的放前面：\leq 要先于 \le 匹配（其实有后行断言兜着，这里再保险一层）。 */
    private val SYMBOLS = listOf(
        "\\left" to "", "\\right" to "", "\\displaystyle" to "",
        "\\quad" to "  ", "\\qquad" to "  ", "\\," to " ", "\\;" to " ", "\\:" to " ", "\\!" to "",
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\rightarrow" to "→", "\\leftarrow" to "←", "\\to" to "→", "\\mapsto" to "↦",
        "\\sum" to "Σ", "\\prod" to "Π", "\\int" to "∫", "\\oint" to "∮", "\\infty" to "∞",
        "\\partial" to "∂", "\\nabla" to "∇", "\\forall" to "∀", "\\exists" to "∃",
        "\\in" to "∈", "\\notin" to "∉", "\\subseteq" to "⊆", "\\subset" to "⊂", "\\cup" to "∪", "\\cap" to "∩",
        "\\emptyset" to "∅", "\\land" to "∧", "\\lor" to "∨", "\\neg" to "¬",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…", "\\degree" to "°", "\\circ" to "∘", "\\%" to "%",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\lambda" to "λ",
        "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ",
        "\\tau" to "τ", "\\phi" to "φ", "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Pi" to "Π",
        "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
    ).sortedByDescending { it.first.length }
}
