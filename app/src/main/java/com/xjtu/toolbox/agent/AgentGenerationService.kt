package com.xjtu.toolbox.agent

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xjtu.toolbox.MainActivity
import com.xjtu.toolbox.R
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.AppNotificationChannels
import com.xjtu.toolbox.util.RequestCodes

/**
 * 小岱生成期间的前台服务，只挂一条通知、不干活：没有它，切出 App 几秒后进程就被系统冻结，
 * 流式连接空闲被断，回来时回复已中断。
 *
 * 用 startService 而非 startForegroundService 起：只在前台发消息时启动，没有「5 秒内必须
 * startForeground」的约束，生成很快结束、服务还没起来就被停也不会崩。
 */
class AgentGenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val launch = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_ROUTE, AppRoute.Agent.id)
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notification = NotificationCompat.Builder(this, AppNotificationChannels.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_notification_notice)
            .setContentTitle("正在生成回复")
            .setContentText("切出应用也会继续，完成后自动消失")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, RequestCodes.AGENT_GENERATION, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: RuntimeException) {
            // 系统不让升前台（如已在后台）：不保活，生成照常跑
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 起 dataSync 有每日时长上限，到点必须自己停。 */
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val ID = 3207

        fun start(context: Context) {
            // 后台起服务会抛 IllegalStateException：没保活就算了
            runCatching { context.startService(Intent(context, AgentGenerationService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentGenerationService::class.java))
        }
    }
}
