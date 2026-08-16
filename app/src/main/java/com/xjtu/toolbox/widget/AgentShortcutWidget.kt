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

/**
 * 屁岱（Agent）一键提问桌面 Widget。
 *
 * 点击行为：打开 MainActivity，跳到 agent 屏。
 *
 * 不刷新——内容是固定的"问屁岱"，没必要周期性 wakeup。
 *
 * 注：自动弹键盘属于 Activity 行为，widget 不能直接做，需要在 AgentScreen 端
 * 监听额外 flag 才能完成（见 TODO / PR-?）。本 PR 只负责入口直达。
 */
object AgentShortcutWidgetUpdater {
    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, AgentShortcutWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) update(context, manager, ids)
    }

    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, com.xjtu.toolbox.Routes.AGENT)
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