package com.xjtu.toolbox.widget.smart

/**
 * 把判出来的一屏塞进卡片里：决定放一个块还是两个、要不要给细节行、底部那条提示能写几个字。
 *
 * 这一版按真机反馈重排过，三条规矩：
 *
 * 1. **卡片不留大块空白**：块区是 `weight=1`，一个块就撑满整张卡（内容垂直居中），两个块各占一半；
 *    底下的提示条永远在，正好把「块和底栏之间那片空」用起来。
 * 2. **一个块还是两个，按高度算**：两个块要两个块的预算，高度不够就退回一个块（并把细节给它）。
 * 3. **每行能写几个字按宽度算**：同一个 20sp 的标题，172dp 的 2×2 放得下 7 个汉字，
 *    110dp 只能放 4 个 —— 写死一个数字必然有一头出界。
 */
object SmartLayout {

    /** 拿不到实例尺寸时的兜底：`smart_widget_info.xml` 的 `minHeight`。 */
    const val DEFAULT_HEIGHT_DP = 110

    /** 拿不到实例宽度时的兜底：`smart_widget_info.xml` 的 `minWidth`。 */
    const val DEFAULT_WIDTH_DP = 110

    /**
     * 卡片内边距。背景用桌面的圆角（常见 16–28dp），首行左上角 (14, 10) 离 28dp 圆心约 23dp，
     * 不会被圆角切到。
     */
    const val PAD_H_DP = 14
    const val PAD_V_DP = 10

    /** 块内各行的行高。与两套布局里的 `layout_height` 逐条对应（单测核对）。 */
    const val BLOCK_LEAD_DP = 22
    const val BLOCK_TITLE_DP = 25
    /** 单块且标题放得下时，标题放大成主数字（余额、座位号这类短标题）。 */
    const val BLOCK_HERO_DP = 34
    const val BLOCK_STATUS_DP = 17
    const val BLOCK_DETAIL_DP = 16
    const val BLOCK_GAP_DP = 2

    /** 两个块时各垫一层深一档的底（`bg_widget_tile`）代替分隔线：内边距和两块间距。 */
    const val TILE_PAD_H_DP = 10
    const val TILE_PAD_V_DP = 8
    const val TILE_GAP_DP = 6

    /** 底部条：左边次要提示（weight 1），右边刷新图标。 */
    const val BOTTOM_DP = 18
    const val BOTTOM_GAP_DP = 4
    const val REFRESH_DP = 18
    const val REFRESH_GAP_DP = 8

    /** 块里的图标按钮：22dp 圆底，左侧 6dp 间距。 */
    const val KEY_ICON_DP = 22
    const val KEY_GAP_DP = 6

    /** 一个块里最多几个键。 */
    const val MAX_KEYS = 2

    /** 这个宽度起算「宽卡」（两个块左右并排）。 */
    const val WIDE_MIN_WIDTH_DP = 220

    /** 各行的字号（sp）。算「这一行放得下几个字」要用它，改字号必须同时改布局。 */
    const val SIZE_LABEL = 12
    const val SIZE_TITLE = 20
    const val SIZE_HERO = 28
    const val SIZE_STATUS = 13
    const val SIZE_DETAIL = 12
    const val SIZE_HINT = 11

    /** 字数硬上限（宽度算不出来时的保险）。 */
    const val TITLE_MAX = 18
    const val DETAIL_MAX = 20
    const val STATUS_MAX = 24

    /** 两个形态：一套布局（`widget_smart_compact.xml` / `widget_smart_wide.xml`）。 */
    enum class Form { COMPACT, WIDE }

    /** 按实例宽度挑形态。用户可以把它拖成 4×2、2×4，形态得跟着实例走。 */
    fun formOf(minWidthDp: Int): Form =
        if (minWidthDp >= WIDE_MIN_WIDTH_DP) Form.WIDE else Form.COMPACT

    /** 底部条 + 上下内边距的固定开销。 */
    private fun chromeDp(): Int = PAD_V_DP * 2 + BOTTOM_GAP_DP + BOTTOM_DP

    /**
     * 一个块的内容能用的高度。单块直接用满；两块时扣掉各自底板的上下内边距，
     * 宽卡并排各拿满高，紧凑卡上下堆要扣掉间距再对半分。
     */
    private fun blockBudgetDp(form: Form, blocks: Int, availableHeightDp: Int): Int {
        val area = availableHeightDp - chromeDp()
        if (blocks <= 1) return area
        val tile = TILE_PAD_V_DP * 2
        return when (form) {
            Form.WIDE -> area - tile
            Form.COMPACT -> (area - TILE_GAP_DP) / 2 - tile
        }
    }

    /** 一屏的总高度（单测用）：宽卡取最高的那个块，紧凑卡把几个块加起来。 */
    fun heightOf(form: Form, blockHeights: List<Int>): Int {
        val chrome = chromeDp()
        if (blockHeights.size <= 1) return chrome + (blockHeights.firstOrNull() ?: 0)
        val tiles = blockHeights.map { it + TILE_PAD_V_DP * 2 }
        return chrome + if (form == Form.WIDE) tiles.max() else tiles.sum() + TILE_GAP_DP
    }

    /** 场景行右侧 `n` 个图标按钮占的宽度（每个带左间距）。 */
    fun keysWidthDp(keys: Int): Int = keys * (KEY_ICON_DP + KEY_GAP_DP)

    /**
     * 落成一屏实际要渲染的东西。
     *
     * 三条伸缩规则（对着「一块占满 / 两块各半」而设计）：
     * 1. **块数**：两个块放得下就放两个（各占一半），放不下就退回一个；
     * 2. **细节行**：每个块能写几行细节由它分到的高度决定（0/1/2 行）——
     *    一个块独占整张卡时写两行，把卡填满，而不是把三行字摆在正中间、上下都空着；
     * 3. **底部那条**：按宽度拼提示，装不下就少一条。
     *
     * @param availableHeightDp 这个实例实际分到的高度（`OPTION_APPWIDGET_MIN_HEIGHT`）。
     * @param availableWidthDp 这个实例实际的宽度（`OPTION_APPWIDGET_MIN_WIDTH`）。
     */
    fun plan(
        card: SmartCard,
        form: Form,
        availableHeightDp: Int = DEFAULT_HEIGHT_DP,
        availableWidthDp: Int = DEFAULT_WIDTH_DP,
    ): Plan {
        val height = availableHeightDp.coerceAtLeast(DEFAULT_HEIGHT_DP)

        // ① 两个块放不放得下
        var blocks = card.blocks.take(2)
        if (blocks.size == 2 && blockBudgetDp(form, 2, height) < blocks.maxOf { it.minHeight() }) {
            blocks = blocks.take(1)
        }
        val budget = blockBudgetDp(form, blocks.size, height)
        val textW = textWidthDp(form, availableWidthDp, blocks.size)

        // ② 单块时标题能否放大成主数字：高度够、而且放大后一个字都不用截
        val single = blocks.singleOrNull()
        val hero = single != null && single.heightDp(0, BLOCK_HERO_DP) <= budget &&
            clampWidth(single.title, textW, SIZE_HERO) == single.title
        val titleDp = if (hero) BLOCK_HERO_DP else BLOCK_TITLE_DP
        val titleSp = if (hero) SIZE_HERO else SIZE_TITLE

        // ③ 每块能写几行细节
        val rendered = blocks.map { b ->
            val details = (b.detailCount downTo 0).firstOrNull { n -> b.heightDp(n, titleDp) <= budget } ?: 0
            val keys = b.keys.take(MAX_KEYS)
            RenderedBlock(
                block = b.copy(
                    label = fit(b.label, textW - keysWidthDp(keys.size), SIZE_LABEL, DETAIL_MAX),
                    title = fit(b.title, textW, titleSp, TITLE_MAX).orEmpty(),
                    detail = if (details >= 1) fit(b.detail, textW, SIZE_DETAIL, DETAIL_MAX) else null,
                    detail2 = if (details >= 2) fit(b.detail2, textW, SIZE_DETAIL, DETAIL_MAX) else null,
                    status = fit(b.statusLine, textW, SIZE_STATUS, STATUS_MAX),
                    meta = null,
                    keys = keys,
                ),
                details = details,
            )
        }

        val hintW = (availableWidthDp - PAD_H_DP * 2 - REFRESH_DP - REFRESH_GAP_DP).coerceAtLeast(40)
        return Plan(
            form = form,
            blocks = rendered,
            twoBlocks = rendered.size == 2,
            hero = hero,
            hint = hintLine(card.hints, hintW),
            heightDp = heightOf(form, rendered.map { it.block.heightDp(it.details, titleDp) }),
        )
    }

    /** 底部那条提示：按宽度把几条拼成一行，装不下的丢掉（宁可少一条，也不要溢出）。 */
    private fun hintLine(hints: List<SmartHint>, widthDp: Int): String? {
        val sep = " · "
        var out = ""
        for (h in hints) {
            val candidate = if (out.isEmpty()) h.text else out + sep + h.text
            // clampWidth 原样返回 = 这一行装得下
            if (clampWidth(candidate, widthDp, SIZE_HINT) != candidate) break
            out = candidate
        }
        // 一条完整的都放不下时，截断显示第一条，不让提示整行消失
        if (out.isEmpty()) return hints.firstOrNull()?.let { clampWidth(it.text, widthDp, SIZE_HINT) }
        return out
    }

    /** 块里一行字实际能用的宽度。两块时扣掉底板内边距，宽卡还要再对半分。 */
    private fun textWidthDp(form: Form, availableWidthDp: Int, blocks: Int): Int {
        val content = (availableWidthDp - PAD_H_DP * 2).coerceAtLeast(40)
        if (blocks <= 1) return content
        val tile = TILE_PAD_H_DP * 2
        return when (form) {
            Form.WIDE -> ((content - TILE_GAP_DP) / 2 - tile).coerceAtLeast(40)
            Form.COMPACT -> (content - tile).coerceAtLeast(40)
        }
    }

    /**
     * 按**像素宽度**收一行字。
     *
     * 为什么不能按「字数」收：同样是十几个字符，「迟到 · 08:05 查过」比「一二三四五六七八九十」
     * 窄得多，一刀切按字数收就会把「查过」前面那个时刻（整句里唯一有时效的半句）截掉。
     * 所以按**四分之一 em** 累计：
     *
     * | 字符 | 占 |
     * |---|---|
     * | 汉字、全角标点 | 4 |
     * | 空格 | 1 |
     * | `. , : ; ·  / -` 这类窄标点 | 1 |
     * | 数字、拉丁字母 | 2 |
     *
     * 这是个偏保守的估算，宁可早一点点出现省略号，也不要溢出到卡片外面去。
     */
    fun clampWidth(text: String?, widthDp: Int, textSizeSp: Int): String? {
        if (text.isNullOrBlank()) return null
        val budget = (widthDp.coerceAtLeast(1) * 4) / textSizeSp.coerceAtLeast(1)
        if (quarterEm(text) <= budget) return text
        val out = StringBuilder()
        var used = 0
        for (c in text) {
            val w = quarterEm(c)
            // 给省略号留一个全角位，收出来的行才不会被后面的「…」再顶出界
            if (used + w > budget - 4) break
            out.append(c)
            used += w
        }
        val kept = out.toString().trimEnd()
        return if (kept.isEmpty()) "…" else "$kept…"
    }

    /** 一个字符占几个「四分之一 em」。 */
    private fun quarterEm(c: Char): Int = when {
        c.code >= 0x2E80 -> 4
        c.isWhitespace() -> 1
        c in NARROW_PUNCT -> 1
        else -> 2
    }

    private fun quarterEm(text: String): Int = text.sumOf { quarterEm(it).toLong() }.toInt()

    private const val NARROW_PUNCT = ".,:;·|/\\-'\"!?()[]"

    /** 先按宽度收，再按字数硬上限兜一刀。 */
    private fun fit(text: String?, widthDp: Int, textSizeSp: Int, hardMax: Int): String? =
        clamp(clampWidth(text, widthDp, textSizeSp), hardMax)

    /** 按字数收一行字（宽度算不出来时的保险）。 */
    fun clamp(text: String?, max: Int): String? = when {
        text.isNullOrBlank() -> null
        text.length <= max -> text
        max <= 1 -> "…"
        else -> text.take(max - 1) + "…"
    }

    /** 一个块最少要占多高：场景行 + 标题 + 状态（没有状态时更矮）。 */
    private fun SmartBlock.minHeight(): Int = heightDp(details = 0)

    /** 渲染一个块要用的东西：块本身 + 它这次写了几行细节。 */
    data class RenderedBlock(val block: SmartBlock, val details: Int)

    /** 一屏要画的东西。 */
    data class Plan(
        val form: Form,
        val blocks: List<RenderedBlock>,
        val twoBlocks: Boolean,
        /** 单块的标题按 [SIZE_HERO] / [BLOCK_HERO_DP] 画。 */
        val hero: Boolean,
        val hint: String?,
        val heightDp: Int,
    )
}
