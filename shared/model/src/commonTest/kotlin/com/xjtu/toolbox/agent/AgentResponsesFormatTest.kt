package com.xjtu.toolbox.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentResponsesFormatTest {

    private fun arr(s: String): JsonArray = Json.parseToJsonElement(s).jsonArray

    @Test
    fun 强度档对应OpenAI的effort() {
        assertNull(AgentResponsesFormat.effortOf(AgentConfig()))
        assertEquals("low", AgentResponsesFormat.effortOf(AgentConfig(reasoningEffort = AgentConfig.REASONING_LOW)))
        assertEquals("high", AgentResponsesFormat.effortOf(AgentConfig(reasoningEffort = AgentConfig.REASONING_HIGH)))
        assertEquals("high", AgentResponsesFormat.effortOf(AgentConfig(reasoningEffort = AgentConfig.REASONING_MAX)))
    }

    @Test
    fun 带正文的assistant与多个工具调用都保留顺序() {
        val input = AgentResponsesFormat.input(arr("""[
          {"role":"assistant","content":"我查一下","tool_calls":[
            {"id":"a","type":"function","function":{"name":"x","arguments":""}},
            {"id":"b","type":"function","function":{"name":"y","arguments":"{\"k\":1}"}}]},
          {"role":"tool","tool_call_id":"a","content":"1"},
          {"role":"tool","tool_call_id":"b","content":"2"}
        ]"""))
        val types = input.map { it.jsonObject["type"]?.jsonPrimitive?.content ?: it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("assistant", "function_call", "function_call", "function_call_output", "function_call_output"), types)
        // 空参数补成 {}：Responses 不收空串
        assertEquals("{}", input[1].jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("b", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun 已经是扁平形状的工具原样保留() {
        val tools = AgentResponsesFormat.tools(arr("""[{"type":"function","name":"z","parameters":{}}]"""))
        assertEquals("z", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }
}
