package com.xjtu.toolbox.util

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

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
