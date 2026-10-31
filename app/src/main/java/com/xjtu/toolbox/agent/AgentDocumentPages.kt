package com.xjtu.toolbox.agent

internal object AgentDocumentPages {
    fun select(raw: String?, total: Int, limit: Int, defaultCount: Int = 2): List<Int> {
        require(total > 0) { "文档没有页面" }
        if (raw.isNullOrBlank()) return (1..minOf(total, defaultCount, limit)).toList()
        val selected = linkedSetOf<Int>()
        raw.trim().split(Regex("[,，、]")).forEach { part ->
            val match = requireNotNull(Regex("""\s*(\d+)\s*(?:[-–—~到]\s*(\d+)\s*)?""").matchEntire(part)) { "页码格式应为 5、3-5 或 1,3,7-8" }
            val start = requireNotNull(match.groupValues[1].toIntOrNull()) { "页码过大" }
            val end = if (match.groupValues[2].isEmpty()) start else requireNotNull(match.groupValues[2].toIntOrNull()) { "页码过大" }
            require(start in 1..total && end in start..total) { "页码需在 1-$total 内，且起始页不大于结束页" }
            require(end.toLong() - start + 1 <= limit) { "每次最多读取 $limit 页，请缩小范围" }
            for (page in start..end) {
                selected += page
                require(selected.size <= limit) { "每次最多读取 $limit 页，请缩小范围" }
            }
        }
        return selected.toList()
    }
}
