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
 * 教务通知桌面 Widget。
 *
 * 数据来源：[NoticeWidgetStore]。写入方：
 * - [com.xjtu.toolbox.notification.NotificationScreen] 打开通知页成功后
 * - [com.xjtu.toolbox.home.HomeStatsRefresher] 首页 4 小时一轮的教务处抓取
 * Widget 自己不发请求，只渲染缓存。
 *
 * 失败状态：首次添加 / 缓存为空 → "--"，点击进应用。
 */
object NoticeWidgetUpdater {
    fun publishTitles(context: Context, titles: List<String>) {
        NoticeWidgetStore.write(context, titles)
        requestUpdate(context)
    }

    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, NoticeWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) update(context, manager, ids)
    }

    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val (titles, updatedAt) = NoticeWidgetStore.read(context)
        val timeText = if (updatedAt == 0L) "--:--" else {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = updatedAt }
            "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.NOTIFICATION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            3001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            runCatching {
                val views = RemoteViews(context.packageName, R.layout.widget_notice)
                views.setTextViewText(R.id.widget_notice_1, titles.getOrNull(0) ?: "--")
                views.setTextViewText(R.id.widget_notice_2, titles.getOrNull(1) ?: "")
                views.setTextViewText(R.id.widget_notice_3, titles.getOrNull(2) ?: "")
                views.setTextViewText(R.id.widget_notice_update_time, timeText)
                views.setOnClickPendingIntent(R.id.widget_notice_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, views)
            }.onFailure {
                val fallback = RemoteViews(context.packageName, R.layout.widget_fallback)
                fallback.setTextViewText(R.id.widget_fallback_text, titles.getOrNull(0) ?: context.getString(R.string.notice_widget_name))
                fallback.setOnClickPendingIntent(R.id.widget_fallback_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, fallback)
            }
        }
    }
}

class NoticeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        NoticeWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }
}