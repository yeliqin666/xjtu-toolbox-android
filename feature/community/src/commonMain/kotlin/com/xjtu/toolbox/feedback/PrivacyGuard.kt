package com.xjtu.toolbox.feedback

/**
 * 发送前的隐私检查：本机已知的账号、姓名按原值匹配，手机号 / 身份证号 / 邮箱按格式匹配，
 * 命中的部分换成「[标签]」。反馈正文会原样公开到 GitHub。
 *
 * 宁可漏也别错：用户可能贴日志，数字串千奇百怪。身份证要过校验位和生日，
 * 手机号和数字账号两侧不能紧挨字母数字或点，崩溃日志不走这里。
 */
internal object PrivacyGuard {

    data class Result(val text: String, val found: List<String>)

    private const val EDGE_L = """(?<![0-9A-Za-z_.])"""
    private const val EDGE_R = """(?![0-9A-Za-z_.])"""
    private val ID_CARD = Regex("""$EDGE_L\d{17}[0-9Xx]$EDGE_R""")
    private val PHONE = Regex("""$EDGE_L(?:\+?86[- ]?)?1[3-9]\d{9}$EDGE_R""")
    private val EMAIL = Regex("""$EDGE_L[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$EDGE_R""")

    /** [known]：标签 to 原值，来自 [FeedbackSource.privateTerms]。 */
    fun scrub(text: String, known: List<Pair<String, String>>): Result {
        var out = text
        val found = LinkedHashSet<String>()
        fun replace(label: String, re: Regex, accept: (String) -> Boolean = { true }) {
            var hit = false
            out = re.replace(out) { m -> if (accept(m.value)) { hit = true; "[$label]" } else m.value }
            if (hit) found += label
        }
        // 长的先换，免得短值把长值拆坏
        known.map { it.first to it.second.trim() }
            .filter { it.second.length >= 2 }
            .sortedByDescending { it.second.length }
            .forEach { (label, v) ->
                val body = Regex.escape(v)
                // 纯数字 / 字母数字的账号要整串命中，别把日志里更长的数字切出一截
                val re = if (v.all { it.isLetterOrDigit() && it.code < 128 }) {
                    Regex("$EDGE_L$body$EDGE_R", RegexOption.IGNORE_CASE)
                } else {
                    Regex(body)
                }
                replace(label, re)
            }
        replace("身份证号", ID_CARD, ::isIdCard)
        replace("手机号", PHONE)
        replace("邮箱", EMAIL)
        return Result(out, found.toList())
    }

    /** GB 11643：出生日期合理 + 校验位对得上。 */
    private fun isIdCard(s: String): Boolean {
        val year = s.substring(6, 10).toInt()
        val month = s.substring(10, 12).toInt()
        val day = s.substring(12, 14).toInt()
        if (year !in 1900..2100 || month !in 1..12 || day !in 1..31) return false
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        val sum = (0 until 17).sumOf { (s[it] - '0') * weights[it] }
        return "10X98765432"[sum % 11] == s[17].uppercaseChar()
    }

    fun notice(found: List<String>): String =
        "检测到你的${found.joinToString("、")}，已自动打码，确认无误后再点一次发送。误判的话可以恢复原文。"
}
