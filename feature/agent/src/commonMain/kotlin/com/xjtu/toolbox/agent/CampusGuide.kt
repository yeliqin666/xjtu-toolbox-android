package com.xjtu.toolbox.agent

/** 校园指南的一条：所在节、条目名、整行正文（去掉了 Markdown 粗体）。 */
internal data class CampusGuideEntry(val section: String, val title: String, val text: String)

internal val CAMPUS_GUIDE: List<CampusGuideEntry> by lazy {
    CAMPUS_GUIDE_SECTIONS.flatMap { block ->
        val lines = block.lines()
        val section = lines.first().removePrefix("## ").trim()
        lines.drop(1).filter { it.startsWith("- ") }.map { line ->
            val text = line.removePrefix("- ").replace("**", "").trim()
            CampusGuideEntry(section, guideTitle(text), text)
        }
    }
}

/** 条目名：书名号里可能自带「｜」，所以先跳过《…》再找分隔符。 */
private fun guideTitle(text: String): String {
    val from = if (text.startsWith('《')) text.indexOf('》').coerceAtLeast(0) else 0
    val end = text.indexOf('｜', from)
    return if (end < 0) text else text.substring(0, end).trim()
}

/** 问句里的虚词，切二元组前先去掉，免得「怎么」「需要」这类到处命中。 */
private val GUIDE_FILLERS = listOf(
    "是不是", "能不能", "有没有", "可不可以", "怎么办", "怎么样", "怎么", "怎样", "如何", "什么", "哪里", "哪儿", "哪些",
    "多少", "几点", "可以", "需要", "应该", "请问", "一下", "我们", "我", "你", "吗", "呢", "吧", "的", "了", "要", "想",
)
private val GUIDE_SPLIT = Regex("""[\s,，、。;；:：/？?！!()（）"“”]+""")

/** 按关键词给条目排序：去虚词后切成二元组，条目名里命中算双倍；整词命中或至少命中两组才算相关。 */
internal fun rankCampusGuide(entries: List<CampusGuideEntry>, query: String): List<CampusGuideEntry> {
    var q = query.lowercase()
    GUIDE_FILLERS.forEach { q = q.replace(it, " ") }
    val terms = q.split(GUIDE_SPLIT).filter { it.isNotBlank() }
    val grams = terms.flatMap { if (it.length <= 2) listOf(it) else it.windowed(2) }.distinct()
    if (grams.isEmpty()) return emptyList()
    val need = minOf(2, grams.size)
    return entries.mapNotNull { e ->
        val text = e.text.lowercase()
        val title = e.title.lowercase()
        val hits = grams.count { it in text }
        val whole = terms.count { it.length >= 2 && it in text }
        if (hits < need && whole == 0) return@mapNotNull null
        val score = hits + grams.count { it in title } * 2 + whole * 3
        e to score
    }.sortedByDescending { it.second }.map { it.first }
}

private const val GUIDE_NOTE = "摘自校园指南（整理自学校公开文件，条末是出处和年份）。逐年会变的日期、金额、电话，年份较早或问的是当年安排时，提醒以最新通知为准，必要时用 get_notifications 核对。"

/** `search_campus_guide`：给 query 检索；只给 section 列整节；都不给列分节目录。 */
fun campusGuideText(query: String?, section: String?, limit: Int?): String {
    val s = section?.trim().orEmpty()
    val pool = if (s.isEmpty()) CAMPUS_GUIDE else CAMPUS_GUIDE.filter { s in it.section }
    if (pool.isEmpty()) return "没有「$s」这一节。\n" + campusGuideCatalog()
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) {
        if (s.isEmpty()) return campusGuideCatalog()
        return pool.groupBy { it.section }.entries.joinToString("\n\n", "$GUIDE_NOTE\n\n") { (name, items) ->
            "【$name】\n" + items.joinToString("\n") { "- " + it.text }
        }
    }
    val hits = rankCampusGuide(pool, q).take((limit ?: 5).coerceIn(1, 10))
    if (hits.isEmpty()) return "校园指南里没有「$q」相关条目。换个说法再查，或用 get_notifications / wechat_search 找学校通知。"
    return hits.joinToString("\n", "$GUIDE_NOTE\n\n") { "【${it.section}】${it.text}" }
}

private fun campusGuideCatalog(): String =
    CAMPUS_GUIDE.groupBy { it.section }.entries.joinToString(
        "\n", "校园指南共 ${CAMPUS_GUIDE.size} 条，分节（给 section 列整节，给 query 检索）：\n",
    ) { (name, items) -> "- $name（${items.size} 条）" }
