package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI Responses API（`/responses`）的请求体换算（#427）。
 *
 * 屁岱内部的消息历史始终按 Chat Completions 的结构存（`role / content / tool_calls`、`role = tool`），
 * 只在发请求的那一刻换算成 Responses 的 `input`。这样落盘、续聊、中途切服务商都不用改。
 *
 * - system / user → `{role, content}`；多段内容里的 `text` / `image_url` 换成 `input_text` / `input_image`。
 * - assistant 的正文 → `{role: assistant, content}`，`tool_calls` 每条拆成一个 `function_call` 条目。
 * - tool 结果 → `{type: function_call_output, call_id, output}`。
 * - `reasoning_content`（别家模型的思维链）不回传：Responses 不认，推理摘要也不需要回传。
 */
object AgentResponsesFormat {

    /** 内部历史 → Responses 的 `input` 数组。 */
    fun input(messages: List<JsonElement>): JsonArray = buildJsonArray {
        messages.forEach { el ->
            val m = el as? JsonObject ?: return@forEach
            when (val role = m.str("role")) {
                "tool" -> add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", m.str("tool_call_id"))
                    put("output", textOf(m["content"]))
                })
                "assistant" -> {
                    val text = textOf(m["content"])
                    if (text.isNotBlank()) add(buildJsonObject {
                        put("role", "assistant")
                        put("content", text)
                    })
                    (m["tool_calls"] as? JsonArray)?.forEach { tc ->
                        val call = tc as? JsonObject ?: return@forEach
                        val fn = call["function"] as? JsonObject
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", call.str("id"))
                            put("name", fn?.str("name").orEmpty())
                            put("arguments", fn?.str("arguments").orEmpty().ifBlank { "{}" })
                        })
                    }
                }
                else -> add(buildJsonObject {
                    put("role", role.ifBlank { "user" })
                    put("content", contentOf(m["content"]))
                })
            }
        }
    }

    /**
     * Chat 的工具定义 `{type, function: {name, description, parameters}}` → Responses 的扁平形状
     * `{type: function, name, description, parameters}`。
     *
     * 显式写 `strict: false`：Responses 上函数默认按严格模式校验 schema，要求所有字段必填、
     * 禁止额外字段，屁岱的工具 schema 不是按这个写的，不关掉会整个请求 400。
     */
    fun tools(chatTools: JsonArray): JsonArray = buildJsonArray {
        chatTools.forEach { el ->
            val tool = el as? JsonObject ?: return@forEach
            val fn = tool["function"] as? JsonObject
            if (fn == null) {
                add(tool)
                return@forEach
            }
            add(buildJsonObject {
                put("type", "function")
                fn["name"]?.let { put("name", it) }
                fn["description"]?.let { put("description", it) }
                fn["parameters"]?.let { put("parameters", it) }
                put("strict", fn["strict"] ?: JsonPrimitive(false))
            })
        }
    }

    /**
     * 设置页的强度档 → OpenAI 的 `reasoning.effort`。自动档不发（服务端按模型默认）；
     * 「最大」落到 high：xhigh 不是每个推理模型都认，发了不认的值整个请求 400。
     */
    fun effortOf(config: AgentConfig): String? = when (config.reasoningEffort) {
        AgentConfig.REASONING_LOW -> "low"
        AgentConfig.REASONING_HIGH, AgentConfig.REASONING_MAX -> "high"
        else -> null
    }

    /**
     * 一轮流式请求的完整请求体。
     *
     * - `store: false`：不让 OpenAI 在服务端留存对话。
     * - 开了思考才带 `reasoning`（`summary: auto` 才会返回推理摘要给「思考」区）；
     *   非推理模型不认这个字段，传输层撞上 400 会去掉它重试一次。
     */
    fun request(config: AgentConfig, messages: List<JsonElement>, chatTools: JsonArray?): JsonObject = buildJsonObject {
        put("model", config.effectiveModel)
        put("input", input(messages))
        put("stream", true)
        put("store", false)
        if (config.thinkingEnabled) {
            put("reasoning", buildJsonObject {
                effortOf(config)?.let { put("effort", it) }
                put("summary", "auto")
            })
        }
        if (chatTools != null && chatTools.isNotEmpty()) {
            put("tools", tools(chatTools))
            put("tool_choice", "auto")
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** content 可能是字符串，也可能是多段数组；只要文字。 */
    private fun textOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { part ->
            (part as? JsonObject)?.takeIf { it.str("type") == "text" || it.str("type") == "input_text" }?.str("text")
        }.joinToString("\n")
        else -> ""
    }

    /** user / system 的 content：字符串原样；多段数组逐段换成 Responses 的写法。 */
    private fun contentOf(content: JsonElement?): JsonElement = when (content) {
        is JsonArray -> buildJsonArray {
            content.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                when (p.str("type")) {
                    "text" -> add(buildJsonObject {
                        put("type", "input_text")
                        put("text", p.str("text"))
                    })
                    "image_url" -> {
                        val url = (p["image_url"] as? JsonObject)?.str("url") ?: p.str("image_url")
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", url)
                        })
                    }
                    else -> add(p)
                }
            }
        }
        is JsonPrimitive -> if (content.isString) content else JsonPrimitive("")
        else -> JsonPrimitive("")
    }
}
