package com.xjtu.toolbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.nav.AppRoute

/**
 * 教务通知桌面 Widget。
 *
 * 数据来源：[NoticeWidgetStore]。写入方：
 * - [com.xjtu.toolbox.notification.NotificationScreen] 打开通知页成功后
 * - [com.xjtu.toolbox.home.HomeStatsRefresher] 首页 4 小时一轮的教务处抓取
 * Widget 自己不发请求，只渲染缓存。
 *
 * 版式：标题行（名称 + 更新时间），下面最近几条通知一行一条，左边是发布日期（今天 / 昨天 / 9/28）；
 * 行高平分剩下的高度，卡片拉高拉矮都铺得满。没有通知时显示一句说明，点击进通知页。
 */
object NoticeWidgetUpdater {
    internal fun publish(context: Context, entries: List<NoticeWidgetStore.Entry>) {
        NoticeWidgetStore.write(context, entries)
        requestUpdate(context)
    }

    private val ROWS = listOf(
        Triple(R.id.widget_notice_row_0, R.id.widget_notice_date_0, R.id.widget_notice_title_0),
        Triple(R.id.widget_notice_row_1, R.id.widget_notice_date_1, R.id.widget_notice_title_1),
        Triple(R.id.widget_notice_row_2, R.id.widget_notice_date_2, R.id.widget_notice_title_2),
        Triple(R.id.widget_notice_row_3, R.id.widget_notice_date_3, R.id.widget_notice_title_3),
    )

    private fun dayLabel(day: Long?, today: java.time.LocalDate): String {
        val date = day?.let(java.time.LocalDate::ofEpochDay) ?: return ""
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${date.monthValue}/${date.dayOfMonth}"
        }
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

        val (entries, updatedAt) = NoticeWidgetStore.read(context)
        val timeText = if (updatedAt == 0L) "" else {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = updatedAt }
            "更新 %02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }
        val today = java.time.LocalDate.now()

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, AppRoute.Notification.id)
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
                views.setTextViewText(R.id.widget_notice_update_time, timeText)
                // 行数不够时空行只隐藏不收起，剩下的几条仍按行高排在上面
                ROWS.forEachIndexed { i, (rowId, dateId, titleId) ->
                    val entry = entries.getOrNull(i)
                    views.setViewVisibility(rowId, if (entry == null) View.INVISIBLE else View.VISIBLE)
                    views.setTextViewText(dateId, dayLabel(entry?.day, today))
                    views.setTextViewText(titleId, entry?.title.orEmpty())
                }
                val empty = entries.isEmpty()
                views.setViewVisibility(R.id.widget_notice_list, if (empty) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_notice_empty, if (empty) View.VISIBLE else View.GONE)
                if (empty) {
                    val noSource = com.xjtu.toolbox.notification.NoticeWatchStore.sources(context).isEmpty()
                    views.setTextViewText(R.id.widget_notice_empty_title, if (noSource) "还没选要看的栏目" else "暂时没有通知")
                    views.setTextViewText(R.id.widget_notice_empty_detail, if (noSource) "点这里去通知页选择" else "点这里打开通知页")
                }
                views.setOnClickPendingIntent(R.id.widget_notice_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, views)
            }.onFailure {
                val fallback = RemoteViews(context.packageName, R.layout.widget_fallback)
                fallback.setTextViewText(R.id.widget_fallback_text, entries.firstOrNull()?.title ?: context.getString(R.string.notice_widget_name))
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