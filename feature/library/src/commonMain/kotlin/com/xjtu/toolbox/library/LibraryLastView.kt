package com.xjtu.toolbox.library

/** 座位页上次的楼层 / 区域，按「校区ID|码」存。 */
internal object LibraryLastView {
    fun encode(campus: LibraryCampus, code: String): String = campus.id + "|" + code

    /** 属于 [campus] 的码，否则 null。 */
    fun decode(saved: String?, campus: LibraryCampus): String? =
        saved?.removePrefix(campus.id + "|")?.takeIf { it != saved && it.isNotEmpty() }

    /** 属于 [campus] 且仍在 [valid] 中才返回，否则 null（走默认）。 */
    fun restore(saved: String?, campus: LibraryCampus, valid: Collection<String>?): String? =
        decode(saved, campus)?.takeIf { valid != null && it in valid }

    /** 进页要切去的校区：只有确定未预约（[free]）才套用 [last]，否则跟学校。 */
    fun campusToRestore(free: Boolean, last: LibraryCampus?, current: LibraryCampus): LibraryCampus? =
        last?.takeIf { free && it != current }
}
