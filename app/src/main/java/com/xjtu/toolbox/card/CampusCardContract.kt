package com.xjtu.toolbox.card

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal object CampusCardContract {
    private val INCOME_MARKERS = listOf(
        "充值", "圈存", "退款", "补助", "recharge", "transfer", "refund", "subsidy",
    )
    private val EXPENSE_MARKERS = listOf(
        "消费", "支出", "扣款", "consume", "expense",
    )

    fun businessCode(root: JsonObject): String? {
        val el = root.get("code") ?: return null
        if (el.isJsonNull) return null
        return runCatching { el.asString.trim() }.getOrNull()
            ?: runCatching { el.asInt.toString() }.getOrNull()
    }

    fun requireSuccess(root: JsonObject, operation: String) {
        val code = businessCode(root)
        if (code != "200") {
            val message = root.get("message")?.takeUnless { it.isJsonNull }?.asString ?: "业务请求失败"
            throw RuntimeException("${operation}失败：$message")
        }
    }

    fun requireDataObject(root: JsonObject, operation: String): JsonObject {
        val data = root.get("data")
        if (data == null || !data.isJsonObject) {
            throw RuntimeException("${operation}返回的数据格式错误")
        }
        return data.asJsonObject
    }

    fun requireArray(data: JsonObject, key: String, operation: String): JsonArray {
        val values = data.get(key)
        if (values == null || !values.isJsonArray) {
            throw RuntimeException("${operation}返回的${key}格式错误")
        }
        return values.asJsonArray
    }

    fun requireLong(value: JsonElement?, field: String, operation: String): Long {
        if (value == null || value.isJsonNull) {
            throw RuntimeException("${operation}返回的${field}格式错误")
        }
        if (!value.isJsonPrimitive) {
            throw RuntimeException("${operation}返回的${field}格式错误")
        }
        val primitive = value.asJsonPrimitive
        if (primitive.isBoolean) {
            throw RuntimeException("${operation}返回的${field}格式错误")
        }
        if (primitive.isNumber) {
            return primitive.asLong
        }
        val text = primitive.asString.trim()
        if (text.isNotEmpty() && text.removePrefix("-").all { it.isDigit() }) {
            return text.toLong()
        }
        throw RuntimeException("${operation}返回的${field}格式错误")
    }

    fun requiredText(data: JsonObject, field: String, operation: String): String {
        val value = data.get(field)
        if (value == null || value.isJsonNull) {
            throw RuntimeException("${operation}缺少必要字段")
        }
        if (!value.isJsonPrimitive || value.asJsonPrimitive.isBoolean) {
            throw RuntimeException("${operation}缺少必要字段")
        }
        val text = runCatching { value.asString.trim() }.getOrElse {
            runCatching { value.asLong.toString() }.getOrDefault("")
        }
        if (text.isBlank()) throw RuntimeException("${operation}缺少必要字段")
        return text
    }

    fun signedAmountCents(rawAmount: Long, typeName: String, icon: String): Long {
        if (rawAmount < 0) return rawAmount
        val normalized = "$typeName $icon".lowercase()
        if (INCOME_MARKERS.any { it.lowercase() in normalized }) return kotlin.math.abs(rawAmount)
        if (EXPENSE_MARKERS.any { it.lowercase() in normalized }) return -kotlin.math.abs(rawAmount)
        return rawAmount
    }

    fun isAuthFailureBody(body: String): Boolean {
        if (Regex(""""code"\s*:\s*"?401"?""").containsMatchIn(body)) return true
        if ("其他设备" in body || "未登录" in body) return true
        if (body.contains("Unauthorized", ignoreCase = true)) return true
        return body.contains("token", ignoreCase = true) && body.contains("过期")
    }

    fun looksLikeMobileRequired(body: String): Boolean = "移动端模式" in body
}
