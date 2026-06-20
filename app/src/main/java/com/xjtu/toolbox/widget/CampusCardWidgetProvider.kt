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

object CampusCardWidgetUpdater {
    fun requestUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, CampusCardWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) {
            update(context, manager, ids)
        }
    }

    fun update(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val prefs = com.xjtu.toolbox.card.CampusCardCache.cardPrefs(context)
        val balance = prefs.getFloat("card_balance_cache", -1f)
        val todaySpend = prefs.getFloat("card_today_spend_cache", -1f)
        val breakfast = prefs.getFloat("card_today_breakfast_cache", -1f)
        val lunch = prefs.getFloat("card_today_lunch_cache", -1f)
        val dinner = prefs.getFloat("card_today_dinner_cache", -1f)
        val cacheTime = prefs.getLong("card_cache_time", 0L)

        val balanceText = formatMoney(balance)
        val todayText = formatMoney(todaySpend)
        val breakfastText = formatMealMoney(breakfast)
        val lunchText = formatMealMoney(lunch)
        val dinnerText = formatMealMoney(dinner)
        val updateTimeText = if (cacheTime == 0L) "--:--" else {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = cacheTime }
            "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.CAMPUS_CARD)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_campus_card)
            views.setTextViewText(R.id.widget_card_balance, balanceText)
            views.setTextViewText(R.id.widget_card_today, todayText)
            views.setTextViewText(R.id.widget_card_breakfast, breakfastText)
            views.setTextViewText(R.id.widget_card_lunch, lunchText)
            views.setTextViewText(R.id.widget_card_dinner, dinnerText)
            views.setTextViewText(R.id.widget_card_update_time, updateTimeText)
            views.setOnClickPendingIntent(R.id.widget_card_root, pendingIntent)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun formatMoney(value: Float): String {
        return if (value < 0f) "--" else "¥%.2f".format(value)
    }

    private fun formatMealMoney(value: Float): String {
        if (value < 0f) return "--"
        return when {
            value >= 100f -> "%.0f".format(value)
            value >= 10f -> "%.1f".format(value)
            else -> "%.2f".format(value)
        }
    }
}

class CampusCardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        CampusCardWidgetUpdater.update(context, appWidgetManager, appWidgetIds)
    }
}