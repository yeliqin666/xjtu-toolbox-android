package com.xjtu.toolbox.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.xjtu.toolbox.util.safeStringOrNull

/** 工具清单那两个构造器（[agentTool] / [agentParams] 与它们的参数类型）和三个取参访问器。 */
fun agentTool(name: String, description: String, params: JsonObject = agentEmptyParams()): JsonObject =
    buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", params)
        })
    }

fun agentEmptyParams(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(emptyMap()))
    put("required", JsonArray(emptyList()))
}

/** [required] 列出必填参数：模型少填时不必白跑一轮再收到 missing_param。 */
fun agentParams(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
    put("required", JsonArray(required.map { JsonPrimitive(it) }))
}

fun agentStrProp(description: String): JsonObject = agentPropOf("string", description)
/** 取值固定的字符串参数。 */
fun agentEnumProp(description: String, vararg values: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", JsonArray(values.map { JsonPrimitive(it) }))
}
fun agentIntProp(description: String): JsonObject = agentPropOf("integer", description)
fun agentBoolProp(description: String): JsonObject = agentPropOf("boolean", description)
/** 旧调用兼容字段，发送工具表时移除。 */
fun agentShow(): Pair<String, JsonObject> = ToolShow.PARAM to agentBoolProp(ToolShow.DESCRIPTION)
fun agentPropOf(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

// 工具参数：模型偶尔把数字写成字符串，按内容解析
fun JsonObject.argStr(key: String): String? = this[key].safeStringOrNull()
fun JsonObject.argInt(key: String): Int? = this[key].safeStringOrNull()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
fun JsonObject.argBool(key: String): Boolean? = this[key].safeStringOrNull()?.let { it.equals("true", ignoreCase = true) }
