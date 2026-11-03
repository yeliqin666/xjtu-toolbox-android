package com.xjtu.toolbox.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xjtu.toolbox.XjtuApp
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.data.AppDatabase
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.schedule.HolidayApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.util.CampusZoneId
import com.xjtu.toolbox.util.RequestCodes
import com.xjtu.toolbox.util.toJavaTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

/** 一条持续更新的通知 + 一个下一边界闹钟；不跑每分钟 Worker，不启动前台服务。 */
internal object DynamicScheduleScheduler {
    private const val PREFS = "dynamic_schedule_notification"
    internal const val ACTION_REFRESH = "com.xjtu.toolbox.DYNAMIC_SCHEDULE_REFRESH"
    private val lock = Mutex()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) cancel(context)
        requestRefresh(context)
    }

    fun canScheduleExactly(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun requestRefresh(context: Context) {
        val app = context.applicationContext as XjtuApp
        app.applicationScope.launch { refresh(app) }
    }

    /** 由应用 IO scope 或广播的 goAsync 调用，只读当前账号的本机缓存与 Room。 */
    suspend fun refresh(context: Context) = lock.withLock {
        try {
            if (!isEnabled(context) || !DynamicScheduleNotifier.canPost(context)) {
                cancel(context)
                return@withLock
            }
            val accounts = AccountStore()
            val account = accounts.activeAccountId()?.takeIf { it.isNotBlank() }
            if (account == null) {
                cancel(context)
                return@withLock
            }
            val cache = DataCache(context, account)
            val term = ScheduleCache.readCurrentTermSchedule(cache)
            val custom = term?.let {
                AppDatabase.getInstance(context).customCourseDao().getByTerm(account, it.code).map { row -> row.toCourseItem() }
            }.orEmpty()
            val now = ZonedDateTime.now(CampusZoneId)
            val plan = planDynamicSchedule(
                courses = term?.courses.orEmpty() + custom,
                termStart = term?.start?.toJavaTime(),
                now = now,
                holidays = HolidayApi.peekCached(cache).keys.map { it.toJavaTime() }.toSet(),
            )
            // 查 Room 期间切了账号/关了开关，旧账号这一轮必须失效。
            if (accounts.activeAccountId() != account ||
                (AccountContext.activeAccountId != null && AccountContext.activeAccountId != account) ||
                !isEnabled(context)) {
                cancel(context)
                return@withLock
            }
            DynamicScheduleNotifier.post(context, plan, now.toInstant().toEpochMilli())
            schedule(context, plan.nextRefreshAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cancel(context)
            Log.w("DynamicSchedule", "本地日程通知刷新失败", e)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.DYNAMIC_SCHEDULE_ALARM,
        Intent(context, DynamicScheduleReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun schedule(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
                // 用户可能恰在此时撤回「闹钟和提醒」权限，回退系统批次调度。
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    internal fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
        DynamicScheduleNotifier.cancel(context)
    }
}

/** 重启、换包、改钟和精确闹钟授权后重建排程；无需联网。 */
class DynamicScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                DynamicScheduleScheduler.ACTION_REFRESH, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )) return
        val pending = goAsync()
        val app = context.applicationContext as XjtuApp
        app.applicationScope.launch {
            try {
                DynamicScheduleScheduler.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}
