package com.xjtu.toolbox.library

/** 图书馆座位二维码的数据形状。 */

/** 图书馆桌面二维码：`http://rg.lib.xjtu.edu.cn:8086/qavail/?seat=056&sp=north4southwest`。 */
data class LibrarySeatQr(val seat: String, val areaCode: String) {
    val area: LibraryQrArea? get() = LibraryQrArea.byCode(areaCode)
    val areaName: String get() = area?.displayName ?: areaCode
}

/** 三个校区全部区域。 */
enum class LibraryQrArea(val code: String, val campus: LibraryCampus, val floorCode: String, val areaLabel: String) {
    NORTH2_EAST("north2east", LibraryCampus.XINGQING, "xingqing2floor", "北楼二层外文库（东）"),
    NORTH2_ELIAN("north2elian", LibraryCampus.XINGQING, "xingqing2floor", "二层连廊及流通大厅"),
    NORTH2_WEST("north2west", LibraryCampus.XINGQING, "xingqing2floor", "北楼二层外文库（西）"),
    SOUTH2("south2", LibraryCampus.XINGQING, "xingqing2floor", "南楼二层大厅"),
    WEST3B("west3B", LibraryCampus.XINGQING, "xingqing3floor", "北楼三层ILibrary-B（西）"),
    EAST_NORTH_DA("eastnorthda", LibraryCampus.XINGQING, "xingqing3floor", "大屏辅学空间"),
    EAST3A("east3A", LibraryCampus.XINGQING, "xingqing3floor", "北楼三层ILibrary-A（东）"),
    SOUTH3_MIDDLE("south3middle", LibraryCampus.XINGQING, "xingqing3floor", "南楼三层中段"),
    NORTH4_WEST("north4west", LibraryCampus.XINGQING, "xingqing4floor", "北楼四层西侧"),
    NORTH4_MIDDLE("north4middle", LibraryCampus.XINGQING, "xingqing4floor", "北楼四层中间"),
    NORTH4_EAST("north4east", LibraryCampus.XINGQING, "xingqing4floor", "北楼四层东侧"),
    NORTH4_SOUTHWEST("north4southwest", LibraryCampus.XINGQING, "xingqing4floor", "北楼四层西南侧"),
    NORTH4_SOUTHEAST("north4southeast", LibraryCampus.XINGQING, "xingqing4floor", "北楼四层东南侧"),
    YANTA1("yanta1floor", LibraryCampus.YANTA, "yanta1floor", "一楼"),
    YANTA2_165("yanta2floor165", LibraryCampus.YANTA, "yanta2floor", "2楼165-236"),
    YANTA2_001("yanta2floor001", LibraryCampus.YANTA, "yanta2floor", "2楼001-164"),
    YANTA3_001("yanta3floor001", LibraryCampus.YANTA, "yanta3floor", "3楼001-207"),
    YANTA3_208("yanta3floor208", LibraryCampus.YANTA, "yanta3floor", "3楼208-279"),
    YANTA4_169("yanta4floor169", LibraryCampus.YANTA, "yanta4floor", "4楼169-240"),
    YANTA4_001("yanta4floor001", LibraryCampus.YANTA, "yanta4floor", "4楼001-168"),
    INNO1_CENTRAL("inno1central", LibraryCampus.INNOVATION, "inno1floor", "创新港图书资料中心一层阅览区（中）"),
    INNO1_WEST("inno1west", LibraryCampus.INNOVATION, "inno1floor", "创新港图书资料中心一层阅览区（西）"),
    INNO1_DIGITAL("inno1digital", LibraryCampus.INNOVATION, "inno1floor", "创新港图书资料中心一层电子阅览区"),
    INNO1_EAST("inno1east", LibraryCampus.INNOVATION, "inno1floor", "创新港图书资料中心一层阅览区（东）"),
    INNO1_SOUTH2("inno1south2", LibraryCampus.INNOVATION, "inno1floor", "创新港图书资料中心一层阅览区（南2）"),
    INNO1_SOUTH1("inno1south1", LibraryCampus.INNOVATION, "inno1floor", "创新港图书资料中心一层阅览区（南1）"),
    INNO2_NORTH2("inno2north2", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（北2）"),
    INNO2_NORTH3("inno2north3", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（北3）"),
    INNO2_NORTH1("inno2north1", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（北1）"),
    INNO2_SOUTH1("inno2south1", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（南1）"),
    INNO2_SOUTH2("inno2south2", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（南2）"),
    INNO2_CENTRAL("inno2central", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（中）"),
    INNO2_WEST("inno2west", LibraryCampus.INNOVATION, "inno2floor", "创新港图书资料中心二层阅览区（西）"),
    ;

    /** 雁塔的区域名只写了楼层，单看「一楼」不知道是哪个馆，补上校区。 */
    val displayName: String get() = if (campus == LibraryCampus.YANTA) "雁塔 $areaLabel" else areaLabel

    companion object {
        fun byCode(code: String): LibraryQrArea? = entries.firstOrNull { it.code == code }

        /** 表里没有的新区域按区域码前缀推校区；推不出就是兴庆（兴庆的区域码没有统一前缀）。 */
        fun campusOf(code: String): LibraryCampus = byCode(code)?.campus ?: when {
            code.startsWith("yanta") -> LibraryCampus.YANTA
            code.startsWith("inno") -> LibraryCampus.INNOVATION
            else -> LibraryCampus.XINGQING
        }
    }
}

/** 图书馆页里「扫码要约的座位」确认框的状态。 */
internal data class ScanSeatPrompt(
    val qr: LibrarySeatQr,
    val status: LibrarySeatStatus? = null,
    val checking: Boolean = true,
)

/** `/qavail/` 页面上的座位状态。 */
sealed class LibrarySeatStatus {
    /** @param statusText 页面原文，如「座位空闲」「座位已被预约」 */
    data class Known(val statusText: String) : LibrarySeatStatus() {
        val isFree: Boolean get() = "空闲" in statusText
    }
    /** 页面上没有座位信息：座位号或区域码不存在 */
    object NotFound : LibrarySeatStatus()
}
