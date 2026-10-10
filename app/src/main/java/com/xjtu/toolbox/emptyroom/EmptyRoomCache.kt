package com.xjtu.toolbox.emptyroom

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import com.xjtu.toolbox.util.stringValue
import com.xjtu.toolbox.util.intValue
import com.xjtu.toolbox.util.isNull
import com.xjtu.toolbox.util.arr
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.safeParseJsonObject

/**
 * 空教室缓存 —— `:core` 的 [EmptyRoomStore] 在 Android 上的实现。
 *
 * **账号隔离**：cache 名后缀随 [AccountContext.activeAccountId] 动态计算，
 * 切换账号后访问落到新账号命名空间，旧账号数据不会被读到（也不会被新账号覆盖）。
 *
 * 取数（`EmptyRoomApi` / `EmptyRoomDirectQuery` / `LiveRoomApi` / `AppEmptyRoomSource`）已经搬进
 * `:data`（桌面端第 10 条真数据路由：桌面自己登智慧教室、自己取数），这里只剩**唯一离不开
 * `Context` 的那一档**：按账号命名空间的 SharedPreferences。搬迁前它就已经是这几个类的落盘去处，
 * 现在只是被抽成 [EmptyRoomStore] 那条缝（本类实现它，方法体逐字未动）——**一个文件名、一个键、
 * 一个 TTL、一次 `.commit()` 都没改**，`AgentTool` 与空教室屏读的还是同一份。
 *
 * ⚠️ 常量（[EmptyRoomStore.CODE_TTL_DAYS] 等）跟着缝走了：它们是「这份缓存怎么用」的契约的一部分，
 * 原来写在下面的 companion 里，现在由 [EmptyRoomStore] 声明。
 */
class EmptyRoomCache(context: Context) : EmptyRoomStore {
    private val appContext = context.applicationContext

    /**
     * 当前账号对应的 prefs 名。**每次访问动态计算**——和 [com.xjtu.toolbox.data.DataCache] 的策略一致。
     * 这样切换账号后无需重启 App，下一次调用就会命中新账号的命名空间。
     */
    private val prefs
        get() = appContext.getSharedPreferences("empty_room_cache${AccountContext.safeSuffix()}", Context.MODE_PRIVATE)

    override fun readJson(key: String, maxAgeDays: Int): String? {
        val savedAt = prefs.getLong("${key}_time", 0L)
        if (savedAt <= 0L) return null
        val maxAgeMs = maxAgeDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        if (System.currentTimeMillis() - savedAt > maxAgeMs) return null
        return prefs.getString(key, null)
    }

    override fun writeJson(key: String, json: String) {
        // commit() 保证异常退出时数据已落盘；空教室缓存写盘频次低（每用户每次进入空教室页 1-2 次），
        // 同步写可接受。apply() 的异步写存在异常退出丢数据风险。
        prefs.edit()
            .putString(key, json)
            .putLong("${key}_time", System.currentTimeMillis())
            .commit()
    }

    override fun readRoomList(key: String, maxAgeDays: Int): List<RoomInfo>? {
        val raw = readJson(key, maxAgeDays) ?: return null
        return parseRoomList(raw)
    }

    /**
     * 忽略 TTL：联网失败时的兜底。"过期了也别空白"——比直接报错更友好。
     * 但调用方应在 UI 上明确标注"这是 X 前的缓存，可能不是最新"。
     */
    override fun readRoomListStale(key: String): List<RoomInfo>? {
        val savedAt = prefs.getLong("${key}_time", 0L)
        if (savedAt <= 0L) return null
        val raw = prefs.getString(key, null) ?: return null
        return parseRoomList(raw)
    }

    /** 不看天数 TTL 读原文：实时状态按分钟算新鲜度，由调用方拿 [savedAt] 自己判断。 */
    override fun readJsonAnyAge(key: String): String? = prefs.getString(key, null)

    /** 取出对应缓存键的「写入时间戳」，供 UI 标注新鲜度。 */
    override fun savedAt(key: String): Long = prefs.getLong("${key}_time", 0L)

    private fun parseRoomList(raw: String): List<RoomInfo>? = try {
        val arr = AppJson.parseToJsonElement(raw).jsonArray
        arr.mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj.get("name")?.takeIf { !it.isNull }?.stringValue ?: return@mapNotNull null
            val size = obj.get("size")?.takeIf { !it.isNull }?.intValue ?: 0
            val status = obj.arr("status")?.map { it.intValue } ?: return@mapNotNull null
            RoomInfo(name, size, status)
        }
    } catch (_: Exception) {
        null
    }

    override fun writeRoomList(key: String, rooms: List<RoomInfo>) {
        val arr = buildJsonArray {
            rooms.forEach { room ->
                addJsonObject {
                    put("name", room.name)
                    put("size", room.size)
                    putJsonArray("status") { room.status.forEach { add(it) } }
                }
            }
        }
        writeJson(key, arr.toString())
    }

    override fun readCodeMap(key: String, maxAgeDays: Int): Map<String, String>? {
        val raw = readJson(key, maxAgeDays) ?: return null
        return try {
            raw.safeParseJsonObject().entries.associate { it.key to it.value.stringValue }
        } catch (_: Exception) {
            null
        }
    }

    override fun writeCodeMap(key: String, data: Map<String, String>) {
        writeJson(key, JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString())
    }
}
