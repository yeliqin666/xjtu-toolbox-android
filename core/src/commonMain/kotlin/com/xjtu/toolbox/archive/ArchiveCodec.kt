package com.xjtu.toolbox.archive

import com.xjtu.toolbox.util.AppJson
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 档案的 codec：`manifest.json` 与 `stores/<store>.json` 的读写，以及 §2.2 的排序稳定性、§5.2 第 5 条的 `#written_at` 归一化。 */
object ArchiveCodec {

    /** §3.3：桌面/web 的 `KeyValueScheduleCacheStore` 旁挂时间戳键的后缀。 */
    const val WRITTEN_AT_SUFFIX = "#written_at"

    /** 解码一个 store 文件的完整结果（base/suffix 从文件内容取，rawName 据此重建）。 */
    data class StoreRecord(val base: String, val suffix: String, val snapshot: StoreSnapshot)

    // ── 值 ↔ `{t, v, at?}`（§2.2） ──

    /** 一个键的带类型值 + 可选 `at`。 */
    private fun encodeValue(value: StoreValue, at: Long?): JsonObject {
        val v: JsonElement = when (value) {
            is StoreValue.S -> JsonPrimitive(value.v)
            is StoreValue.B -> JsonPrimitive(value.v)
            is StoreValue.I -> JsonPrimitive(value.v)
            is StoreValue.L -> JsonPrimitive(value.v)
            is StoreValue.F -> JsonPrimitive(value.v)
            // §2.2：set 去重 + 升序
            is StoreValue.SetValue -> JsonArray(value.v.distinct().sorted().map { JsonPrimitive(it) })
        }
        return if (at == null) {
            JsonObject(linkedMapOf("t" to JsonPrimitive(value.tag), "v" to v))
        } else {
            JsonObject(linkedMapOf("t" to JsonPrimitive(value.tag), "v" to v, "at" to JsonPrimitive(at)))
        }
    }

    private fun decodeValue(obj: JsonObject): Pair<StoreValue, Long?> {
        val tag = (obj["t"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("缺值类型标签 t：$obj")
        val v = obj["v"] ?: throw IllegalStateException("缺值 v：$obj")
        val at = (obj["at"] as? JsonPrimitive)?.content?.toLongOrNull()
        val value: StoreValue = when (tag) {
            "s" -> StoreValue.S((v as? JsonPrimitive)?.content ?: "")
            "b" -> StoreValue.B((v as? JsonPrimitive)?.content?.toBoolean() ?: false)
            "i" -> StoreValue.I((v as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
            "l" -> StoreValue.L((v as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
            "f" -> StoreValue.F((v as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f)
            "set" -> StoreValue.SetValue(
                (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            )
            else -> throw IllegalStateException("不认识的类型标签：$tag")
        }
        return value to at
    }

    // ── `stores/<store>.json`（§2.2） ──

    /** 把一个 store 快照编成 `stores/<store>.json` 的字节。 */
    fun encodeStore(snapshot: StoreSnapshot, base: String, suffix: String): String {
        val keys = snapshot.keys.entries.sortedBy { it.key }.map { (k, v) ->
            k to encodeValue(v, snapshot.writtenAt[k])
        }
        val obj = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(ARCHIVE_STORE_FORMAT),
                "version" to JsonPrimitive(ARCHIVE_VERSION),
                "store" to JsonPrimitive(base),
                "suffix" to JsonPrimitive(suffix),
                "keys" to JsonObject(keys.toMap()),
            )
        )
        return obj.toString()
    }

    /** 把一个 `stores/<store>.json` 的字节解回快照 + base/suffix。 */
    fun decodeStore(json: String): StoreRecord {
        val obj = AppJson.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalStateException("store 文件不是 JSON 对象")
        val base = (obj["store"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("store 文件缺 store 字段")
        val suffix = (obj["suffix"] as? JsonPrimitive)?.content ?: ""
        val keysObj = obj["keys"] as? JsonObject ?: emptyMap<String, JsonElement>()
        val keys = linkedMapOf<String, StoreValue>()
        val writtenAt = mutableMapOf<String, Long>()
        keysObj.forEach { (k, v) ->
            val (value, at) = decodeValue(v as JsonObject)
            keys[k] = value
            if (at != null) writtenAt[k] = at
        }
        return StoreRecord(base, suffix, StoreSnapshot(base + suffix, keys, writtenAt))
    }

    // ── `manifest.json`（§2.1） ──

    /** 把一个 manifest 编成 `manifest.json` 的字节（可缺省字段缺省时省略）。 */
    fun encodeManifest(manifest: ArchiveManifest): String {
        val fields = linkedMapOf<String, JsonElement>()
        fields["format"] = JsonPrimitive(manifest.format)
        fields["version"] = JsonPrimitive(manifest.version)
        fields["createdAt"] = JsonPrimitive(manifest.createdAt)
        fields["createdBy"] = JsonPrimitive(manifest.createdBy)
        fields["appVersion"] = JsonPrimitive(manifest.appVersion)
        if (manifest.noAccount) fields["noAccount"] = JsonPrimitive(true)
        fields["accounts"] = JsonArray(
            manifest.accounts.map { a ->
                JsonObject(
                    linkedMapOf(
                        "accountId" to JsonPrimitive(a.accountId),
                        "suffix" to JsonPrimitive(a.suffix),
                        "accountType" to JsonPrimitive(a.accountType),
                        "lastUsedAt" to JsonPrimitive(a.lastUsedAt),
                    )
                )
            }
        )
        fields["stores"] = JsonArray(manifest.stores.map { JsonPrimitive(it) })
        fields["files"] = JsonArray(manifest.files.map { JsonPrimitive(it) })
        if (manifest.redacted.isNotEmpty()) {
            fields["redacted"] = JsonArray(manifest.redacted.map { JsonPrimitive(it) })
        }
        if (manifest.ttlClass.isNotEmpty()) {
            fields["ttlClass"] = JsonObject(manifest.ttlClass.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) })
        }
        return JsonObject(fields).toString()
    }

    /** 把 `manifest.json` 的字节解回 [ArchiveManifest]。 */
    fun decodeManifest(json: String): ArchiveManifest {
        val obj = AppJson.parseToJsonElement(json) as? JsonObject
            ?: throw IllegalStateException("manifest 不是 JSON 对象")
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.content ?: ""
        val accounts = (obj["accounts"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            ArchiveAccount(
                accountId = (a["accountId"] as? JsonPrimitive)?.content ?: "",
                suffix = (a["suffix"] as? JsonPrimitive)?.content ?: "",
                accountType = (a["accountType"] as? JsonPrimitive)?.content ?: "",
                lastUsedAt = (a["lastUsedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            )
        }
        return ArchiveManifest(
            format = str("format"),
            version = (obj["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            createdAt = str("createdAt"),
            createdBy = str("createdBy"),
            appVersion = str("appVersion"),
            noAccount = (obj["noAccount"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
            accounts = accounts,
            stores = (obj["stores"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            files = (obj["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            redacted = (obj["redacted"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            ttlClass = (obj["ttlClass"] as? JsonObject)
                ?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
                ?: emptyMap(),
        )
    }


    /** 把端内旁挂的 `<key>#written_at` 折进 [StoreSnapshot.writtenAt]，并把它从 [StoreSnapshot.keys] 里去掉——档案的 `keys` 里永不出现 `*#written_at`，该时间出现在 `at`。 */
    fun normalizeWrittenAt(rawName: String, rawKeys: Map<String, StoreValue>): StoreSnapshot {
        val keys = linkedMapOf<String, StoreValue>()
        val writtenAt = mutableMapOf<String, Long>()
        rawKeys.forEach { (k, v) ->
            if (k.endsWith(WRITTEN_AT_SUFFIX)) {
                val base = k.removeSuffix(WRITTEN_AT_SUFFIX)
                val ms = when (v) {
                    is StoreValue.S -> v.v.toLongOrNull()
                    is StoreValue.L -> v.v
                    else -> null
                }
                if (ms != null) writtenAt[base] = ms
                // 无论能否解析，这个旁挂键都不进 keys。
            } else {
                keys[k] = v
            }
        }
        return StoreSnapshot(rawName, keys, writtenAt)
    }

    /** §2.1：`createdAt` 用 ISO-8601 带时区（禁止 epoch）。 */
    fun formatTimestamp(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()
}
