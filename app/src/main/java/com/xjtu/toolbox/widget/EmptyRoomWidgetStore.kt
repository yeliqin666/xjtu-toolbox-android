package com.xjtu.toolbox.widget

import android.content.Context

/**
 * 空教室桌面 Widget 的轻量缓存：最多 3 条 (name, size) + 更新时间。
 *
 * 写入方：[com.xjtu.toolbox.emptyroom.EmptyRoomScreen] 拿到结果后取前 3 条。
 * 读取方：[EmptyRoomWidgetUpdater]。
 *
 * **不放入 Auto Backup 白名单**：空教室查询是临时信息，重装重查即可。
 */
internal object EmptyRoomWidgetStore {
    private const val PREFS = "empty_room_widget_cache"
    private val NAME_KEYS = listOf("name_0", "name_1", "name_2")
    private val SIZE_KEYS = listOf("size_0", "size_1", "size_2")
    private const val KEY_TIME = "updated_at"

    data class RoomBrief(val name: String, val size: Int)
    data class Snapshot(val rooms: List<RoomBrief>, val updatedAt: Long)

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rooms = mutableListOf<RoomBrief>()
        for (i in 0..2) {
            // 跳过空槽位但不因第一个空就 break——损坏 / 半写数据仍能展示剩余部分
            val name = p.getString(NAME_KEYS[i], null)?.takeIf { it.isNotBlank() } ?: continue
            rooms.add(RoomBrief(name, p.getInt(SIZE_KEYS[i], 0)))
        }
        return Snapshot(rooms, p.getLong(KEY_TIME, 0L))
    }

    fun write(context: Context, rooms: List<RoomBrief>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = p.edit()
        rooms.take(3).forEachIndexed { i, r ->
            editor.putString(NAME_KEYS[i], r.name)
            editor.putInt(SIZE_KEYS[i], r.size)
        }
        // 多余的槽位清空，避免上次写满 3 条后下次只写 1 条残留旧数据
        for (i in rooms.size.coerceAtMost(3)..2) {
            editor.remove(NAME_KEYS[i])
            editor.remove(SIZE_KEYS[i])
        }
        editor.putLong(KEY_TIME, System.currentTimeMillis())
        editor.apply()
    }
}