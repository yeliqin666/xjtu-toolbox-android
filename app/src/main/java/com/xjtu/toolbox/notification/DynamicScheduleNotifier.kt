package com.xjtu.toolbox.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.RequestCodes
import java.util.Locale

internal object DynamicScheduleNotifier {
    const val ID = 3206

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    fun canPost(context: Context): Boolean = NoticeNotifier.canPost(context) &&
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(AppNotificationChannels.CHANNEL_DYNAMIC_SCHEDULE)
            ?.importance != NotificationManager.IMPORTANCE_NONE

    fun post(context: Context, plan: DynamicSchedulePlan, nowMs: Long) {
        val notification = build(context, plan, nowMs) ?: return cancel(context)
        if (!canPost(context)) return cancel(context)
        try {
            NotificationManagerCompat.from(context).notify(ID, notification)
        } catch (_: SecurityException) {
            // 系统权限可能在检查和 notify 之间被撤回。
            cancel(context)
        }
    }

    internal fun build(context: Context, plan: DynamicSchedulePlan, nowMs: Long): Notification? {
        val item = plan.current ?: return null
        if (plan.nextRefreshAt <= nowMs) return null
        val kind = if (item.isAgenda) "日程" else "课程"
        val state = if (plan.ongoing) "正在进行" else "接下来"
        val time = "${clock(item.startMinute)}–${clock(item.endMinute)}"
        val detail = listOf(time, item.course.location.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
        val left = "今日剩余 ${plan.coursesLeft} 项课程 · ${plan.agendasLeft} 项日程"
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, AppRoute.Schedule.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, RequestCodes.DYNAMIC_SCHEDULE, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, AppNotificationChannels.CHANNEL_DYNAMIC_SCHEDULE)
            .setSmallIcon(R.drawable.ic_notification_notice)
            .setContentTitle("$state$kind · ${item.course.courseName}")
            .setContentText(detail)
            .setSubText(if (plan.ongoing) "距结束" else "距开始")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n$left"))
            .setWhen(if (plan.ongoing) item.endAt else item.startAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // 后台切换被系统推迟时先移除旧卡，避免继续倒数已经开始/结束的安排。
            .setTimeoutAfter(plan.nextRefreshAt - nowMs)
            .build()
    }

    private fun clock(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
}
