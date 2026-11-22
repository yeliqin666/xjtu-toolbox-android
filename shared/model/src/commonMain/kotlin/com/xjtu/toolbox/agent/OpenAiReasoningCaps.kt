package com.xjtu.toolbox.agent

/**
 * OpenAI 系模型认的最低推理档位（#427）。按模型 ID 查，不额外发探测请求，写法跟 `AgentVision.supportsVision` 一致。
 *
 * 为什么要有这张表：关思考时以前一律发 `none`，可 GPT-6.2 Sol / Astra 这类模型直接 400，
 * 每次都要白挨一次拒绝再降级重试。现在已知型号直接发它认的最低一档，
 * 只有表里没有的型号才走「先试、400 再降级」的老路。
 *
 * 来源（2026-11 核对，OpenAI 官方文档）：
 * - developers.openai.com/api/docs/guides/reasoning：GPT-6.2 Astra / Sol 拒绝 none；GPT-6.1 Sol 不认 none、minimal；GPT-6 Astra 不认 none。
 * - developers.openai.com/api/docs/guides/latest-model：换到 6.2 时「原来用 none 或 minimal 的改用 low」。
 * - models/gpt-6-luna、models/gpt-6.1-terra：effort 支持 none ~ max。
 * - models/gpt-5.1、gpt-5.2、gpt-5.4、gpt-5.5：支持 none。
 * - models/gpt-5：只支持 minimal、low、medium、high（mini / nano 同代同档）。
 * - o 系列只有 low / medium / high；gpt-4o、gpt-4.1 不是推理模型，不发这个参数。
 */
object OpenAiReasoningCaps {

    /** 最低一档；[wire] 为 null 表示不是推理模型，推理参数一个都别发。 */
    enum class Floor(val wire: String?) {
        NOT_REASONING(null),
        NONE("none"),
        MINIMAL("minimal"),
        LOW("low"),
    }

    /** 允许带日期快照后缀，如 `gpt-6.2-sol-2026-09-30`。 */
    private const val SNAPSHOT = """(-\d{4}-\d{2}-\d{2})?"""

    private val TABLE: List<Pair<Regex, Floor>> = listOf(
        Regex("""gpt-6\.2-(astra|sol)$SNAPSHOT""") to Floor.LOW,
        Regex("""(gpt-6\.1-sol|gpt-6-astra)$SNAPSHOT""") to Floor.LOW,
        Regex("""(gpt-6\.1-terra|gpt-6-luna)$SNAPSHOT""") to Floor.NONE,
        Regex("""gpt-5\.(1|2|4|5)$SNAPSHOT""") to Floor.NONE,
        Regex("""gpt-5(-mini|-nano)?$SNAPSHOT""") to Floor.MINIMAL,
        Regex("""(o1|o3|o3-mini|o4-mini)$SNAPSHOT""") to Floor.LOW,
        Regex("""(gpt-4o|gpt-4o-mini|gpt-4\.1|gpt-4\.1-mini|gpt-4\.1-nano)$SNAPSHOT""") to Floor.NOT_REASONING,
    )

    /** 表里没有返回 null：未知模型，交给调用方的降级重试兜底。中转常见的 `openai/` 前缀会先去掉。 */
    fun floorOf(model: String): Floor? {
        val id = model.trim().lowercase().substringAfterLast('/')
        return TABLE.firstOrNull { it.first.matches(id) }?.second
    }

    /** DeepSeek 有自己的 thinking 开关和 none 档，不查这张表。 */
    fun floorOf(config: AgentConfig): Floor? =
        if (config.provider == AgentConfig.PROVIDER_DEEPSEEK) null else floorOf(config.effectiveModel)
}