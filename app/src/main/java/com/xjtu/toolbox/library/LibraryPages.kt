package com.xjtu.toolbox.library

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 图书馆座位系统页面 / 接口响应的纯解析。
 *
 * 从 [LibraryApi] 里拆出来，是为了能脱离设备跑 JVM 单测：这里不发请求、不碰 android.*（连 Log 都不用），
 * 输入是响应原文，输出是数据类。学校页面一改版，先在测试里复现，再改这里。
 */
object LibraryPages {

    const val BASE_URL = "http://rg.lib.xjtu.edu.cn:8086"

    /** `showConfirmModal('文案', 'ruguan1', '4953117')` 的动作名与 reserve id。 */
    val CONFIRM_MODAL_REGEX =
        Regex("""showConfirmModal\s*\(\s*['"][^'"]*['"]\s*,\s*['"](\w+)['"]\s*,\s*['"](\d+)['"]\s*\)""")

    val RESERVE_ID_IN_URL = Regex("""[?&]ri=(\d+)""")

    /** 座位号：字母前缀的（C08、Y003）和零开头纯数字的（002、019，兴庆东南侧 / 西南侧）。 */
    val SEAT_ID_REGEX = Regex("""(?:[A-Z]\d{2,4}|\b\d{3}\b)""")

    // ── 我的预约 ──

    /** 解析一次 `/my/` 响应的结论。 */
    sealed interface MyPage {
        data class Booked(val info: MyBookingInfo) : MyPage
        /** 页面认得出来，确实没有当前预约。 */
        data object NoBooking : MyPage
        /** 页面认不出来（错误页、404、改版），调用方应该换个地址再试。 */
        data object Unrecognized : MyPage
    }

    /**
     * @param knownAreaNames 已知区域中文名。只在模板认不出、退回文本启发式时用来找区域名。
     */
    fun parseMyPage(html: String, finalUrl: String, knownAreaNames: Set<String> = emptySet()): MyPage {
        if (html.length < 50) return MyPage.Unrecognized
        val doc = Jsoup.parse(html, finalUrl)
        val bodyText = doc.body()?.text() ?: ""
        if ("Not Found" in bodyText && bodyText.length < 800) return MyPage.Unrecognized

        // ① 结构化解析优先。模板认得出来（有 well / notwell 卡）时，它的结论就是最终结论，
        //    包括「没有预约」——不再往下猜文本。
        if (doc.selectFirst("div.well, div.notwell") != null) {
            return parseBookingCard(doc, html, finalUrl)?.let { MyPage.Booked(it) } ?: MyPage.NoBooking
        }

        // ② 模板不认识，退回文本启发式。先认「暂无预约」：页面上的统计数字（「今日累计 120 人次」）
        //    也长得像兴庆的三位数座位号，先查座位号会把它当成一条预约。
        if (listOf("暂无预约", "没有预约", "无预约").any { it in bodyText }) return MyPage.NoBooking
        if (!SEAT_ID_REGEX.containsMatchIn(bodyText)) {
            return if ("暂无" in bodyText) MyPage.NoBooking else MyPage.Unrecognized
        }
        parseActiveBooking(doc, bodyText, html, knownAreaNames)?.let { return MyPage.Booked(it) }
        // 有座位号、有状态但全都失效了
        return if ("预约状态" in bodyText) MyPage.NoBooking else MyPage.Unrecognized
    }

    /**
     * 按 `/my/` 页的 DOM 结构解析当前预约。
     *
     * 认模板而不是认数据：模板全校一套，座位编号每个校区都不同（创新港、雁塔的编号
     * 对不上 [SEAT_ID_REGEX]，按全文正则找座位号会永远显示「暂无预约」）。
     * - 当前预约是 `div.well`，历史记录是 `div.notwell`；没有 well 卡就是真的没有预约；
     * - 座位行是卡内第一个 `<hr>` 的**尾随文本**，固定为 `区域名&nbsp;座位号`；
     * - 状态是 `.cta-button` 里第一个无 class 的 `<h3>`。
     *
     * 结构判据取自 yan-xiaoo/XJTUToolBox 对该页面的实测（`library/seats.py`）。
     */
    fun parseBookingCard(doc: Document, html: String, finalUrl: String): MyBookingInfo? {
        val card = doc.selectFirst("div.well") ?: return null

        // Jsoup 没有 lxml 的 .tail，尾随文本就是 <hr> 的下一个兄弟文本节点。
        // 必须用 wholeText：text() 会把 &nbsp; 规范化掉，而我们正是靠它切分区域名和座位号。
        val hr = card.selectFirst("hr") ?: return null
        val tail = (hr.nextSibling() as? org.jsoup.nodes.TextNode)?.wholeText?.trim().orEmpty()
        if (tail.isBlank()) return null

        // NBSP 不算 Kotlin 认的空白字符，上面的 trim() 不会把它吃掉。
        val nbsp = ' '
        val area: String?
        val seatId: String
        if (nbsp in tail) {
            area = tail.substringBeforeLast(nbsp).trim().ifBlank { null }
            seatId = tail.substringAfterLast(nbsp).trim()
        } else {
            // 结构对了但分隔符不是 NBSP（模板微调过）。别把整行当座位号，退一步按空白切最后一段。
            area = tail.substringBeforeLast(' ').trim().ifBlank { null }
            seatId = tail.substringAfterLast(' ').trim()
        }
        if (seatId.isBlank()) return null

        val status = card.selectFirst("div.cta-button h3:not([class])")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }

        // 认 JS 调用本身比认按钮文案稳：页面按状态只渲染当前可执行的按钮，出现哪个给哪个。
        val actions = actionsFromConfirmModal(html, finalUrl)
            .ifEmpty { parseActionsFromHtml(doc, html) }
        return MyBookingInfo(seatId, area, status, actions)
    }

    /** 从整页 HTML 里扫 `showConfirmModal(…)`。WebVPN 会把引号实体化，先反转义再扫。 */
    fun actionsFromConfirmModal(html: String, finalUrl: String): MutableMap<String, String> {
        val normalized = org.jsoup.parser.Parser.unescapeEntities(html, false)
        val out = linkedMapOf<String, String>()
        CONFIRM_MODAL_REGEX.findAll(normalized).forEach { m ->
            val label = labelOfAction(m.groupValues[1]) ?: return@forEach
            buildActionUrl(m.groupValues[1], m.groupValues[2])?.let { out.putIfAbsent(label, it) }
        }
        if (out.isEmpty()) {
            // 页面没内联 JS（有的模板把 ri 放在地址上），退一步从 URL 里取。
            RESERVE_ID_IN_URL.find(finalUrl)?.groupValues?.get(1)?.let { ri ->
                buildActionUrl("cancel", ri)?.let { out["取消预约"] = it }
            }
        }
        return out
    }

    private fun labelOfAction(action: String): String? = when (action) {
        "cancel" -> "取消预约"
        "ruguan1" -> "入馆签到"
        "leave", "midleave" -> "中途离开"
        "return", "midreturn" -> "中途返回"
        else -> null
    }

    /**
     * 页面 JS 的动作名 → 动作 URL。注意 action 名和 URL 参数名不一样
     * （ruguan1→firstruguan、leave→midleave、return→midreturn），来源是前端 `switch(currentAction)`。
     */
    fun buildActionUrl(action: String, reserveId: String): String? = when (action) {
        "cancel" -> "$BASE_URL/my/?cancel=1&ri=$reserveId"
        "ruguan1" -> "$BASE_URL/my/?firstruguan=1&ri=$reserveId"
        "leave", "midleave" -> "$BASE_URL/my/?midleave=1&ri=$reserveId"
        "return", "midreturn" -> "$BASE_URL/my/?midreturn=1&ri=$reserveId"
        else -> null
    }

    /** 动作 URL → 显示名，[LibraryApi.executeAction] 按它决定怎么复核结果。 */
    fun labelOfActionUrl(url: String): String? = when {
        "cancel" in url -> "取消预约"
        "firstruguan" in url -> "入馆签到"
        "midleave" in url -> "中途离开"
        "midreturn" in url -> "中途返回"
        else -> null
    }

    /** 两个座位号是不是同一个座位。严格相等（忽略大小写和首尾空白），A01 和 A011 不是一个座位。 */
    fun sameSeat(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    enum class ActionVerdict { DONE, NOT_DONE, UNKNOWN }

    /**
     * 操作做完后，按重新拉到的「我的预约」判断它到底生效没有。
     *
     * 学校页面按预约状态只渲染当前能做的按钮，所以看按钮就知道状态走到哪一步了：
     * 签到成功后「入馆签到」消失，中途离开后出现「中途返回」，返回后「中途返回」消失。
     * 落地页文案不可靠（学校随时改），这里一概不看。
     *
     * @param fetched 这次「我的预约」有没有真拿到。没拿到时 [after] 为 null 不代表没有预约，只能说不知道。
     */
    fun actionVerdict(label: String?, after: MyBookingInfo?, fetched: Boolean): ActionVerdict {
        if (!fetched || label == null) return ActionVerdict.UNKNOWN
        val active = after?.takeIf { it.statusText !in LibraryApi.INACTIVE_STATUSES }
        val actions = active?.actionUrls?.keys.orEmpty()
        return when (label) {
            "取消预约" -> if (active == null) ActionVerdict.DONE else ActionVerdict.NOT_DONE
            "入馆签到" -> when {
                active == null || "入馆签到" in actions -> ActionVerdict.NOT_DONE
                else -> ActionVerdict.DONE
            }
            "中途离开" -> when {
                "中途返回" in actions -> ActionVerdict.DONE
                active == null || "中途离开" in actions -> ActionVerdict.NOT_DONE
                else -> ActionVerdict.UNKNOWN
            }
            "中途返回" -> when {
                active == null || "中途返回" in actions -> ActionVerdict.NOT_DONE
                else -> ActionVerdict.DONE
            }
            else -> ActionVerdict.UNKNOWN
        }
    }

    /** 模板认不出时的文本启发式：页面可能列了多条预约（含已取消），只取第一条活跃的。 */
    private fun parseActiveBooking(
        doc: Document, bodyText: String, html: String, knownAreaNames: Set<String>,
    ): MyBookingInfo? {
        val statusMatches = Regex("""预约状态[:：]\s*(\S+)""").findAll(bodyText).toList()
        if (statusMatches.isEmpty()) {
            val seatId = SEAT_ID_REGEX.find(bodyText)?.value ?: return null
            val area = knownAreaNames.firstOrNull { it in bodyText }
            return MyBookingInfo(seatId, area, null, parseActionsFromHtml(doc, html))
        }
        var blockStart = 0
        for (statusMatch in statusMatches) {
            val status = statusMatch.groupValues[1]
            val blockEnd = statusMatch.range.last + 1
            val blockText = bodyText.substring(blockStart, blockEnd)
            blockStart = blockEnd
            if (status in LibraryApi.INACTIVE_STATUSES) continue
            val seatId = SEAT_ID_REGEX.findAll(blockText).lastOrNull()?.value ?: continue
            val area = knownAreaNames.firstOrNull { it in blockText }
            return MyBookingInfo(seatId, area, status, parseActionsFromHtml(doc, html))
        }
        return null
    }

    /**
     * 从 DOM 里找操作按钮的真实 URL。按钮多是 `href="#"` 的 JS 按钮：依次看 data-*、onclick、href，
     * 再看 form，最后从 script 里捞。
     */
    fun parseActionsFromHtml(doc: Document, html: String): MutableMap<String, String> {
        val actionUrls = linkedMapOf<String, String>()
        val navTexts = setOf("座位预约", "我预约的座位", "我预约的图书", "跨校", "提存", "典藏", "意见反馈",
            "资料修改", "活动查询", "注销", "常见问题", "其他功能", "Toggle navigation", "首页",
            "English version", "确认操作", "确认", "取消", "×")

        doc.body()?.select("a[href], button[onclick], a[onclick], a[data-href], a[data-url]")?.forEach { el ->
            val text = el.text().trim()
            if (text.isBlank() || text in navTexts || text.length > 15) return@forEach
            if ("logout" in (el.attr("href") + el.attr("onclick")).lowercase()) return@forEach
            val realUrl = el.attr("data-href").ifBlank { null }
                ?: el.attr("data-url").ifBlank { null }
                ?: el.attr("data-action").ifBlank { null }
                ?: extractUrlFromOnclick(org.jsoup.parser.Parser.unescapeEntities(el.attr("onclick"), false))
                ?: el.attr("abs:href").let { href ->
                    if (href.isBlank() || href.endsWith("#") || href == "#" || "javascript:" in href) null else href
                }
            val label = classifyActionLabel(text) ?: return@forEach
            if (realUrl != null) actionUrls[label] = realUrl
        }

        doc.select("form[action]").forEach { form ->
            val action = form.attr("abs:action").ifBlank { return@forEach }
            val submitText = form.select("button[type=submit], input[type=submit]").firstOrNull()?.let {
                it.text().ifBlank { it.attr("value") }
            } ?: return@forEach
            val label = classifyActionLabel(submitText)
            if (label != null && !action.endsWith("#")) actionUrls[label] = action
        }

        if (actionUrls.isEmpty() || "取消预约" !in actionUrls) {
            extractActionsFromScripts(doc).forEach { (label, url) -> actionUrls.putIfAbsent(label, url) }
        }
        return actionUrls
    }

    fun classifyActionLabel(text: String): String? = when {
        "取消" in text && "预约" in text -> "取消预约"
        "线上签到" in text -> "入馆签到"
        "首次入馆" in text || "入馆" in text && "离" !in text && "返" !in text -> "入馆签到"
        "签到" in text && "回馆" !in text && "离" !in text && "返" !in text -> "入馆签到"
        "中途离开" in text -> "中途离开"
        "离馆" in text || "暂离" in text || "中途离" in text -> "中途离开"
        "中途返回" in text -> "中途返回"
        "回馆" in text || "返回签到" in text || "中途返" in text -> "中途返回"
        "换座" in text -> "我想换座"
        "取消" in text -> "取消预约"
        else -> null
    }

    private fun extractUrlFromOnclick(onclick: String?): String? {
        if (onclick.isNullOrBlank()) return null
        Regex("""(?:location\.href|location|window\.location)\s*=\s*['"]([^'"]+)['"]""")
            .find(onclick)?.groupValues?.get(1)?.let { return it }
        CONFIRM_MODAL_REGEX.find(onclick)?.let { m ->
            return buildActionUrl(m.groupValues[1], m.groupValues[2])
        }
        Regex("""['"](/[^'"]+)['"]""").find(onclick)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun extractActionsFromScripts(doc: Document): Map<String, String> {
        val found = linkedMapOf<String, String>()
        doc.select("script").forEach { script ->
            val code = script.data()
            if (code.length < 20) return@forEach
            CONFIRM_MODAL_REGEX.findAll(code).forEach { m ->
                val label = labelOfAction(m.groupValues[1]) ?: return@forEach
                buildActionUrl(m.groupValues[1], m.groupValues[2])?.let { found.putIfAbsent(label, it) }
            }
            // url = "/my/?cancel=1&ri=" + 123
            Regex("""['"](/my/\?(?:cancel|firstruguan|midleave|midreturn)=1&ri=)\s*['"]?\s*\+?\s*['"]?(\d+)""")
                .findAll(code).forEach { m ->
                    val full = "$BASE_URL${m.groupValues[1]}${m.groupValues[2]}"
                    labelOfActionUrl(full)?.let { found.putIfAbsent(it, full) }
                }
        }
        return found
    }

    // ── 预约失败原因 ──

    /**
     * 预约页失败时的原因。认不出返回 null，调用方再判登录失效 / 未知原因。
     */
    fun bookingFailureReason(html: String): String? {
        val doc = Jsoup.parse(html)
        val alertText = extractAlertText(doc, ".alert, .error, .msg, .message, .warn, .notice, #msg, .tip")
        if (alertText.isNotBlank()) return alertText
        val bodyText = doc.body()?.text() ?: ""
        return when {
            "30分钟" in bodyText || "30 min" in bodyText -> "30 分钟内不能重复预约\n‣ 取消后 30 分钟内不能重新预约"
            "已被预约" in bodyText || "已被占" in bodyText -> "该座位已被他人预约\n‣ 已自动刷新座位列表"
            "已有预约" in bodyText || "已预约" in bodyText -> "您已有其他座位预约\n‣ 如需更换，请先取消当前预约"
            "不在预约时间" in bodyText || "未开放" in bodyText -> "当前不在预约开放时间\n‣ 预约通常在 22:00 开放次日抢座"
            "维护" in bodyText -> "系统维护中，请稍后再试"
            else -> null
        }
    }

    /**
     * 学校页面的提示框是 Bootstrap 风格：`<div class="alert"><button class="close">×</button>正文</div>`。
     * 直接对整个容器 `.text()` 会把关闭按钮上的 "×" 也拼进来——先把关闭按钮摘掉再取文字。
     */
    fun extractAlertText(doc: Document, selector: String): String {
        val elements = doc.select(selector)
        elements.select(".close, [data-dismiss], button").remove()
        return elements.text().trim().trimStart('×', '✕', '✗').trim()
    }

    // ── 平面图 ──

    /**
     * 解析 `/qseatuist?sp=区域码`：`{座位号: [left, top, width, height, 状态码]}`，数值是字符串。
     *
     * 坐标是区域平面图（`/static/images/ui10/{区域码}.jpg`）上的像素。状态码和 `/qseat` **不一样**：
     * 这里 2 才是空闲，0 已预约、1 使用中、3 中途离开、-1 取消。`cancel` 是图上「返回上一级」
     * 那个按钮的矩形，`spacecancel` 同类，都不是座位，丢掉。
     */
    fun parseSeatLayout(body: String): SeatLayout {
        // 用 Gson 而不是 org.json：后者在 JVM 单测里是 android.jar 的桩，一调就抛。
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        val seats = ArrayList<PlanSeat>()
        for ((id, value) in json.entrySet()) {
            if (id.isBlank() || id == "cancel" || id == "spacecancel") continue
            if (!value.isJsonArray) continue
            val arr = value.asJsonArray
            if (arr.size() < 4) continue
            fun at(i: Int): String? = arr[i].takeIf { it.isJsonPrimitive }?.asString?.trim()
            val nums = (0 until 4).map { at(it)?.toFloatOrNull() }
            if (nums.any { it == null }) continue
            val (l, t, w, h) = nums.map { it!! }
            if (w <= 0f || h <= 0f) continue
            val status = if (arr.size() >= 5) at(4)?.toIntOrNull() ?: PlanSeat.FREE else PlanSeat.FREE
            seats += PlanSeat(id, l, t, w, h, status)
        }
        return SeatLayout(seats)
    }

    /** 区域平面图各状态的图名。底图加载失败整张图不可用；状态图缺了就按底图画。 */
    fun planImageNames(areaCode: String): Map<Int?, String> = mapOf(
        null to "$areaCode.jpg",
        PlanSeat.BOOKED to "$areaCode-book.jpg",
        PlanSeat.INSIDE to "$areaCode-inside.jpg",
        PlanSeat.LEAVE to "$areaCode-leave.jpg",
        PlanSeat.CANCELLED to "blanket.jpg",
    )
}

/** 平面图上的一个座位：矩形是平面图像素坐标。 */
data class PlanSeat(
    val seatId: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val status: Int,
) {
    val available: Boolean get() = status == FREE
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    companion object {
        const val FREE = 2
        const val BOOKED = 0
        const val INSIDE = 1
        const val LEAVE = 3
        const val CANCELLED = -1
    }
}

data class SeatLayout(val seats: List<PlanSeat>)
