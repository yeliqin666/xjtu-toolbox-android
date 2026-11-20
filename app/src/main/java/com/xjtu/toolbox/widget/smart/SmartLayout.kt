package com.xjtu.toolbox.widget.smart

/**
 * 把判出来的一屏塞进卡片里：选形态、算高度预算、不够就**按块降级**。
 *
 * 这一层管三件事，都是「看上去挤不挤」的直接原因：
 *
 * 1. **安全距离**：四周留 `PAD_H × PAD_V` 的内边距（卡片背景是 16dp 圆角的形状，贴边会
 *    被圆角切掉），行与行之间也都留了间距；钩子区、按键区各自再隔一条 1dp 分割线。
 * 2. **按实例的实际高度算预算**：`minHeight` 是「至少需要多高」，不是「你只会分到这么高」。
 *    同一个 2×2，在不同桌面网格下可能是 110dp 也可能是 140dp —— 拿实例给的
 *    `OPTION_APPWIDGET_MIN_HEIGHT` 来算，空间富余就多写一条钩子，不够就先砍钩子。
 * 3. **块可以占多行，砍的时候整行地砍**：顺序是「多余的钩子 → 细节行 → 状态行 → 场景行」，
 *    标题与底栏永远不砍（标题是这个块的主语，底栏是唯一还能点的东西）。
 */
object SmartLayout {

    /** 拿不到实例尺寸时的兜底：`smart_widget_info.xml` 的 `minHeight`，也是「不能挤出」的红线。 */
    const val DEFAULT_HEIGHT_DP = 110

    /**
     * 左右内边距。
     *
     * 14dp 不是随手写的：卡片背景是 16dp 圆角的形状，圆角处的可视区域要满足
     * `(16−x)² + (16−y)² ≤ 16²`，贴边的第一行字会被圆角切掉；14dp 加上行高，
     * 第一行字的左边缘落在安全区内。同时也是 Android 12 起「小组件内容要离边」的通行建议。
     */
    const val PAD_H_DP = 14

    /** 上下内边距。比左右略小：竖向更贵（一行字就 15dp），圆角对上下第一行的切割也更浅。 */
    const val PAD_V_DP = 8

    const val ROW_LABEL_DP = 15
    const val GAP_LABEL_DP = 1
    const val ROW_TITLE_DP = 22
    const val GAP_STATUS_DP = 2
    const val ROW_STATUS_DP = 18
    const val GAP_DETAIL_DP = 2
    const val ROW_DETAIL_DP = 15

    /** 钩子区：与块之间是一条 1dp 分割线，上下各留 [HOOK_LEAD_DP] / [HOOK_GAP_DP]。 */
    const val HOOK_LEAD_DP = 6
    const val DIVIDER_DP = 1
    const val HOOK_GAP_DP = 6
    const val ROW_HOOK_DP = 15

    /** 按键区：再来一条分割线，把「能点的地方」与内容分开。 */
    const val KEYS_LEAD_DP = 4
    const val KEYBAR_DIVIDER_DP = 1
    const val KEYBAR_GAP_DP = 4

    /** 底栏高 24dp（图标 20dp + 上下各 2dp 的呼吸），是「点得中」的最小值。 */
    const val ROW_KEYS_DP = 24
    const val KEY_ICON_DP = 20
    const val KEY_GAP_DP = 12

    /** 底栏最多几个键：位置要固定、要少，多了就成了工具箱。 */
    const val MAX_KEYS = 3

    /**
     * 紧凑卡底栏最多几个键。
     *
     * 算得出来：2×2 的卡宽 110dp，扣掉左右 14dp 内边距只剩 82dp；
     * 三个 20dp 图标 + 两个 12dp 间距 = 84dp，差 2dp 就会把最右那颗挤出去。
     * 所以紧凑卡只放两个，宽卡（≥220dp）才有三个位置。
     */
    const val COMPACT_MAX_KEYS = 2

    /** 紧凑卡最多两条钩子；再多就该升级成宽卡了。 */
    const val COMPACT_MAX_HOOKS = 2

    /** 宽卡右栏能竖着排几条钩子（15dp × 5 = 75dp，比左栏的四行还矮）。 */
    const val WIDE_MAX_HOOKS = 5

    /** 这个宽度起算「宽卡」（左栏一块 + 右栏钩子）。 */
    const val WIDE_MIN_WIDTH_DP = 220

    /** 宽卡右栏宽度：钩子文案按这里裁（约 8 个字）。 */
    const val WIDE_RAIL_DP = 88

    /**
     * 各行的字数上限。
     *
     * 为什么在 Kotlin 侧截断而不是只靠 `ellipsize="end"`：不同 ROM 的省略号行为不一致
     * （有的把整行抹成「…」，有的干脆溢出到下一行被裁），而卡片的高度是算出来的，
     * 一行溢出就是把下面那行顶掉。所以**先按字数收一遍**，`ellipsize` 只当第二道保险。
     *
     * ⚠️ 这几个是**上限**，实际能写几个字由 [charsFor] 按实例宽度算 —— 同样是 17sp 的标题，
     * 172dp 的 2×2 放得下 8 个字，110dp 的只放得下 4 个。写死一个数字必然有一头出界。
     */
    const val TITLE_MAX = 18
    const val DETAIL_MAX = 20
    const val STATUS_MAX = 24
    const val HOOK_MAX = 12

    /** 各行的字号（sp）。算出「这一行放得下几个字」要用它，改字号必须同时改布局。 */
    const val SIZE_LABEL = 10
    const val SIZE_TITLE = 17
    const val SIZE_STATUS = 12
    const val SIZE_DETAIL = 11
    const val SIZE_HOOK = 10

    /** 两个形态：一套布局一个（`widget_smart_compact.xml` / `widget_smart_wide.xml`）。 */
    enum class Form { COMPACT, WIDE }

    /**
     * 按实例宽度挑形态。
     *
     * 用户可以把它拖成 4×2、2×4（`resizeMode="horizontal|vertical"`），所以形态必须跟着
     * 实例的实际尺寸走，而不是跟着安装时的默认值走。
     */
    fun formOf(minWidthDp: Int): Form =
        if (minWidthDp >= WIDE_MIN_WIDTH_DP) Form.WIDE else Form.COMPACT

    /**
     * 这一屏要占的高度。宽卡的钩子在右栏，不占竖向预算，所以只算左栏那四行。
     */
    fun heightOf(
        form: Form,
        hasLabel: Boolean,
        hasDetail: Boolean,
        hasStatus: Boolean,
        hooks: Int,
    ): Int {
        var h = PAD_V_DP * 2 + ROW_TITLE_DP +
            KEYS_LEAD_DP + KEYBAR_DIVIDER_DP + KEYBAR_GAP_DP + ROW_KEYS_DP
        if (hasLabel) h += ROW_LABEL_DP + GAP_LABEL_DP
        if (hasStatus) h += GAP_STATUS_DP + ROW_STATUS_DP
        if (hasDetail) h += GAP_DETAIL_DP + ROW_DETAIL_DP
        if (form == Form.COMPACT && hooks > 0) {
            h += HOOK_LEAD_DP + DIVIDER_DP + HOOK_GAP_DP + hooks.coerceAtMost(COMPACT_MAX_HOOKS) * ROW_HOOK_DP
        }
        return h
    }

    /** 底栏实际占的宽度：`n` 个 20dp 图标 + 间距。 */
    fun bottomBarWidthDp(keys: Int): Int =
        keys * KEY_ICON_DP + (keys - 1).coerceAtLeast(0) * KEY_GAP_DP

    /**
     * 按**像素宽度**收一行字。
     *
     * 为什么不能按「字数」收：同样是十几个字符，「迟到 · 08:05 查过」比「一二三四五六七八九十」
     * 窄得多，一刀切按字数收就会把「查过」前面那个时刻（整句里唯一有时效的半句）截掉。
     * 所以按**四分之一 em** 累计，收在真正溢出的地方：
     *
     * | 字符 | 占 |
     * |---|---|
     * | 汉字、全角标点 | 4 |
     * | 空格 | 1 |
     * | `. , : ; ·  / -` 这类窄标点 | 1 |
     * | 数字、拉丁字母 | 2 |
     *
     * 这是个偏保守的估算（真机上的数字比 0.5em 略窄），宁可早一点点出现省略号，
     * 也不要溢出到卡片外面去。
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

    /** 这一行实际能用的宽度：宽卡要扣掉右栏和两边的间距。 */
    private fun textWidthDp(form: Form, availableWidthDp: Int): Int {
        val content = (availableWidthDp - PAD_H_DP * 2).coerceAtLeast(40)
        return when (form) {
            Form.WIDE -> (content - WIDE_RAIL_DP - 16).coerceAtLeast(40)
            Form.COMPACT -> content
        }
    }

    /**
     * 落成一屏实际要渲染的东西。
     *
     * @param availableHeightDp 这个实例实际分到的高度（`OPTION_APPWIDGET_MIN_HEIGHT`）；
     *   拿不到时给 [DEFAULT_HEIGHT_DP]，此时按最紧的预算排（只留一个块和底栏）。
     * @param availableWidthDp 这个实例实际的宽度（`OPTION_APPWIDGET_MIN_WIDTH`）：
     *   它同时决定形态（≥[WIDE_MIN_WIDTH_DP] 才是宽卡）与每行能写几个字。
     */
    fun plan(
        card: SmartCard,
        form: Form,
        availableHeightDp: Int = DEFAULT_HEIGHT_DP,
        availableWidthDp: Int = DEFAULT_HEIGHT_DP,
    ): Plan {
        val budget = availableHeightDp.coerceAtLeast(DEFAULT_HEIGHT_DP)
        val maxHooks = if (form == Form.COMPACT) COMPACT_MAX_HOOKS else WIDE_MAX_HOOKS

        var label = !card.block.label.isNullOrBlank()
        var status = card.block.statusLine != null
        var detail = !card.block.detail.isNullOrBlank()
        var hooks = card.hooks.size.coerceAtMost(maxHooks)

        fun h(hookCount: Int) = heightOf(form, label, detail, status, hookCount)

        // ① 钩子最先让（它本来就是「有余量才写」的）；宽卡的钩子不占高，所以这里只对紧凑卡生效
        while (hooks > 0 && h(hooks) > budget) hooks--
        // ② 再让细节行（细节随情境变，本来就可以没有）
        if (h(0) > budget) detail = false
        // ③ 状态行最后动；状态要么独占一行，要么这个块整个不出现 —— 绝不和标题挤一行
        if (h(0) > budget) status = false
        // ④ 场景行已经极小，砍到它说明连 110dp 都没有，只能留标题 + 底栏
        if (h(0) > budget) label = false

        // 每行能写几个字按**实例宽度**算，而且按半 em 累计：同样是 17sp 的标题，172dp 的 2×2
        // 放得下 8 个汉字，110dp 的只放得下 4 个 —— 写死一个数字必然有一头出界。
        val textW = textWidthDp(form, availableWidthDp)
        val hookW = if (form == Form.WIDE) WIDE_RAIL_DP else textW

        // 排版前把长文案收一遍。状态行按**整句**收：它已经把「结论 + 时效」拼成一句，
        // 分开收会把「查于 10:12」截掉，那就等于又变回一句没有时刻的断言。
        var block = card.block
        block = block.copy(
            label = fit(block.label, textW, SIZE_LABEL, DETAIL_MAX),
            title = fit(block.title, textW, SIZE_TITLE, TITLE_MAX).orEmpty(),
            detail = fit(block.detail, textW, SIZE_DETAIL, DETAIL_MAX),
            status = fit(block.statusLine, textW, SIZE_STATUS, STATUS_MAX),
            meta = null,
        )
        if (!label) block = block.copy(label = null)
        if (!detail) block = block.copy(detail = null)
        if (!status) block = block.copy(status = null)

        val hookTexts = card.hooks.mapNotNull { fit(it.text, hookW, SIZE_HOOK, HOOK_MAX) }.take(hooks)
        return Plan(
            form = form,
            block = block,
            hooks = hookTexts,
            keys = fitKeys(card.keys, if (form == Form.COMPACT) COMPACT_MAX_KEYS else MAX_KEYS),
            /** 钩子区那条分割线要不要画：紧凑卡有钩子才画，宽卡由竖线分隔。 */
            showHookDivider = form == Form.COMPACT && hookTexts.isNotEmpty(),
            /** 宽卡左右两栏之间那条竖线。 */
            showRailDivider = form == Form.WIDE,
            heightDp = heightOf(
                form = form,
                hasLabel = !block.label.isNullOrBlank(),
                hasDetail = !block.detail.isNullOrBlank(),
                hasStatus = block.statusLine != null,
                hooks = hookTexts.size,
            ),
        )
    }

    /**
     * 底栏放不下时砍哪个：**砍中间那个，保住最右的「刷新」**。
     *
     * 刷新是每个场景都可能要的动作、位置固定不变，砍掉它比砍掉一个场景键更伤；
     * 而场景键里越靠前的越紧急（图书馆那一屏是先「签到/离开」后「取消」）。
     */
    private fun fitKeys(keys: List<SmartKey>, max: Int): List<SmartKey> = when {
        keys.size <= max -> keys
        max <= 1 -> keys.take(max)
        else -> keys.take(max - 1) + keys.last()
    }

    /**
     * 先按宽度收，再按一个字数的硬上限兜一刀。
     *
     * 两道都要：宽度算出的是「这一行物理上装不下」，字数上限防的是「宽度本身就离谱」
     * （某些桌面报上来的尺寸不可信），任何一道单独都不够。
     */
    private fun fit(text: String?, widthDp: Int, textSizeSp: Int, hardMax: Int): String? =
        clamp(clampWidth(text, widthDp, textSizeSp), hardMax)

    /**
     * 按字数收一行字：超了就截断并补一个省略号。
     *
     * 只当**排版保险**用，不改语义 —— 所以截断点后面那个省略号很重要：
     * 用户看到「…」才知道后面还有，而不是学校只给了这些。
     */
    fun clamp(text: String?, max: Int): String? = when {
        text.isNullOrBlank() -> null
        text.length <= max -> text
        max <= 1 -> "…"
        else -> text.take(max - 1) + "…"
    }

    /** 一屏要画的东西（块可能已降级，钩子已按预算裁过）。 */
    data class Plan(
        val form: Form,
        val block: SmartBlock,
        val hooks: List<String>,
        val keys: List<SmartKey>,
        val showHookDivider: Boolean,
        val showRailDivider: Boolean,
        val heightDp: Int,
    )
}
