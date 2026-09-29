package com.xjtu.toolbox.dormpower

import android.content.Context
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 一间宿舍最近一次查到的电量；[kwh] 为 null 表示系统查不到。 */
data class DormReading(val room: DormRoom, val kwh: Double?, val at: Long)

/**
 * 本机记下的宿舍和最近一次电量：首页卡片、屁岱提醒不用每次登录就有得说，
 * 也让后台只在绑过宿舍的账号上才去碰这个系统。按账号一份。
 */
object DormPowerStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        "dorm_power" + AccountContext.suffixFor(AccountContext.activeAccountId),
        Context.MODE_PRIVATE,
    )

    fun readings(context: Context): List<DormReading> {
        val raw = prefs(context).getString("readings", null) ?: return emptyList()
        val array = runCatching { AppJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            DormReading(
                DormRoom(id, o["name"]?.jsonPrimitive?.content.orEmpty()),
                o["kwh"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                o["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    fun hasRooms(context: Context) = readings(context).isNotEmpty()

    fun save(context: Context, readings: List<DormReading>) {
        val array: JsonArray = buildJsonArray {
            readings.forEach { r ->
                add(
                    buildJsonObject {
                        put("id", r.room.id)
                        put("name", r.room.name)
                        r.kwh?.let { put("kwh", it) }
                        put("at", r.at)
                    },
                )
            }
        }
        prefs(context).edit().putString("readings", array.toString()).apply()
    }

    /** 查一遍所有绑定的宿舍，写回本机。房间以服务器上绑定的为准，某间查失败就沿用上次的读数。 */
    suspend fun refresh(context: Context, api: DormPowerApi): List<DormReading> {
        val previous = readings(context).associateBy { it.room.id }
        val now = System.currentTimeMillis()
        val fresh = api.rooms().map { room ->
            val kwh = runCatching { api.kwh(room.id) }.getOrElse { return@map previous[room.id] ?: DormReading(room, null, 0L) }
            DormReading(room, kwh, now)
        }
        save(context, fresh)
        return fresh
    }

    /** 电量最低且低于 [LOW_KWH] 的那间，给提醒用。 */
    fun lowest(readings: List<DormReading>): DormReading? =
        readings.filter { (it.kwh ?: Double.MAX_VALUE) < LOW_KWH }.minByOrNull { it.kwh ?: Double.MAX_VALUE }
}

/** 房间全名的最后三级（栋/层/室这类），列表标题用；不足三级就是全名。 */
fun DormRoom.shortName(): String = name.split('/').takeLast(3).joinToString(" · ").ifBlank { name }
