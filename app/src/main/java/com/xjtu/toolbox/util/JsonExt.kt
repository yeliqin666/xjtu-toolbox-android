package com.xjtu.toolbox.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Gson JsonElement 安全扩展函数
 * 处理 JSON null 值（服务器返回 "field": null 时，get() 返回 JsonNull 实例而非 Kotlin null）
 */

fun JsonElement?.safeString(default: String = ""): String =
    if (this == null || this is JsonNull) default else try { this.asString } catch (_: Exception) { default }

fun JsonElement?.safeStringOrNull(): String? =
    if (this == null || this is JsonNull) null else try { this.asString } catch (_: Exception) { null }

fun JsonElement?.safeDouble(default: Double = 0.0): Double =
    if (this == null || this is JsonNull) default else try { this.asDouble } catch (_: Exception) { default }

fun JsonElement?.safeDoubleOrNull(): Double? =
    if (this == null || this is JsonNull) null else try { this.asDouble } catch (_: Exception) { null }

fun JsonElement?.safeInt(default: Int = 0): Int =
    if (this == null || this is JsonNull) default else try { this.asInt } catch (_: Exception) { default }

fun JsonElement?.safeBoolean(default: Boolean = false): Boolean =
    if (this == null || this is JsonNull) default else try { this.asBoolean } catch (_: Exception) { default }

/** 安全获取 JsonObject 的字段 */
fun JsonObject.safeGet(key: String): JsonElement? {
    val el = this.get(key)
    return if (el is JsonNull) null else el
}

// ── 安全 JSON 解析（防止服务器返回 HTML/空字符串/非 JSON 时崩溃） ──

/** 安全解析 JSON 字符串为 JsonObject，失败时抛出明确错误信息 */
fun String?.safeParseJsonObject(): JsonObject {
    if (this.isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    val element = try {
        JsonParser.parseString(this)
    } catch (e: Exception) {
        throw RuntimeException("服务器返回非JSON数据: ${this.take(200)}", e)
    }
    if (!element.isJsonObject) {
        throw RuntimeException("预期JSON对象但收到其他类型: ${this.take(200)}")
    }
    return element.asJsonObject
}

/** 安全解析 JSON 字符串为 JsonArray，失败时抛出明确错误信息 */
fun String?.safeParseJsonArray(): JsonArray {
    if (this.isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    val element = try {
        JsonParser.parseString(this)
    } catch (e: Exception) {
        throw RuntimeException("服务器返回非JSON数据: ${this.take(200)}", e)
    }
    if (!element.isJsonArray) {
        throw RuntimeException("预期JSON数组但收到其他类型: ${this.take(200)}")
    }
    return element.asJsonArray
}

/** 安全解析 JSON 字符串为 JsonElement，失败时抛出明确错误信息 */
fun String?.safeParseJson(): JsonElement {
    if (this.isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    return try {
        JsonParser.parseString(this)
    } catch (e: Exception) {
        throw RuntimeException("服务器返回非JSON数据: ${this.take(200)}", e)
    }
}
