package com.xjtu.toolbox.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RegisteredPluginTool(val record: PluginRecord, val tool: PluginToolManifest) {
    // IDs only contain lowercase letters, digits, underscores and dots. Length separates the two names.
    val name = "plugin_${record.id.length}_${record.id.replace('.', 'D')}_${tool.name}"
    private val function = tool.definition["function"] as JsonObject
    private val parameters = function["parameters"] as? JsonObject

    fun definition(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", JsonObject(function + mapOf(
            "name" to JsonPrimitive(name),
            "description" to JsonPrimitive("插件「${record.manifest.name}」：${function["description"].stringValue.orEmpty()}"),
        )))
    }

    fun execute(argsJson: String, call: (String) -> String): String {
        // The v2 SDK allows empty arguments. Keep forwarding them as-is for older implementations.
        val empty = argsJson.isBlank()
        val args = if (empty) JsonObject(emptyMap()) else {
            runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
                ?: return "error: invalid_arguments; tool: $name; reason: expected a JSON object"
        }
        if (!args.isJsonValue()) return "error: invalid_arguments; tool: $name; reason: invalid JSON literal"
        parameters?.argumentError(args, "arguments")?.let {
            return "error: invalid_arguments; tool: $name; reason: $it"
        }
        // Forward the validated object, including when the input contained duplicate keys.
        return call(if (empty) argsJson else args.toString())
    }
}

internal fun List<PluginRecord>.registeredTools(): Sequence<RegisteredPluginTool> = asSequence()
    .filter { it.enabled && it.toolsEnabled && !it.manifest.isWeb && it.manifest.apiVersionError() == null }
    .flatMap { record ->
        record.manifest.tools.asSequence()
            .filter { it.name !in record.disabledTools && it.definitionError() == null }
            .map { RegisteredPluginTool(record, it) }
    }

internal fun PluginToolManifest.definitionError(): String? {
    if (!definition.isJsonValue()) return "definition 包含无效的 JSON 值"
    val function = definition["function"] as? JsonObject ?: return "definition.function 必须是对象"
    if (definition["type"].stringValue != "function") return "definition.type 必须是 function"
    if (function["name"].stringValue != name) return "definition.function.name 必须与工具名一致"
    if ("description" in function && function["description"].stringValue == null) return "description 必须是字符串"
    val parameters = function["parameters"] ?: return null
    if (parameters !is JsonObject || parameters["type"].stringValue != "object") return "parameters 必须是 object schema"
    return parameters.schemaError("parameters")
}

private val schemaTypes = setOf("object", "array", "string", "integer", "number", "boolean", "null")
private val schemaKeys = setOf(
    "type", "properties", "required", "additionalProperties", "items", "enum", "const",
    "title", "description", "default", "examples", "\$comment",
)
private val JsonElement?.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private val JsonElement?.boolValue: Boolean?
    get() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private fun JsonElement.isJsonValue(depth: Int = 0): Boolean {
    if (depth > 64) return false
    return when (this) {
        is JsonObject -> values.all { it.isJsonValue(depth + 1) }
        is JsonArray -> all { it.isJsonValue(depth + 1) }
        is JsonPrimitive -> isString || this == JsonNull || boolValue != null || jsonNumber.matches(content)
    }
}

private fun JsonObject.schemaError(path: String, depth: Int = 0): String? {
    if (depth > 32) return "$path 嵌套太深"
    keys.firstOrNull { it !in schemaKeys }?.let { return "$path.$it 暂不支持" }
    if ("type" in this && this["type"].stringValue !in schemaTypes) return "$path.type 无效"
    val properties = this["properties"]
    if (properties != null && properties !is JsonObject) return "$path.properties 必须是对象"
    for ((key, value) in (properties as? JsonObject).orEmpty()) {
        val propertyPath = "$path.properties.$key"
        if (value !is JsonObject) return "$propertyPath 必须是 schema 对象"
        value.schemaError(propertyPath, depth + 1)?.let { return it }
    }
    val required = this["required"]
    if (required != null && (required !is JsonArray || required.any { it.stringValue == null } ||
            required.distinct().size != required.size)) return "$path.required 必须是无重复的字符串数组"
    val additional = this["additionalProperties"]
    if (additional is JsonObject) additional.schemaError("$path.additionalProperties", depth + 1)?.let { return it }
    else if (additional != null && additional.boolValue == null) return "$path.additionalProperties 必须是布尔值或 schema 对象"
    val items = this["items"]
    if (items is JsonObject) items.schemaError("$path.items", depth + 1)?.let { return it }
    else if (items != null) return "$path.items 必须是 schema 对象"
    val allowed = this["enum"]
    if (allowed != null && (allowed !is JsonArray || allowed.isEmpty())) return "$path.enum 必须是非空数组"
    return null
}

private fun JsonObject.argumentError(value: JsonElement, path: String): String? {
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
    val matchesType = when (this["type"].stringValue) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value.stringValue != null
        "integer" -> number != null && (number.scale() <= 0 || number.stripTrailingZeros().scale() <= 0)
        "number" -> number != null
        "boolean" -> value.boolValue != null
        "null" -> value == JsonNull
        else -> true
    }
    if (!matchesType) return "$path must be ${this["type"].stringValue}"
    val allowed = this["enum"] as? JsonArray
    if (allowed != null && allowed.none { it.sameJsonValue(value) }) return "$path is not an allowed value"
    this["const"]?.let { if (!it.sameJsonValue(value)) return "$path does not match const" }
    if (value is JsonObject) {
        val required = this["required"] as? JsonArray
        required?.firstOrNull { it.stringValue !in value.keys }?.let { return "$path.${it.stringValue} is required" }
        val properties = this["properties"] as? JsonObject
        val additional = this["additionalProperties"]
        for ((key, argument) in value) {
            val known = properties?.get(key) as? JsonObject
            if (known == null && additional.boolValue == false) return "$path.$key is not allowed"
            val schema = known ?: additional as? JsonObject
            schema?.argumentError(argument, "$path.$key")?.let { return it }
        }
    }
    if (value is JsonArray) {
        val items = this["items"] as? JsonObject
        if (items != null) value.forEachIndexed { index, item ->
            items.argumentError(item, "$path[$index]")?.let { return it }
        }
    }
    return null
}

private fun JsonElement.sameJsonValue(other: JsonElement): Boolean = when {
    this == other -> true
    this is JsonObject && other is JsonObject -> keys == other.keys && all { (key, value) -> value.sameJsonValue(other.getValue(key)) }
    this is JsonArray && other is JsonArray -> size == other.size && indices.all { this[it].sameJsonValue(other[it]) }
    this is JsonPrimitive && other is JsonPrimitive && !isString && !other.isString ->
        content.toBigDecimalOrNull()?.let { a -> other.content.toBigDecimalOrNull()?.let { b -> a.compareTo(b) == 0 } } == true
    else -> false
}
