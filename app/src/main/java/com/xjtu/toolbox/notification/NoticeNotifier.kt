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
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.RequestCodes

/**
 * 把新教务通知落到系统通知栏。
 *
 * 不绕过用户开关：没授权或渠道被关就直接返回。
 * 多条合成一条 Inbox，避免一次检查刷出一排。
 */
internal object NoticeNotifier {
    const val NOTIFICATION_ID = 3101
    const val EXTRA_ASK_TEXT = "extra_ask_pidai_text"
    const val EXTRA_ASK_SNAPSHOT = "extra_ask_pidai_snapshot"
    const val EXTRA_ASK_INBOX_IDS = "extra_ask_pidai_inbox_ids"

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
            // 只有一条时直接打开正文（#138），多条才进通知列表
            val single = shown.singleOrNull()?.link?.takeIf { it.startsWith("http") }
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, single?.let { AppRoute.Browser(it).id } ?: AppRoute.Notification.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            RequestCodes.NOTICE_PUSH,
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
            .addAction(R.drawable.ic_notification_notice, "问小岱", askPidaiIntent(context, shown))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** 走不导出的别名（[DeepLinkRouter.PIDAI_NOTICE_ALIAS]）：只有本应用能发这个 Intent，到了就自动发送。 */
    private fun askPidaiIntent(context: Context, shown: List<Notification>): PendingIntent {
        val forward = com.xjtu.toolbox.agent.AgentForward.notices(shown)
        val intent = Intent().apply {
            setClassName(context, com.xjtu.toolbox.nav.DeepLinkRouter.PIDAI_NOTICE_ALIAS)
            putExtra(EXTRA_ASK_TEXT, forward.text)
            putExtra(EXTRA_ASK_SNAPSHOT, forward.snapshot)
            putExtra(EXTRA_ASK_INBOX_IDS, shown.map { "notice:${it.link}" }.toTypedArray())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            RequestCodes.NOTICE_ASK_PIDAI,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
