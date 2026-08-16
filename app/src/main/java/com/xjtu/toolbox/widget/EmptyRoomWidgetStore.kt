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
    private const val KEY_N0 = "name_0"
    private const val KEY_N1 = "name_1"
    private const val KEY_N2 = "name_2"
    private const val KEY_S0 = "size_0"
    private const val KEY_S1 = "size_1"
    private const val KEY_S2 = "size_2"
    private const val KEY_TIME = "updated_at"

    data class RoomBrief(val name: String, val size: Int)
    data class Snapshot(val rooms: List<RoomBrief>, val updatedAt: Long)

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rooms = mutableListOf<RoomBrief>()
        for (i in 0..2) {
            val name = p.getString(keyName(i), null) ?: break
            val size = p.getInt(keySize(i), 0)
            rooms.add(RoomBrief(name, size))
        }
        return Snapshot(rooms, p.getLong(KEY_TIME, 0L))
    }

    fun write(context: Context, rooms: List<RoomBrief>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = p.edit()
        rooms.take(3).forEachIndexed { i, r ->
            editor.putString(keyName(i), r.name)
            editor.putInt(keySize(i), r.size)
        }
        // 多余的槽位清空，避免上次写满 3 条后下次只写 1 条残留旧数据
        for (i in rooms.size.coerceAtMost(3)..2) {
            editor.remove(keyName(i))
            editor.remove(keySize(i))
        }
        editor.putLong(KEY_TIME, System.currentTimeMillis())
        editor.apply()
    }

    private fun keyName(i: Int) = if (i == 0) KEY_N0 else if (i == 1) KEY_N1 else KEY_N2
    private fun keySize(i: Int) = if (i == 0) KEY_S0 else if (i == 1) KEY_S1 else KEY_S2
}