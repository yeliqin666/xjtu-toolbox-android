package com.xjtu.toolbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.Routes

/**
 * 空教室桌面 Widget。
 *
 * 数据来源：[EmptyRoomWidgetStore]，由 [com.xjtu.toolbox.emptyroom.EmptyRoomScreen]
 * 每次成功拉到列表后写入前 3 条（教室名 + 座位数）。Widget 不发请求。
 *
 * 简化策略：取列表前 3 条。应用不做"当前时段"推断，widget 也就省了——牺牲一点
 * 精确度换 widget 极简实现。当前时段推断留给后续升级。
 */
object EmptyRoomWidgetUpdater {
    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, EmptyRoomWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) update(context, manager, ids)
    }

    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val (rooms, updatedAt) = EmptyRoomWidgetStore.read(context)
        val timeText = if (updatedAt == 0L) "--:--" else {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = updatedAt }
            "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.EMPTY_ROOM)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_empty_room)
            for (i in 0..2) {
                val textId = when (i) {
                    0 -> R.id.widget_emptyroom_1
                    1 -> R.id.widget_emptyroom_2
                    else -> R.id.widget_emptyroom_3
                }
                val subId = when (i) {
                    0 -> R.id.widget_emptyroom_1_size
                    1 -> R.id.widget_emptyroom_2_size
                    else -> R.id.widget_emptyroom_3_size
                }
                val room = rooms.getOrNull(i)
                views.setTextViewText(textId, room?.name ?: "--")
                views.setTextViewText(subId, room?.let { "${it.size} 座" } ?: "")
            }
            views.setTextViewText(R.id.widget_emptyroom_update_time, timeText)
            views.setOnClickPendingIntent(R.id.widget_emptyroom_root, pendingIntent)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class EmptyRoomWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        EmptyRoomWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }
}