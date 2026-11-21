package com.xjtu.toolbox.widget.smart

/**
 * 把判出来的一屏塞进卡片里：决定放一个块还是两个、要不要给细节行、底部那条提示能写几个字。
 *
 * 这一版按真机反馈重排过，三条规矩：
 *
 * 1. **卡片不留大块空白**：块区是 `weight=1`，一个块就撑满整张卡（内容垂直居中），两个块各占一半；
 *    底下的提示条永远在，正好把「块和底栏之间那片空」用起来。
 * 2. **一个块还是两个，按高度算**：两个块要两个块的预算，高度不够就退回一个块（并把细节给它）。
 * 3. **每行能写几个字按宽度算**：同一个 17sp 的标题，172dp 的 2×2 放得下 8 个汉字，
 *    110dp 只能放 4 个 —— 写死一个数字必然有一头出界。
 */
object SmartLayout {

    /** 拿不到实例尺寸时的兜底：`smart_widget_info.xml` 的 `minHeight`。 */
    const val DEFAULT_HEIGHT_DP = 110

    /** 拿不到实例宽度时的兜底：`smart_widget_info.xml` 的 `minWidth`。 */
    const val DEFAULT_WIDTH_DP = 110

    /**
     * 左右内边距。
     *
     * 14dp 不是随手写的：卡片背景是 16dp 圆角的形状，圆角处的可视区域要满足
     * `(16−x)² + (16−y)² ≤ 16²`，贴边的字会被圆角切掉；14dp 加上行高，第一行的左边缘落在安全区内。
     * 同时这也是 Android 12 起「小组件内容要离边」的通行建议。
     */
    const val PAD_H_DP = 14

    /** 上下内边距。比左右略小：竖向更贵（一行字就 15dp），圆角对上下第一行的切割也更浅。 */
    const val PAD_V_DP = 8

    /** 块内各行的行高。与两套布局里的 `layout_height` 逐条对应（单测核对）。 */
    const val BLOCK_LEAD_DP = 16
    const val BLOCK_TITLE_DP = 21
    const val BLOCK_STATUS_DP = 17
    const val BLOCK_DETAIL_DP = 15
    const val BLOCK_GAP_DP = 1

    /** 两个块之间的分隔线（1dp 线 + 上下各 3dp 留白）。 */
    const val DIVIDER_DP = 1
    const val DIVIDER_GAP_DP = 3

    /** 底部条：左边次要提示（weight 1），右边刷新图标。 */
    const val BOTTOM_DP = 22
    const val BOTTOM_GAP_DP = 3
    const val KEY_ICON_DP = 18
    const val KEY_GAP_DP = 10

    /** 一个块里最多几个键：块内那一行只排得下两个 18dp 图标 + 间距。 */
    const val MAX_KEYS = 2

    /** 这个宽度起算「宽卡」（两个块左右并排）。 */
    const val WIDE_MIN_WIDTH_DP = 220

    /** 各行的字号（sp）。算「这一行放得下几个字」要用它，改字号必须同时改布局。 */
    const val SIZE_LABEL = 10
    const val SIZE_TITLE = 17
    const val SIZE_STATUS = 12
    const val SIZE_DETAIL = 11
    const val SIZE_HINT = 10

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
     * 一个块能用的高度。宽卡两块是**并排**的，各拿满高；紧凑卡两块上下堆，要除以二
     * （中间那条分隔线也算进去）。
     */
    private fun blockBudgetDp(form: Form, blocks: Int, availableHeightDp: Int): Int {
        val chrome = chromeDp()
        if (blocks <= 1) return availableHeightDp - chrome
        val divide = DIVIDER_DP + DIVIDER_GAP_DP * 2
        return when (form) {
            Form.WIDE -> availableHeightDp - chrome
            Form.COMPACT -> (availableHeightDp - chrome - divide) / 2
        }
    }

    /**
     * 一屏的高度预算（单测用）：宽卡取最高的那个块，紧凑卡把几个块加起来。
     */
    fun heightOf(form: Form, blockHeights: List<Int>): Int {
        val chrome = chromeDp()
        return when {
            blockHeights.isEmpty() -> chrome
            form == Form.WIDE -> chrome + blockHeights.max()
            else -> chrome + blockHeights.sum() +
                (if (blockHeights.size > 1) DIVIDER_DP + DIVIDER_GAP_DP * 2 else 0)
        }
    }

    /** 底栏实际占的宽度：`n` 个 18dp 图标 + 间距（放进布局预算测试）。 */
    fun bottomBarWidthDp(keys: Int): Int =
        keys * KEY_ICON_DP + (keys - 1).coerceAtLeast(0) * KEY_GAP_DP

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
        val textW = textWidthDp(form, availableWidthDp)

        // ① 两个块放不放得下
        var blocks = card.blocks.take(2)
        if (blocks.size == 2 && blockBudgetDp(form, 2, height) < blocks[0].minHeight()) {
            blocks = blocks.take(1)
        }

        // ② 每块能写几行细节
        val budget = blockBudgetDp(form, blocks.size, height)
        val rendered = blocks.map { b ->
            val details = (b.detailCount downTo 0).firstOrNull { n -> b.heightDp(n) <= budget } ?: 0
            RenderedBlock(
                block = b.copy(
                    label = fit(b.label, textW, SIZE_LABEL, DETAIL_MAX),
                    title = fit(b.title, textW, SIZE_TITLE, TITLE_MAX).orEmpty(),
                    detail = if (details >= 1) fit(b.detail, textW, SIZE_DETAIL, DETAIL_MAX) else null,
                    detail2 = if (details >= 2) fit(b.detail2, textW, SIZE_DETAIL, DETAIL_MAX) else null,
                    status = fit(b.statusLine, textW, SIZE_STATUS, STATUS_MAX),
                    meta = null,
                    keys = b.keys.take(MAX_KEYS),
                ),
                details = details,
            )
        }

        return Plan(
            form = form,
            blocks = rendered,
            twoBlocks = rendered.size == 2,
            hint = hintLine(card.hints, textW),
            heightDp = heightOf(form, rendered.map { it.block.heightDp(it.details) }),
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
        return out.takeIf { it.isNotBlank() }
    }

    /** 这一行实际能用的宽度。宽卡两块并排，各分一半。 */
    private fun textWidthDp(form: Form, availableWidthDp: Int): Int {
        val content = (availableWidthDp - PAD_H_DP * 2).coerceAtLeast(40)
        return when (form) {
            // 两块 + 中间那条分隔线（含留白）
            Form.WIDE -> ((content - DIVIDER_DP - DIVIDER_GAP_DP * 2) / 2).coerceAtLeast(40)
            Form.COMPACT -> content
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
        val hint: String?,
        val heightDp: Int,
    )
}
