package com.xjtu.toolbox.library

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 图书馆桌面二维码：`http://rg.lib.xjtu.edu.cn:8086/qavail/?seat=056&sp=north4southwest`。
 *
 * 2026-09-22 实测：
 * - `seat` 就是座位表里的键，也就是预约接口 `/seat/?kid=` 要的值，原样用。
 *   不能补零、去零、加减字母前缀——`56`、`Q056`、`004`（本该是 `D004`）都查不到座位；
 *   字母大小写不敏感（`d004` 也认），但预约还是按座位表里的写法发。
 * - 同一个号在好几个区都有（`100` 到处都是），必须连着 `sp` 一起用。
 * - `/qavail/` 和 `/qseatqr?sp=` 不需要登录：前者显示单个座位的状态，后者只列区域里的空位。
 *
 * 扫到码不在扫码页里约：交给图书馆页（[LibraryFocus] 带上座位号），
 * 那边有平面图、我的预约、换座和跨校区处理，扫码只负责认码。
 */
data class LibrarySeatQr(val seat: String, val areaCode: String) {
    val area: LibraryQrArea? get() = LibraryQrArea.byCode(areaCode)
    val areaName: String get() = area?.displayName ?: areaCode

    companion object {
        private const val HOST = "rg.lib.xjtu.edu.cn"

        /** 认不出返回 null。只认图书馆座位系统的 `/qavail/`，别把别的链接当成座位码。 */
        fun parse(text: String): LibrarySeatQr? {
            val url = text.trim().toHttpUrlOrNull() ?: return null
            if (!url.host.equals(HOST, ignoreCase = true)) return null
            if (url.pathSegments.firstOrNull { it.isNotEmpty() } != "qavail") return null
            val seat = url.queryParameter("seat")?.trim().orEmpty()
            val sp = url.queryParameter("sp")?.trim().orEmpty()
            if (seat.isEmpty() || sp.isEmpty()) return null
            if (!SAFE.matches(seat) || !SAFE.matches(sp)) return null
            return LibrarySeatQr(seat, sp)
        }

        /** 只放行字母数字，座位号和区域码会原样拼进预约地址。 */
        private val SAFE = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

/**
 * 三个校区全部区域。2026-09-22 在网页上逐校区、逐楼层 `/qspace` 实测得到。
 *
 * 图书馆页的区域列表仍然现拉 `/qspace`（见 [LibraryCampus] 的说明），这张表只给扫码用：
 * 扫到码的那一刻要知道这个区在哪个校区、哪一层，而 `/qspace` 只返回账号当前校区，
 * 拿它查别的校区得先改用户资料——不能为了显示一个名字就去切校区。
 * 表里查不到的新区域照样能预约，只是显示区域码、按区域码前缀推校区。
 */
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

/**
 * 图书馆页里「扫码要约的座位」确认框的状态。
 * @param status 查到的状态；null 且 [checking] 为 false 表示没查到（照样允许预约）
 */
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

/**
 * 查单个座位空不空：就是桌面二维码本身打开的那个 `/qavail/` 页面，一次请求，
 * 比拉整层座位表（`qspace` + `qseat`）快得多，扫码弹确认时用。
 */
object LibrarySeatAvailability {
    /** `/qavail/` 页面标题「西安交通大学图书馆图书预约系统」，座位不存在时也有。 */
    private const val PAGE_MARK = "图书馆图书预约系统"
    private val STATUS_RE = Regex("""座位号：\S+\s+预约状态:\s*(\S+)""")

    /**
     * @param client 图书馆会话的客户端（校外自动经 WebVPN）
     * @throws java.io.IOException 拿回来的不是预约系统页面（如 WebVPN 登录页）——那是「查不了」，不是「没有这个座位」
     */
    fun fetch(client: OkHttpClient, qr: LibrarySeatQr): LibrarySeatStatus {
        val url = "${LibraryPages.BASE_URL}/qavail/?seat=${qr.seat}&sp=${qr.areaCode}"
        val html = client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("座位状态查询失败：HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (PAGE_MARK !in html) throw java.io.IOException("不是图书馆预约系统的页面")
        return parse(html)
    }

    /** 页面是 `<h4>座位号：X</h4>…<h4>预约状态:</h4><h3>状态<h3>`（h3 没闭合），脚本里也有「空闲」字样，先去掉脚本。 */
    internal fun parse(html: String): LibrarySeatStatus {
        if ("座位号" !in html) return LibrarySeatStatus.NotFound
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val text = doc.body()?.text().orEmpty().replace(' ', ' ')
        val m = STATUS_RE.find(text) ?: return LibrarySeatStatus.NotFound
        return LibrarySeatStatus.Known(m.groupValues[1])
    }
}
