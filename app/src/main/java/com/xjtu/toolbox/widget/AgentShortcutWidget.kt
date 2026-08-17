package com.xjtu.toolbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.Routes

/**
 * 一键提问桌面 Widget。
 *
 * 点击行为：打开 MainActivity，跳到 agent 屏。
 *
 * 不参与周期性 wakeup：内容是固定的"问屁岱"，刷新完全靠添加 / 系统重新绑定触发。
 */
object AgentShortcutWidgetUpdater {
    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.AGENT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            5001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_agent_shortcut)
            views.setOnClickPendingIntent(R.id.widget_agent_root, pendingIntent)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class AgentShortcutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AgentShortcutWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }
}