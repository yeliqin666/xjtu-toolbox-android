package com.xjtu.toolbox.agent

/**
 * 屁岱（Agent）那一族的**夹具常量**（`docs/api-contract.md` §5.11 / D10）。
 *
 * 与 `:testkit` 其余夹具同一条纪律：**只有 JDK 自己的东西、不依赖任何工程模块**
 *（`testkit/build.gradle.kts` 的 KDoc 记着这条）—— 所以这里是纯字符串 / 数字常量，
 * 不是 `AgentConfig` 之类的对象（那些在 `:core`）。
 *
 * ## 这一族为什么用常量当夹具
 *
 * 它**不打学校上游**：循环要的那一枪（模型）在测试里由 `:server:test` 里的**夹具传输**演，
 * 三份落盘走的是真 store（数据根指到临时目录）。所以夹具要给的只有两样东西：
 *
 * 1. **一把编造的 API Key**（[FAKE_API_KEY]）—— 红线断言靠它：「响应里（含 SSE 的每一帧）
 *    没有这一串」这件事，只有在**夹具里明明有它**的前提下才证明得了什么
 *    （`docs/api-contract.md` §5.2 的那条写法）；
 * 2. 其余**编造**的值（型号、助手名、会话标题、记忆键值、回复正文、工具名、token 数）。
 *
 * 夹具里的值一个都不是真的：钥匙打不通任何服务商，学号 / 手机号一个都没有
 * （这一族**不该**有用得上的身份字段 —— 那正是红线要的结论）。
 */
object AgentFixture {

    /**
     * 夹具用的 API Key。**任何响应体（含 SSE 每一帧）里都不许出现它。**
     *
     * 前缀 `sk-` 只为了让「它看起来像一把 key」这件事成立（万一哪天有人把它打印出来，
     * 一眼能认出来源），本身不是任何服务商的真格式。
     */
    const val FAKE_API_KEY = "sk-fixture-agent-key-not-a-real-one"

    /** 服务商 / 型号 / 搜索源：与 `AgentConfig` 的取值同一个字母表（`deepseek` 等），但型号是编的。 */
    const val PROVIDER = "deepseek"
    const val MODEL = "fixture-model-v1"
    const val SEARCH_ENGINE = "auto"
    const val ASSISTANT_NAME = "夹具屁岱"

    /** 服务商给回来的模型列表（`POST /api/agent/models` 的夹具那一枪）。 */
    val MODELS = listOf("fixture-model-v1", "fixture-model-v2")

    /** 新会话的默认标题（`AgentSessionStore.DEFAULT_TITLE` 同一个串）与夹具自己起的标题。 */
    const val DEFAULT_SESSION_TITLE = "新对话"
    const val SESSION_TITLE = "夹具起的标题"

    /** 一条记忆（`GET /api/agent/memory` 那两格）。 */
    const val MEMORY_KEY = "常去的校区"
    const val MEMORY_VALUE = "兴庆"

    /** 一轮对话里发出去的那句话、模型回的正文、思维链。 */
    const val USER_TEXT = "夹具问的那一句"
    const val REPLY_TEXT = "夹具回的那一句"
    const val REASONING_TEXT = "夹具的思维链"

    /** 模型回的两段增量（验「逐段上屏」而不是一次给全）。 */
    val DELTAS = listOf("夹具", "回的那一句")

    /** 夹具模型回报的累计 token（远低于 800k 阈值 ⇒ 不该判成上下文耗尽）。 */
    const val TOTAL_TOKENS = 1234L

    /** 超过 800k 阈值那个数（验「服务端判、`done` 带出来」）。 */
    const val OVER_LIMIT_TOKENS = 800_000L

    /** 夹具工具表里那一个工具的名字（与 `:server:test` 里夹具工具表的定义同一个串）。 */
    const val TOOL_NAME = "fixture_tool"

    /** 夹具工具执行成功 / 失败时的结果。 */
    const val TOOL_RESULT = "夹具工具的结果"
    const val TOOL_ERROR = "夹具工具炸了"

    /** 夹具那一枪打模型时抛的失败文案（走 `error` 事件那一档）。 */
    const val LLM_FAILURE = "夹具里的 LLM 失败了"

    /** MFA 夹具里的站点名与**手机号**：手机号绝不许出现在任何响应里（红线 §5.1）。 */
    const val MFA_SITE_NAME = "夹具图书馆"
    const val MFA_PHONE_NUMBER = "13800000000"
}
