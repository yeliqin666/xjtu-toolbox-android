package com.xjtu.toolbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.inbox.InboxCategories
import com.xjtu.toolbox.inbox.InboxItem
import com.xjtu.toolbox.inbox.InboxRules
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.schedule.colorOf
import com.xjtu.toolbox.schedule.courseColorMap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 待办桌面小组件：收纳页「待办」栏的前几条，快截止的在前。一行一条：左边一道色条，作业按科目取课表那门课的颜色、
 * 科目名同色，其余待办用分类色；右边是截止时间。
 *
 * 数据来自 [InboxStore]，每次收纳写入都重画，小组件自己不发请求。点击进收纳页。
 */
object TodoWidgetUpdater {
    private class Row(val row: Int, val accent: Int, val source: Int, val title: Int, val due: Int)

    private val ROWS = listOf(
        Row(R.id.widget_todo_row_0, R.id.widget_todo_accent_0, R.id.widget_todo_source_0, R.id.widget_todo_title_0, R.id.widget_todo_due_0),
        Row(R.id.widget_todo_row_1, R.id.widget_todo_accent_1, R.id.widget_todo_source_1, R.id.widget_todo_title_1, R.id.widget_todo_due_1),
        Row(R.id.widget_todo_row_2, R.id.widget_todo_accent_2, R.id.widget_todo_source_2, R.id.widget_todo_title_2, R.id.widget_todo_due_2),
        Row(R.id.widget_todo_row_3, R.id.widget_todo_accent_3, R.id.widget_todo_source_3, R.id.widget_todo_title_3, R.id.widget_todo_due_3),
    )

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** 截止时间：今天、明天带钟点，更远只给日期；没有截止时间给空串。 */
    internal fun dueLabel(expiresAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (expiresAt <= 0) return ""
        val at = Instant.ofEpochMilli(expiresAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (at.toLocalDate()) {
            today -> "今天 " + at.format(CLOCK)
            today.plusDays(1) -> "明天 " + at.format(CLOCK)
            else -> "${at.monthValue}/${at.dayOfMonth}"
        }
    }

    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
        if (ids.isNotEmpty()) update(context, manager, ids)
    }

    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val now = System.currentTimeMillis()
        val todos = InboxRules.todos(InboxStore.load(), now)
        val courseColors = courseColorMap(todos.filter { it.category == InboxCategories.LMS }.map { it.source })
        fun accent(item: InboxItem) =
            if (item.category == InboxCategories.LMS) courseColors.colorOf(item.source).toArgb() else InboxCategories.argb(item.category)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, AppRoute.Inbox.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(context, 4001, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        for (id in appWidgetIds) {
            runCatching {
                val views = RemoteViews(context.packageName, R.layout.widget_todo)
                views.setTextViewText(R.id.widget_todo_count, if (todos.isEmpty()) "" else "${todos.size} 项")
                // 行数不够时空行只隐藏不收起，剩下的几条仍按行高排在上面
                ROWS.forEachIndexed { i, r ->
                    val item = todos.getOrNull(i)
                    views.setViewVisibility(r.row, if (item == null) View.INVISIBLE else View.VISIBLE)
                    if (item == null) return@forEachIndexed
                    val color = accent(item)
                    views.setInt(r.accent, "setColorFilter", color)
                    views.setTextColor(r.source, color)
                    views.setTextViewText(r.source, item.source)
                    // 学校不少待办的标题就是系统名，这时拿正文当标题
                    views.setTextViewText(r.title, if (item.title == item.source && item.body.isNotBlank()) item.body else item.title)
                    views.setTextViewText(r.due, dueLabel(item.expiresAt, now))
                }
                val empty = todos.isEmpty()
                views.setViewVisibility(R.id.widget_todo_list, if (empty) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widget_todo_empty, if (empty) View.VISIBLE else View.GONE)
                views.setOnClickPendingIntent(R.id.widget_todo_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, views)
            }.onFailure {
                val fallback = RemoteViews(context.packageName, R.layout.widget_fallback)
                fallback.setTextViewText(R.id.widget_fallback_text, todos.firstOrNull()?.title ?: context.getString(R.string.todo_widget_name))
                fallback.setOnClickPendingIntent(R.id.widget_fallback_root, pendingIntent)
                appWidgetManager.updateAppWidget(id, fallback)
            }
        }
    }
}

class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        TodoWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }
}
