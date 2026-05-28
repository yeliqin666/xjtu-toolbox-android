package com.xjtu.toolbox.emptyroom

import android.content.Context
import com.xjtu.toolbox.util.safeParseJsonObject

class EmptyRoomCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("empty_room_cache", Context.MODE_PRIVATE)

    fun readJson(key: String, maxAgeDays: Int): String? {
        val savedAt = prefs.getLong("${key}_time", 0L)
        if (savedAt <= 0L) return null
        val maxAgeMs = maxAgeDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        if (System.currentTimeMillis() - savedAt > maxAgeMs) return null
        return prefs.getString(key, null)
    }

    fun writeJson(key: String, json: String) {
        prefs.edit()
            .putString(key, json)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    fun readRoomList(key: String, maxAgeDays: Int): List<RoomInfo>? {
        val raw = readJson(key, maxAgeDays) ?: return null
        return try {
            val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
            arr.mapNotNull { el ->
                val obj = el.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                val size = obj.get("size")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val status = obj.getAsJsonArray("status")?.map { it.asInt } ?: return@mapNotNull null
                RoomInfo(name, size, status)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeRoomList(key: String, rooms: List<RoomInfo>) {
        val arr = com.google.gson.JsonArray()
        rooms.forEach { room ->
            val obj = com.google.gson.JsonObject()
            obj.addProperty("name", room.name)
            obj.addProperty("size", room.size)
            val status = com.google.gson.JsonArray()
            room.status.forEach { status.add(it) }
            obj.add("status", status)
            arr.add(obj)
        }
        writeJson(key, arr.toString())
    }

    fun readCodeMap(key: String, maxAgeDays: Int): Map<String, String>? {
        val raw = readJson(key, maxAgeDays) ?: return null
        return try {
            raw.safeParseJsonObject().entrySet().associate { it.key to it.value.asString }
        } catch (_: Exception) {
            null
        }
    }

    fun writeCodeMap(key: String, data: Map<String, String>) {
        val obj = com.google.gson.JsonObject()
        data.forEach { (k, v) -> obj.addProperty(k, v) }
        writeJson(key, obj.toString())
    }

    companion object {
        const val CODE_TTL_DAYS = 7
        const val DIRECT_RESULT_TTL_DAYS = 7
        const val CDN_RESULT_TTL_DAYS = 1
    }
}
