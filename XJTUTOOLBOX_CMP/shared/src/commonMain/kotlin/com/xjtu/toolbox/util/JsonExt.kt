package com.xjtu.toolbox.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * kotlinx.serialization JsonElement 安全扩展函数
 * 对应原 Gson 版本的 safeString / safeInt / safeDouble 等
 */

fun JsonElement?.safeString(default: String = ""): String =
    if (this == null || this is JsonNull) default
    else try { this.jsonPrimitive.contentOrNull ?: default } catch (_: Exception) { default }

fun JsonElement?.safeStringOrNull(): String? =
    if (this == null || this is JsonNull) null
    else try { this.jsonPrimitive.contentOrNull } catch (_: Exception) { null }

fun JsonElement?.safeDouble(default: Double = 0.0): Double =
    if (this == null || this is JsonNull) default
    else try { this.jsonPrimitive.doubleOrNull ?: default } catch (_: Exception) { default }

fun JsonElement?.safeDoubleOrNull(): Double? =
    if (this == null || this is JsonNull) null
    else try { this.jsonPrimitive.doubleOrNull } catch (_: Exception) { null }

fun JsonElement?.safeInt(default: Int = 0): Int =
    if (this == null || this is JsonNull) default
    else try { this.jsonPrimitive.intOrNull ?: default } catch (_: Exception) { default }

fun JsonElement?.safeBoolean(default: Boolean = false): Boolean =
    if (this == null || this is JsonNull) default
    else try { this.jsonPrimitive.booleanOrNull ?: default } catch (_: Exception) { default }

fun JsonObject.safeGet(key: String): JsonElement? {
    val el = this[key]
    return if (el is JsonNull) null else el
}

fun String?.safeParseJsonObject(): JsonObject {
    if (this.isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    
    // 检查是否返回了 HTML 而非 JSON
    val trimmed = this.trimStart()
    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || 
        trimmed.startsWith("<html", ignoreCase = true)) {
        throw RuntimeException("服务器返回了网页而非数据，请稍后重试")
    }
    
    val element = try {
        kotlinx.serialization.json.Json.parseToJsonElement(this)
    } catch (e: Exception) {
        val preview = this.take(100).replace("\n", " ")
        throw RuntimeException("数据格式错误，无法解析：$preview", e)
    }
    return try { element.jsonObject } catch (_: Exception) {
        val preview = this.take(100).replace("\n", " ")
        throw RuntimeException("服务器返回了非预期的数据格式：$preview")
    }
}

fun String?.safeParseJsonArray(): JsonArray {
    if (this.isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    
    // 检查是否返回了 HTML 而非 JSON
    val trimmed = this.trimStart()
    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || 
        trimmed.startsWith("<html", ignoreCase = true)) {
        throw RuntimeException("服务器返回了网页而非数据，请稍后重试")
    }
    
    val element = try {
        kotlinx.serialization.json.Json.parseToJsonElement(this)
    } catch (e: Exception) {
        val preview = this.take(100).replace("\n", " ")
        throw RuntimeException("数据格式错误，无法解析：$preview", e)
    }
    return try { element.jsonArray } catch (_: Exception) {
        val preview = this.take(100).replace("\n", " ")
        throw RuntimeException("服务器返回了非预期的数据格式：$preview")
    }
}

fun String?.safeParseJson(): JsonElement {
    if (this.isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    
    // 检查是否返回了 HTML 而非 JSON
    val trimmed = this.trimStart()
    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || 
        trimmed.startsWith("<html", ignoreCase = true)) {
        throw RuntimeException("服务器返回了网页而非数据，请稍后重试")
    }
    
    return try {
        kotlinx.serialization.json.Json.parseToJsonElement(this)
    } catch (e: Exception) {
        val preview = this.take(100).replace("\n", " ")
        throw RuntimeException("数据格式错误，无法解析：$preview", e)
    }
}
