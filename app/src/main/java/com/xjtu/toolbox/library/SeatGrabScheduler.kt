package com.xjtu.toolbox.library

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 抢座闹钟调度器 — 使用 AlarmManager 精确定时
 *
 * 时间策略：
 * - 设定时间 = 用户配置的抢座时刻（默认 22:00:00）
 * - 实际触发 = 设定时间 - 60 秒（提前 60s 启动 Service 做认证预热）
 */
object SeatGrabScheduler {

    private const val TAG = "SeatGrabScheduler"
    private const val REQUEST_CODE = 0x5EA7 // "SEAT"

    /** 提前触发量（毫秒）：60 秒预热认证 */
    const val ADVANCE_MS = 60_000L

    /**
     * 设置精确闹钟。
     * @return true 如果设置成功；false 如果权限不足
     */
    fun schedule(context: Context, config: SeatGrabConfig): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Android 12+ 需要检查精确闹钟权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "canScheduleExactAlarms = false, 无法设置精确闹钟")
                return false
            }
        }

        val triggerTime = parseTriggerTime(config.triggerTimeStr)
        val triggerEpochMs = computeTriggerEpochMs(triggerTime)

        val intent = Intent(context, SeatGrabReceiver::class.java).apply {
            action = "com.xjtu.toolbox.SEAT_GRAB_ALARM"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 提前 60 秒触发（认证预热 + 连接预热）
        val actualTriggerMs = triggerEpochMs - ADVANCE_MS

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            actualTriggerMs,
            pendingIntent
        )

        Log.i(TAG, "闹钟已设置：目标时刻=${config.triggerTimeStr}, 实际触发=${
            java.time.Instant.ofEpochMilli(actualTriggerMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }")
        return true
    }

    /**
     * 取消闹钟
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SeatGrabReceiver::class.java).apply {
            action = "com.xjtu.toolbox.SEAT_GRAB_ALARM"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "闹钟已取消")
    }

    /**
     * 闹钟是否已设置
     */
    fun isScheduled(context: Context): Boolean {
        val intent = Intent(context, SeatGrabReceiver::class.java).apply {
            action = "com.xjtu.toolbox.SEAT_GRAB_ALARM"
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null
    }

    // ── 内部工具 ──

    private fun parseTriggerTime(timeStr: String): LocalTime {
        return try {
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm:ss"))
        } catch (_: Exception) {
            LocalTime.of(22, 0, 0) // 默认 22:00
        }
    }

    /**
     * 计算下一个可用的触发时间戳。
     * 如果今天的目标时刻已经过了，则取次日。
     */
    private fun computeTriggerEpochMs(triggerTime: LocalTime): Long {
        var targetDateTime = LocalDate.now().atTime(triggerTime)

        // 如果今天已过（含 60s 提前量），取次日
        if (targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - ADVANCE_MS
            <= System.currentTimeMillis()
        ) {
            targetDateTime = targetDateTime.plusDays(1)
        }

        return targetDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
