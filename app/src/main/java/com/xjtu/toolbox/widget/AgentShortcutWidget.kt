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
 * 问屁岱桌面小组件：三条现成问题，点一下带着 prompt 打开聊天。
 */
object AgentShortcutWidgetUpdater {
    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        for (id in appWidgetIds) {
            runCatching {
                val views = RemoteViews(context.packageName, R.layout.widget_agent_shortcut)
                views.setOnClickPendingIntent(
                    R.id.widget_agent_root,
                    agentIntent(context, 5001, null),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_agent_chip_schedule,
                    agentIntent(context, 5002, context.getString(R.string.agent_widget_ask_schedule)),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_agent_chip_room,
                    agentIntent(context, 5003, context.getString(R.string.agent_widget_ask_room)),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_agent_chip_card,
                    agentIntent(context, 5004, context.getString(R.string.agent_widget_ask_card)),
                )
                appWidgetManager.updateAppWidget(id, views)
            }.onFailure {
                val fallback = RemoteViews(context.packageName, R.layout.widget_fallback)
                fallback.setTextViewText(R.id.widget_fallback_text, context.getString(R.string.agent_shortcut_widget_title))
                fallback.setOnClickPendingIntent(R.id.widget_fallback_root, agentIntent(context, 5001, null))
                appWidgetManager.updateAppWidget(id, fallback)
            }
        }
    }

    private fun agentIntent(context: Context, requestCode: Int, prompt: String?): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.AGENT)
            if (!prompt.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_LAUNCH_PROMPT, prompt)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
