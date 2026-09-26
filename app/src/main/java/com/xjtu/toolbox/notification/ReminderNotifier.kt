package com.xjtu.toolbox.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.nav.AppRoute

/**
 * 三类后台提醒的系统通知。
 *
 * 图书馆走 [AppNotificationChannels.CHANNEL_REMINDER]：截止时间是硬的，过点就丢座位。
 * 课表变更、考试、作业走 [AppNotificationChannels.CHANNEL_APP]：要紧但不按分钟算。
 *
 * 权限判断复用 [NoticeNotifier.canPost]，没授权就不发——不绕过用户的开关。
 */
internal object ReminderNotifier {

    private const val ID_LIBRARY = 3201
    private const val ID_SCHEDULE = 3202
    private const val ID_EXAM = 3203
    private const val ID_LMS = 3204

    fun canPost(context: Context): Boolean = NoticeNotifier.canPost(context)

    /** @return 是否真的发出去了。发不出去时调用方该退回应用内提示。 */
    fun notifyLibrary(context: Context, action: String, seat: String?): Boolean = post(
        context = context,
        id = ID_LIBRARY,
        channel = AppNotificationChannels.CHANNEL_REMINDER,
        icon = R.drawable.ic_notification_seat,
        title = "图书馆座位要$action",
        text = buildString {
            seat?.takeIf { it.isNotBlank() }?.let { append("座位 $it · ") }
            append(if (action == "入馆签到") "超时没签到今天就不能再线上预约了" else "还没返座，记得回去签一下")
        },
        route = AppRoute.Library,
    )

    fun notifyScheduleChange(context: Context, summary: String): Boolean = post(
        context = context,
        id = ID_SCHEDULE,
        channel = AppNotificationChannels.CHANNEL_APP,
        icon = R.drawable.ic_notification_notice,
        title = "课表有变动",
        text = summary,
        route = AppRoute.Schedule,
    )

    fun notifyExam(context: Context, courseName: String, label: String, detail: String): Boolean = post(
        context = context,
        id = ID_EXAM,
        channel = AppNotificationChannels.CHANNEL_APP,
        icon = R.drawable.ic_notification_notice,
        title = "$courseName$label",
        text = detail,
        route = AppRoute.Schedule,
    )

    fun notifyLmsDeadlines(context: Context, items: List<String>): Boolean {
        if (items.isEmpty()) return false
        val inbox = NotificationCompat.InboxStyle()
        items.take(5).forEach { inbox.addLine(it) }
        return post(
            context = context,
            id = ID_LMS,
            channel = AppNotificationChannels.CHANNEL_APP,
            icon = R.drawable.ic_notification_notice,
            title = if (items.size == 1) "作业快截止了" else "${items.size} 份作业快截止了",
            text = items.first(),
            route = AppRoute.Lms(),
            style = inbox,
        )
    }

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        icon: Int,
        title: String,
        text: String,
        route: AppRoute,
        style: NotificationCompat.Style? = null,
    ): Boolean {
        if (!canPost(context)) return false
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, route.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            // 同一件事复查多轮时只响第一次，后面几轮静默更新，不反复打扰。
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
        if (style != null) builder.setStyle(style) else builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        NotificationManagerCompat.from(context).notify(id, builder.build())
        return true
    }
}
