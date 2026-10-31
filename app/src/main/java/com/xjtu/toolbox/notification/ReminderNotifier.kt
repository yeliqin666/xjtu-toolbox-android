package com.xjtu.toolbox.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.util.RequestCodes

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
    private const val ID_EXAM_CHANGE = 3205

    fun canPost(context: Context): Boolean = NoticeNotifier.canPost(context)

    /** @param status 替换正文，回显按钮签到的进度或结果。
     *  @param withAction 是否带「签到」按钮（#204）。 */
    fun notifyLibrary(
        context: Context,
        action: String,
        seat: String?,
        status: String? = null,
        withAction: Boolean = true,
    ): Boolean = post(
        context = context,
        id = ID_LIBRARY,
        requestCode = RequestCodes.REMINDER_LIBRARY,
        channel = AppNotificationChannels.CHANNEL_REMINDER,
        icon = R.drawable.ic_notification_seat,
        title = "图书馆座位要$action",
        text = status ?: buildString {
            seat?.takeIf { it.isNotBlank() }?.let { append("座位 $it · ") }
            append(if (action == "入馆签到") "超时没签到今天就不能再线上预约了" else "还没返座，记得回去签一下")
        },
        route = AppRoute.Library,
        actions = if (withAction) listOf(librarySignAction(context, action)) else emptyList(),
    )

    /** 撤掉图书馆提醒（#203）。 */
    fun cancelLibrary(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_LIBRARY)
    }

    private fun librarySignAction(context: Context, action: String): NotificationCompat.Action {
        val intent = Intent(context, LibraryActionReceiver::class.java).apply {
            this.action = LibraryActionReceiver.INTENT_ACTION
            putExtra(LibraryReminderWorker.KEY_ACTION, action)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            RequestCodes.REMINDER_LIBRARY_ACTION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = if (action == LibraryReminderWorker.ACTION_RETURN) "返座签到" else "签到"
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    fun notifyScheduleChange(context: Context, summary: String): Boolean = post(
        context = context,
        id = ID_SCHEDULE,
        requestCode = RequestCodes.REMINDER_SCHEDULE,
        channel = AppNotificationChannels.CHANNEL_APP,
        icon = R.drawable.ic_notification_notice,
        title = "课表有变动",
        text = summary,
        route = AppRoute.Schedule,
    )

    /** 自建待办快截止。一条待办一个通知 id，同一条重复提醒时覆盖上一条而不是堆叠。 */
    fun notifyTodo(context: Context, todoId: String, title: String, detail: String): Boolean = post(
        context = context,
        id = ("self_todo:$todoId").hashCode(),
        requestCode = RequestCodes.REMINDER_TODO,
        channel = AppNotificationChannels.CHANNEL_APP,
        icon = R.drawable.ic_notification_notice,
        title = "待办：$title",
        text = detail,
        route = AppRoute.Inbox,
    )

    /** 屁岱的简报或定时任务结果。[key] 区分是哪一份，同一份覆盖不堆叠；[pushAt] 非空时点开定位到「推送记录」里那条（#194）。 */
    fun notifyPidai(context: Context, key: String, title: String, text: String, pushAt: Long? = null): Boolean = post(
        context = context,
        id = ("pidai:$key").hashCode(),
        // 与通知 id 一致：每份推送各带各的定位 extras，点旧通知不会跳到最新那条
        requestCode = ("pidai:$key").hashCode(),
        channel = AppNotificationChannels.CHANNEL_APP,
        icon = R.drawable.ic_notification_notice,
        title = title,
        text = text,
        route = if (pushAt != null) AppRoute.Agent else AppRoute.Inbox,
        style = androidx.core.app.NotificationCompat.BigTextStyle().bigText(text),
        extras = pushAt?.let { mapOf(com.xjtu.toolbox.agent.AgentPushFocus.EXTRA_PUSH_AT to it) }.orEmpty(),
    )

    fun notifyExam(context: Context, courseName: String, label: String, detail: String): Boolean = post(
        context = context,
        id = ID_EXAM,
        requestCode = RequestCodes.REMINDER_EXAM,
        channel = AppNotificationChannels.CHANNEL_APP,
        icon = R.drawable.ic_notification_notice,
        title = "$courseName$label",
        text = detail,
        route = AppRoute.Schedule,
    )

    /** 考试的时间、考场、座位被改了。和「快考试了」分开发，免得一条盖掉另一条。 */
    fun notifyExamChange(context: Context, changes: List<String>): Boolean {
        if (changes.isEmpty()) return false
        val inbox = NotificationCompat.InboxStyle()
        changes.take(5).forEach { inbox.addLine(it) }
        return post(
            context = context,
            id = ID_EXAM_CHANGE,
            requestCode = RequestCodes.REMINDER_EXAM_CHANGE,
            channel = AppNotificationChannels.CHANNEL_APP,
            icon = R.drawable.ic_notification_notice,
            title = "考试安排有变",
            text = changes.first(),
            route = AppRoute.Schedule,
            style = inbox,
        )
    }

    fun notifyLmsDeadlines(context: Context, items: List<String>): Boolean {
        if (items.isEmpty()) return false
        val inbox = NotificationCompat.InboxStyle()
        items.take(5).forEach { inbox.addLine(it) }
        return post(
            context = context,
            id = ID_LMS,
            requestCode = RequestCodes.REMINDER_LMS,
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
        requestCode: Int,
        channel: String,
        icon: Int,
        title: String,
        text: String,
        route: AppRoute,
        style: NotificationCompat.Style? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
        extras: Map<String, Long> = emptyMap(),
    ): Boolean {
        if (!canPost(context)) return false
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, route.id)
            extras.forEach { (k, v) -> putExtra(k, v) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            requestCode,
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
        actions.forEach { builder.addAction(it) }
        if (style != null) builder.setStyle(style) else builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        NotificationManagerCompat.from(context).notify(id, builder.build())
        return true
    }
}
