package com.xjtu.toolbox.agent

/** 屁岱（Agent）那一族的夹具常量（`docs/api-contract.md` §5.11）。 */
object AgentFixture {

    /** 夹具用的 API Key。 */
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
