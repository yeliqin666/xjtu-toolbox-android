package com.xjtu.toolbox.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService

/**
 * 系统通知渠道。
 *
 * 三个 channel 各司其职：
 * - [CHANNEL_APP]     常规业务通知（余额低 / 新通知 / 新成绩），有声音但不打扰
 * - [CHANNEL_REMINDER] 上课前提醒，高优先级，锁屏可见
 * - [CHANNEL_SILENT]  Agent 主动拉新（与 [com.xjtu.toolbox.agent.ProactiveBubble] 冗余通道），
 *                     LOW importance，不响不弹，纯抽屉里落一条
 *
 * 必须在 [Application.onCreate] 调一次 [ensureChannels]，否则 8.0+ 发通知会被系统丢弃。
 * 调用幂等：重复注册同名 channel 会覆盖，不会泄漏。
 */
object AppNotificationChannels {

    /** 业务通知：余额 / 教务通知 / 新成绩。 */
    const val CHANNEL_APP = "app"

    /** 上课前 15 分钟提醒，HIGH importance + 锁屏可见。 */
    const val CHANNEL_REMINDER = "reminder"

    /** Agent 主动拉新冗余通道，不打扰。 */
    const val CHANNEL_SILENT = "silent"

    /** 启动时建一次。重复调用无副作用。 */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APP,
                "应用通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "余额变动、新通知、新成绩等业务消息"
                enableLights(true)
                enableVibration(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                "上课提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "上课前 15 分钟提醒，锁屏可见"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                "静默消息",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Agent 主动拉新的备份通道，不响不弹"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
        )
    }

    /**
     * 全局通知是否被用户关闭（系统级 / 应用级）。
     * 返回 false 时任何 channel 都发不出去，调用方应降级为 Toast / Snackbar。
     */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}