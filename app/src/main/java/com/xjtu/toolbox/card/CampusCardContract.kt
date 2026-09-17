package com.xjtu.toolbox.card

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal object CampusCardContract {
    private val INCOME_MARKERS = listOf(
        "充值", "圈存", "退款", "补助", "recharge", "transfer-in", "refund", "subsidy",
    )
    private val EXPENSE_MARKERS = listOf(
        "消费", "支出", "扣款", "consume", "expense", "transfer-out",
        // 食堂窗口扫码支付走的是这条独立通道，不归 consume：抓包实测确认，
        // icon=qrCode-payment / turnoverType=二维码支付，此前两个字段都没覆盖到，
        // 落回默认分支被当成正数收入显示。
        "二维码支付", "qrcode-payment",
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

    /**
     * @param typeFrom 官方 ncard 网页判断收支方向用的字段，反编译其账单页前端代码核实：
     * `"1" === typeFrom` 显示 +，其余一律显示 -。跟猜测 `turnoverType` 展示文案比，
     * 这是权威来源自己用的规则，优先按它来。
     * @param toAccount 这笔钱最终落到的账号；`typeFrom` 缺失时的兜底之一。`fromAccount`
     * 恒为本人账号，不区分方向。抓包实测：充值 `toAccount=0`（钱没转出去），
     * 消费/扫码支付 `toAccount=商户终端账号`（转出去了）。
     * @param fromAccount 本人账号，仅用于跟 [toAccount] 比较是否"钱还是回到自己账上"
     * （圈存一类自转自的场景可能是这种形状，没有实样，按同样道理覆盖）。
     */
    fun signedAmountCents(
        rawAmount: Long,
        typeName: String,
        icon: String,
        typeFrom: String? = null,
        toAccount: Long? = null,
        fromAccount: Long? = null,
    ): Long {
        if (rawAmount < 0) return rawAmount
        if (!typeFrom.isNullOrBlank()) {
            return if (typeFrom == "1") kotlin.math.abs(rawAmount) else -kotlin.math.abs(rawAmount)
        }
        val normalized = "$typeName $icon".lowercase()
        if (INCOME_MARKERS.any { it.lowercase() in normalized }) return kotlin.math.abs(rawAmount)
        if (EXPENSE_MARKERS.any { it.lowercase() in normalized }) return -kotlin.math.abs(rawAmount)
        // typeFrom 缺失、两份关键词也都对不上——大概率是学校又加了个没见过的支付
        // 渠道展示文案。与其盲目当正数收入（这正是这次 bug 的成因），不如看这笔钱实际去哪了。
        if (toAccount != null) {
            val staysOnCard = toAccount == 0L || (fromAccount != null && toAccount == fromAccount)
            return if (staysOnCard) kotlin.math.abs(rawAmount) else -kotlin.math.abs(rawAmount)
        }
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
