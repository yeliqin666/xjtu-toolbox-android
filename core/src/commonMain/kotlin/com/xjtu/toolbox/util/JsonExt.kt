package com.xjtu.toolbox.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 全 App 共用的 JSON 配置：学校接口字段多变、常夹 null，本地缓存也要读得回旧版本写的数据，
 * 所以忽略未知字段、null 落回默认值。写出格式与旧版（Gson）一致：写出默认值、不写出 null，
 * 装回旧版本也读得懂新写的缓存和联机报文。
 */
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    allowSpecialFloatingPointValues = true
}

private val JsonElement?.primitive: JsonPrimitive?
    get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

// ── 宽松取值：缺字段、null、类型不对都给默认值 ──

fun JsonElement?.safeString(default: String = ""): String = primitive?.content ?: default

fun JsonElement?.safeStringOrNull(): String? = primitive?.content

fun JsonElement?.safeDouble(default: Double = 0.0): Double = safeDoubleOrNull() ?: default

fun JsonElement?.safeDoubleOrNull(): Double? = primitive?.content?.toDoubleOrNull()

fun JsonElement?.safeInt(default: Int = 0): Int = safeLongOrNull()?.toInt() ?: default

fun JsonElement?.safeLong(default: Long = 0L): Long = safeLongOrNull() ?: default

private fun JsonElement?.safeLongOrNull(): Long? =
    primitive?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

fun JsonElement?.safeBoolean(default: Boolean = false): Boolean =
    primitive?.content?.let { it.equals("true", ignoreCase = true) } ?: default

// ── 严格取值：缺字段、null 或不是标量就抛异常，交给外层的错误处理 ──

val JsonElement?.stringValue: String
    get() = primitive?.content ?: throw IllegalStateException("不是字符串：$this")

val JsonElement?.intValue: Int get() = longValue.toInt()

val JsonElement?.longValue: Long
    get() = safeLongOrNull() ?: throw IllegalStateException("不是整数：$this")

val JsonElement?.doubleValue: Double
    get() = safeDoubleOrNull() ?: throw IllegalStateException("不是数字：$this")

val JsonElement?.booleanValue: Boolean
    get() = primitive?.content?.equals("true", ignoreCase = true) ?: throw IllegalStateException("不是布尔：$this")

// ── 结构访问 ──

val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull
val JsonElement?.isObject: Boolean get() = this is JsonObject
val JsonElement?.isArray: Boolean get() = this is JsonArray
val JsonElement?.isPrimitive: Boolean get() = this is JsonPrimitive && this !is JsonNull

/** 取字段；JSON null 当作没有。 */
fun JsonObject.safeGet(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 必需的字段：缺了说明接口变了，抛出带字段名的异常。 */
fun JsonObject.requireObj(key: String): JsonObject = obj(key) ?: throw IllegalStateException("缺少字段 $key")
fun JsonObject.requireArr(key: String): JsonArray = arr(key) ?: throw IllegalStateException("缺少字段 $key")

/** 把请求参数这类 Map / List / 标量的组合转成 JSON；不认识的类型按 toString() 写成字符串。 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

// ── 解析：服务器返回空串、HTML 页、非 JSON 时给出能看懂的错误 ──

fun String?.safeParseJson(): JsonElement {
    if (isNullOrBlank()) throw RuntimeException("服务器返回空数据")
    val trimmed = trimStart()
    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
        throw RuntimeException("服务器返回了网页而非数据，请稍后重试")
    }
    return try {
        AppJson.parseToJsonElement(this)
    } catch (e: Exception) {
        throw RuntimeException("数据格式错误，无法解析：${preview()}", e)
    }
}

fun String?.safeParseJsonObject(): JsonObject =
    safeParseJson() as? JsonObject ?: throw RuntimeException("服务器返回了非预期的数据格式：${preview()}")

private fun String?.preview() = orEmpty().take(100).replace("\n", " ")

val JsonPrimitive.isNumber: Boolean get() = !isString && this !is JsonNull && content.toDoubleOrNull() != null
val JsonPrimitive.isBoolean: Boolean get() = !isString && (content == "true" || content == "false")
