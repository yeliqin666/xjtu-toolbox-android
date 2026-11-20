package com.xjtu.toolbox.widget.smart

/**
 * 把判出来的一屏塞进卡片里：选形态、算高度预算、不够就**按块降级**。
 *
 * 2026-11-18 之前卡片真的会被挤出去：`widget_smart.xml` 里主区各行都是 `wrap_content`、
 * 全书没有一处 `layout_weight`，最坏情况量出来约 181dp，而 `smart_widget_info.xml` 的
 * `minHeight` 只有 110dp —— 超出的约 70dp 正好把底栏顶出卡片（§1.3）。
 *
 * 两条根因各有一条对策，都在这里定下来：
 * 1. **布局侧**：主区 `height=0dp` + `weight=1`、底栏 `wrap_content` 锚底（见两套布局），
 *    于是内容再多也只会被裁，不会把底栏挤出去；
 * 2. **内容侧**：这一层按确定的预算（[CARD_HEIGHT_DP] 与下面那组行高常量）算出能放几条钩子，
 *    放不下就按 §8.1 的顺序砍。行高写死在这里而不是量出来，是因为**预算要能在单测里断言**，
 *    量出来的东西只有真机能验。
 */
object SmartLayout {

    /** 卡片最小高度：`smart_widget_info.xml` 的 `minHeight`，也是「不能挤出」的红线。 */
    const val CARD_HEIGHT_DP = 110

    /** 上下内边距**各** [PAD_DP]。 */
    const val PAD_DP = 4

    const val ROW_LABEL_DP = 13
    const val ROW_TITLE_DP = 22
    const val ROW_DETAIL_DP = 14
    const val ROW_STATUS_DP = 17
    const val ROW_HOOK_DP = 14

    /** 底栏：24dp 图标的触控区压到 20dp，整行 22dp。 */
    const val ROW_KEYS_DP = 22
    const val KEY_ICON_DP = 20
    const val KEY_GAP_DP = 8

    /** 底栏放得下的图标数（§5.6 的宽度预算：96dp 里 24dp×3 + 8dp×2 = 88dp）。 */
    const val MAX_KEYS = 3

    /** 紧凑卡的钩子行：最宽裕时也就 3 条。 */
    const val COMPACT_MAX_HOOKS = 3

    /** 宽卡右栏能竖着排几条钩子（4 × 14dp = 56dp，比左栏 66dp 还矮）。 */
    const val WIDE_MAX_HOOKS = 4

    /** 从这个宽度起算「宽卡」（§7.2 / §7.4）。 */
    const val WIDE_MIN_WIDTH_DP = 220

    /**
     * 各行的字数上限。
     *
     * 为什么在 Kotlin 侧截断而不是只靠 `ellipsize="end"`：不同 ROM 的 `TextView` 省略号行为
     * 不一致（有的把整行抹成「…」，有的干脆溢出到下一行被裁），而卡片只有 110dp 高，
     * 一行溢出就是把下面那行顶掉。所以**先按字数收一遍**，`ellipsize` 只当第二道保险。
     */
    const val TITLE_MAX = 18
    const val DETAIL_MAX = 22
    const val STATUS_MAX = 24
    const val HOOK_MAX = 14

    /** 两个形态：一套布局一个（成对的 `widget_smart_compact.xml` / `widget_smart_wide.xml`）。 */
    enum class Form { COMPACT, WIDE }

    /**
     * 卡片实例长宽给的是「能放下多少 dp」，按宽度挑形态。
     *
     * 用户可以把它拖成 4×2、2×4（`resizeMode="horizontal|vertical"`），所以形态必须跟着
     * 实例的实际尺寸走，而不是跟着安装时的默认值走。
     */
    fun formOf(minWidthDp: Int): Form =
        if (minWidthDp >= WIDE_MIN_WIDTH_DP) Form.WIDE else Form.COMPACT

    /** 这一屏的高度预算。宽卡左栏只有块，钩子挂在右栏、不占高。 */
    fun heightDp(
        form: Form,
        hasLabel: Boolean,
        hasDetail: Boolean,
        hasStatus: Boolean,
        hooks: Int,
    ): Int {
        val block = (if (hasLabel) ROW_LABEL_DP else 0) +
            ROW_TITLE_DP +
            (if (hasDetail) ROW_DETAIL_DP else 0) +
            (if (hasStatus) ROW_STATUS_DP else 0)
        val hookPart = if (form == Form.COMPACT) hooks.coerceAtMost(COMPACT_MAX_HOOKS) * ROW_HOOK_DP else 0
        return PAD_DP * 2 + block + hookPart + ROW_KEYS_DP
    }

    /**
     * 底栏实际占的宽度（§5.6 的账）：图标 + 间距。
     *
     * 紧凑卡可用宽度是 110 − 左右内边距各 4 = 102dp，所以「3 个纯图标」是硬上限：
     * 4 个要 112dp，带两个文字标签要 106dp —— 都放不下。
     */
    fun bottomBarWidthDp(keys: Int): Int =
        keys * KEY_ICON_DP + (keys - 1).coerceAtLeast(0) * KEY_GAP_DP

    /**
     * 落成一屏实际要渲染的东西。**不够就整块整块地砍**（§8.1），不留半块：
     * 砍顺序是「多余的钩子 → 钩子全砍 → 细节行 → 状态行」，标题、场景行、底栏永远不砍。
     */
    fun plan(card: SmartCard, form: Form): Plan {
        val maxHooks = if (form == Form.COMPACT) COMPACT_MAX_HOOKS else WIDE_MAX_HOOKS
        val hooks = card.hooks.map { clamp(it.text, HOOK_MAX)!! }.take(maxHooks)
        val keys = card.keys.take(MAX_KEYS)
        var block = card.block

        fun fits(candidate: SmartBlock, hookCount: Int): Boolean = heightDp(
            form = form,
            hasLabel = !candidate.label.isNullOrBlank(),
            hasDetail = !candidate.detail.isNullOrBlank(),
            hasStatus = candidate.statusLine != null,
            hooks = hookCount,
        ) <= CARD_HEIGHT_DP

        var used = hooks.size
        while (used > 0 && !fits(block, used)) used--
        // 钩子让完还放不下，才动细节行（细节随情境变，本来就可以没有）
        if (!fits(block, 0)) block = block.copy(detail = null)
        // 状态行最后才动；状态要么独占一行，要么这个块整个不出现 —— 绝不和标题挤一行
        if (!fits(block, 0)) block = block.copy(status = null, meta = null)

        // 排版前把长文案收一遍（见 TITLE_MAX 那组常量的注释）。
        // 状态行按**整句**收：它已经把「结论 + 时效」拼成一句，分开收会把「查于 10:12」截掉，
        // 那就等于又变回一句没有时刻的断言。
        // 标题留空也不许炸：脏数据（教务给空课名）上面已经各自兜过一层，这里是最后一道。
        block = block.copy(
            label = clamp(block.label, DETAIL_MAX),
            title = clamp(block.title, TITLE_MAX).orEmpty(),
            detail = clamp(block.detail, DETAIL_MAX),
            status = clamp(block.statusLine, STATUS_MAX),
            meta = null,
        )

        return Plan(
            form = form,
            block = block,
            hooks = hooks.take(used),
            keys = keys,
            heightDp = heightDp(
                form = form,
                hasLabel = !block.label.isNullOrBlank(),
                hasDetail = !block.detail.isNullOrBlank(),
                hasStatus = block.statusLine != null,
                hooks = used,
            ),
        )
    }

    /**
     * 按字数收一行字：超了就截断并补一个省略号。
     *
     * 只当**排版保险**用，不改语义 —— 所以截断点后面那个省略号很重要：
     * 少一个字符就少一个字的可信度，用户看到「…」才知道后面还有、不是学校只给了这些。
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
        val heightDp: Int,
    )
}
