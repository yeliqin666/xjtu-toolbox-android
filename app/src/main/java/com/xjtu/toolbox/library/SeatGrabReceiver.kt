package com.xjtu.toolbox.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xjtu.toolbox.R

/**
 * 抢座闹钟广播接收器
 *
 * 接收 AlarmManager 触发 + 设备重启后恢复闹钟
 */
class SeatGrabReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SeatGrabReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: action=${intent.action}")

        when (intent.action) {
            "com.xjtu.toolbox.SEAT_GRAB_ALARM" -> {
                // 闹钟触发 → 校验配置后启动前台 Service
                val config = SeatGrabConfigStore.load(context)
                if (config.enabled && config.targetSeats.isNotEmpty()) {
                    startGrabService(context)
                } else {
                    Log.w(TAG, "收到闹钟但配置未启用，忽略")
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // 设备重启 → 重新注册闹钟（如果用户之前设定了抢座）
                val config = SeatGrabConfigStore.load(context)
                if (config.enabled && config.targetSeats.isNotEmpty()) {
                    val ok = SeatGrabScheduler.schedule(context, config)
                    Log.i(TAG, "BOOT_COMPLETED: 恢复闹钟, success=$ok")
                }
            }

            else -> Log.w(TAG, "未知 action: ${intent.action}")
        }
    }

    private fun startGrabService(context: Context) {
        val serviceIntent = Intent(context, SeatGrabService::class.java)
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "启动 SeatGrabService 失败", e)
            // 发送通知告知用户服务启动失败
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = SeatGrabService.CHANNEL_ID
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(channelId, SeatGrabService.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                    )
                }
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_notification_seat)
                    .setContentTitle("抢座服务启动失败")
                    .setContentText("可能被系统限制，请检查自启动权限和电池优化设置")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        "抢座服务无法启动: ${e.message}\n请前往系统设置允许应用自启动，并关闭电池优化。"
                    ))
                    .setAutoCancel(true)
                    .build()
                nm.notify(5099, notification)
            } catch (_: Exception) { /* 通知也失败了，无能为力 */ }
        }
    }
}
