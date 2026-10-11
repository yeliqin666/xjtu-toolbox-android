package com.xjtu.toolbox.dormpower

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * 本机记下的宿舍和最近一次电量：首页卡片、屁岱提醒不用每次登录就有得说，
 * 也让后台只在绑过宿舍的账号上才去碰这个系统。按账号一份。
 *
 * - **落盘**：原来收 `android.content.Context` 自己开 `SharedPreferences`，现在走 `:core` 的
 *   平台键值缝 [keyValueStore] —— Android 侧的 actual 就是**同一个文件名的
 *   `SharedPreferences`**（`dorm_power<suffix>`，键名 `readings` / `used` 逐个相同）⇒ 老读数与
 *   「进过这一页」那个标记一个不丢，不需要迁移代码。`:app` 的 `initAndroidPlatform(context)`
 *   已经在 `Application.onCreate` 里调过（见 `platform/KeyValueStore.android.kt`）。
 * - **刷新**：`refresh` 原来收 `DormPowerApi`（`:data` 的类），现在收 [DormPowerSource] 端口
 *   —— `:core` 不认识 okhttp，而 `:data` 的 `DormPowerApi` 正好实现这个端口，所以调用点只少了
 *   那个 `Context` 参数，编排一行未改（房间以服务器上绑定的为准，某间查失败就沿用上次的读数）。
 */
object DormPowerStore {

    /**
     * 按账号分命名空间，与搬迁前**同一个文件名**。
     *
     * 每次调用现取（而不是 `by lazy`）：名字里带着当前账号，切账号要立刻换一份 —— 与原来的
     * `prefs(context)` 每次现算同名同义。
     */
    private fun store() = keyValueStore("dorm_power" + AccountContext.suffixFor(AccountContext.activeAccountId))

    fun readings(): List<DormReading> {
        val raw = store().getString("readings") ?: return emptyList()
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

    /** 用户进过宿舍电费页（绑定发生在学校页面里，本机不知道），之后才值得在后台去查。 */
    fun isUsed() = store().getBoolean("used", false)

    fun markUsed() = store().putBoolean("used", true)

    fun save(readings: List<DormReading>) {
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
        store().putString("readings", array.toString())
    }

    /** 查一遍所有绑定的宿舍，写回本机。房间以服务器上绑定的为准，某间查失败就沿用上次的读数。 */
    suspend fun refresh(source: DormPowerSource): List<DormReading> {
        val previous = readings().associateBy { it.room.id }
        // `System.currentTimeMillis()`（java.time 时代的写法）在 commonMain 没有 ⇒ 用 `Clock.System`
        // 同一时刻、同一语义（epoch 毫秒），与 `InboxModels` 等既有先例同一句写法。
        val now = Clock.System.now().toEpochMilliseconds()
        val fresh = source.rooms().map { room ->
            val kwh = runCatching { source.kwh(room.id) }.getOrElse { return@map previous[room.id] ?: DormReading(room, null, 0L) }
            DormReading(room, kwh, now)
        }
        save(fresh)
        return fresh
    }

    /** 电量最低且低于 [LOW_KWH] 的那间，给提醒用。 */
    fun lowest(readings: List<DormReading>): DormReading? =
        readings.filter { (it.kwh ?: Double.MAX_VALUE) < LOW_KWH }.minByOrNull { it.kwh ?: Double.MAX_VALUE }
}
