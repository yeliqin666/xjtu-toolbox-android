package com.xjtu.toolbox.emptyroom

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.safeParseJsonObject

/**
 * 空教室缓存。
 *
 * **账号隔离**：cache 名后缀随 [AccountContext.activeAccountId] 动态计算，
 * 切换账号后访问落到新账号命名空间，旧账号数据不会被读到（也不会被新账号覆盖）。
 */
class EmptyRoomCache(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 当前账号对应的 prefs 名。**每次访问动态计算**——和 [com.xjtu.toolbox.util.DataCache] 的策略一致。
     * 这样切换账号后无需重启 App，下一次调用就会命中新账号的命名空间。
     */
    private val prefs
        get() = appContext.getSharedPreferences("empty_room_cache${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)

    fun readJson(key: String, maxAgeDays: Int): String? {
        val savedAt = prefs.getLong("${key}_time", 0L)
        if (savedAt <= 0L) return null
        val maxAgeMs = maxAgeDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        if (System.currentTimeMillis() - savedAt > maxAgeMs) return null
        return prefs.getString(key, null)
    }

    fun writeJson(key: String, json: String) {
        // commit() 保证异常退出时数据已落盘；空教室缓存写盘频次低（每用户每次进入空教室页 1-2 次），
        // 同步写可接受。apply() 的异步写存在异常退出丢数据风险。
        prefs.edit()
            .putString(key, json)
            .putLong("${key}_time", System.currentTimeMillis())
            .commit()
    }

    fun readRoomList(key: String, maxAgeDays: Int): List<RoomInfo>? {
        val raw = readJson(key, maxAgeDays) ?: return null
        return parseRoomList(raw)
    }

    /**
     * 忽略 TTL：联网失败时的兜底。"过期了也别空白"——比直接报错更友好。
     * 但调用方应在 UI 上明确标注"这是 X 前的缓存，可能不是最新"。
     */
    fun readRoomListStale(key: String): List<RoomInfo>? {
        val savedAt = prefs.getLong("${key}_time", 0L)
        if (savedAt <= 0L) return null
        val raw = prefs.getString(key, null) ?: return null
        return parseRoomList(raw)
    }

    /** 取出对应缓存键的「写入时间戳」，供 UI 标注新鲜度。 */
    fun savedAt(key: String): Long = prefs.getLong("${key}_time", 0L)

    private fun parseRoomList(raw: String): List<RoomInfo>? = try {
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
