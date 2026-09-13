package com.xjtu.toolbox.library

/**
 * 图书馆的三个校区。
 *
 * 之前整套座位功能只认兴庆：楼层码写死 `xingqing2/3/4floor`，区域名写死一张十三条的
 * 中文表（[LibraryApi.AREA_MAP]）。雁塔和创新港的同学打开座位页，区域列表是空的——
 * 因为那两个校区的区域码根本不在那张表里，`filterScount` 一过全被滤掉。
 *
 * 这一版换个思路：
 * - **校区和楼层码写死**，它们是学校系统里固定的枚举，且数量极少；
 * - **区域名不写死**，改成每层现拉 `/qspace?floor=…` 的 `sp` 字段。
 *   兴庆那张表能手抄是因为有人一个个点过，雁塔、创新港没人点，抄不出来也不该抄——
 *   学校改个区域名，写死的表就又要发版。
 *
 * 校区 ID / 楼层码取自 yan-xiaoo/XJTUToolBox 对 `/seatui` floor-selector 的实测
 * （见该仓库 `library/seats.py`）。
 */
enum class LibraryCampus(
    /** 学校系统里的校区 ID，就是 `/modify` 页面 `select#rplace` 的 option value。 */
    val id: String,
    val displayName: String,
    /** 该校区的楼层码，按楼层升序。 */
    val floorCodes: List<String>,
) {
    XINGQING("east", "兴庆", listOf("xingqing2floor", "xingqing3floor", "xingqing4floor")),
    YANTA("west", "雁塔", listOf("yanta1floor", "yanta2floor", "yanta3floor", "yanta4floor")),
    INNOVATION("inno", "创新港", listOf("inno1floor", "inno2floor")),
    ;

    /** 楼层码 → 「三楼」这样的显示名。 */
    fun floorLabel(floorCode: String): String {
        val n = FLOOR_NUMBER.find(floorCode)?.groupValues?.get(1)?.toIntOrNull()
            ?: return floorCode
        return "${CHINESE_DIGITS.getOrNull(n) ?: n.toString()}楼"
    }

    companion object {
        private val FLOOR_NUMBER = Regex("""(\d+)floor""")
        private val CHINESE_DIGITS = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")

        val DEFAULT = XINGQING

        fun byId(id: String?): LibraryCampus? = entries.firstOrNull { it.id == id?.trim() }

        /**
         * 反推楼层码属于哪个校区。
         *
         * 只按前缀匹配，不按 [floorCodes] 精确匹配：万一学校加了一层
         * （`yanta5floor` 之类），前缀照样认得出，不至于整个校区哑掉。
         */
        fun byFloorCode(floorCode: String): LibraryCampus? = when {
            floorCode.startsWith("xingqing") -> XINGQING
            floorCode.startsWith("yanta") -> YANTA
            floorCode.startsWith("inno") -> INNOVATION
            else -> null
        }
    }
}
