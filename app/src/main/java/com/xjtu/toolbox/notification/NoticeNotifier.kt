package com.xjtu.toolbox.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.Routes

/**
 * 把新教务通知落到系统通知栏。
 *
 * 不绕过用户开关：没授权或渠道被关就直接返回。
 * 多条合成一条 Inbox，避免一次检查刷出一排。
 */
internal object NoticeNotifier {
    const val NOTIFICATION_ID = 3101
    private const val REQUEST_CODE = 3101

    fun canPost(context: Context): Boolean {
        if (!AppNotificationChannels.areNotificationsEnabled(context)) return false
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    fun notifyNew(context: Context, items: List<Notification>) {
        if (items.isEmpty() || !canPost(context)) return

        val shown = items.take(5)
        val title = if (items.size == 1) shown[0].source.displayName else "教务通知"
        val text = if (items.size == 1) shown[0].title else "${items.size} 条新通知"
        val inbox = NotificationCompat.InboxStyle().setSummaryText(text)
        shown.forEach { inbox.addLine("${it.source.displayName}  ${it.title}") }

        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, Routes.NOTIFICATION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AppNotificationChannels.CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_notification_notice)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(inbox)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setNumber(items.size)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
