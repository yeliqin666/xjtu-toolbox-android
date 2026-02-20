package com.xjtu.toolbox.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
        }
    }
}
