package com.xjtu.toolbox.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import java.util.concurrent.TimeUnit

/** 一条持续更新的通知 + 一个下一边界闹钟；不跑每分钟 Worker，不启动前台服务。 */
internal object DynamicScheduleScheduler {
    private const val PREFS = "dynamic_schedule_notification"
    internal const val ACTION_REFRESH = "com.xjtu.toolbox.DYNAMIC_SCHEDULE_REFRESH"
    /** 本轮刷新出错时，隔多久再试一次；不能直接撤掉闹钟，否则不打开 App 就再也醒不过来。 */
    private const val RETRY_MS = 15 * 60_000L
    private val lock = Mutex()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    /** 跨楼时提前提醒出发，默认开。 */
    fun isWalkHintOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("walk_hint", true)

    fun setWalkHintOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("walk_hint", on).apply()
        requestRefresh(context)
    }

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
            val notifyOn = isEnabled(context) && DynamicScheduleNotifier.canPost(context)
            val dndOn = AutoDndScheduler.isEnabled(context)
            if (!notifyOn && !dndOn) {
                cancel(context)
                AutoDndScheduler.restore(context)
                DynamicScheduleWatchdog.cancel(context)
                return@withLock
            }
            val accounts = AccountStore()
            val account = accounts.activeAccountId()?.takeIf { it.isNotBlank() }
            if (account == null) {
                cancel(context)
                AutoDndScheduler.restore(context)
                DynamicScheduleWatchdog.cancel(context)
                return@withLock
            }
            DynamicScheduleWatchdog.ensure(context)
            val cache = DataCache(context, account)
            val term = ScheduleCache.readCurrentTerm(cache)
            // readCourses 会把课名写入当前全局账号的色表；后台仅做无副作用的显式账号读取。
            val courses = term?.let {
                ScheduleCache.readOptimizedCourses(cache, it, Long.MAX_VALUE)
                    ?: ScheduleCache.readRawCourses(cache, it, Long.MAX_VALUE)
            }.orEmpty()
            val custom = term?.let {
                AppDatabase.getInstance(context).customCourseDao().getByTerm(account, it).map { row -> row.toCourseItem() }
            }.orEmpty()
            val now = ZonedDateTime.now(CampusZoneId)
            val nowMs = now.toInstant().toEpochMilli()
            val allCourses = courses + custom
            val termStart = term?.let { ScheduleCache.readStartDate(cache, it) }?.toJavaTime()
            val holidays = HolidayApi.peekCached(cache).keys.map { it.toJavaTime() }.toSet()
            val plan = planDynamicSchedule(allCourses, termStart, now, holidays)
            // 查 Room 期间切了账号/关了开关，旧账号这一轮必须失效。
            if (accounts.activeAccountId() != account ||
                (AccountContext.activeAccountId != null && AccountContext.activeAccountId != account)) {
                cancel(context)
                AutoDndScheduler.restore(context)
                return@withLock
            }
            val todayItems = todayScheduleItems(allCourses, termStart, now, holidays)
            // 跨楼「该出发」：只在显示的是下一节（未开始）时生效，出发时刻本身也是一次唤醒
            val walk = if (isWalkHintOn(context) && !plan.ongoing) {
                plan.current?.let { walkPlanFor(todayItems, it) }
            } else null
            if (notifyOn && isEnabled(context)) DynamicScheduleNotifier.post(context, plan, nowMs, walk?.let { walkHintText(it, nowMs) })
            else DynamicScheduleNotifier.cancel(context)
            val dndNext = if (dndOn) {
                AutoDndScheduler.apply(context, todayItems, nowMs)
            } else {
                AutoDndScheduler.restore(context)
                null
            }
            val walkWake = walk?.leaveAt?.takeIf { it > nowMs }
            // 几件事共用一个闹钟：取最早的边界；午夜那一刻也要醒来重排第二天的课。
            schedule(context, listOfNotNull(plan.nextRefreshAt, dndNext, walkWake).min())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只收起通知，闹钟改排到一刻钟后重试：以前这里连闹钟一起撤掉，偶发一次读缓存失败，
            // 自动勿扰和动态通知就一直停着，直到下次打开 App 才恢复。
            DynamicScheduleNotifier.cancel(context)
            runCatching { schedule(context, System.currentTimeMillis() + RETRY_MS) }
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

/**
 * 闹钟链的兜底：每 30 分钟用 WorkManager 重排一次。
 *
 * 平时全靠 [DynamicScheduleScheduler.schedule] 那一个闹钟一环扣一环地往下排，一旦被系统吞掉
 * （部分国产系统清后台会连带清闹钟，没给精确闹钟时还可能被推迟很久），后面就全断了，
 * 用户看到的就是「不打开 App 就不切勿扰」。WorkManager 的任务由系统 JobScheduler 托管，
 * 进程没了也会被重新拉起；这里只读本机缓存、不联网，跑一次很便宜，所以不加网络或电量约束。
 * 动态通知和自动勿扰都关掉、或没有账号时随 [DynamicScheduleScheduler.refresh] 一起撤掉。
 */
internal object DynamicScheduleWatchdog {
    private const val UNIQUE = "dynamic_schedule_watchdog"

    fun ensure(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<DynamicScheduleWatchdogWorker>(30, TimeUnit.MINUTES).build()
            // KEEP：每到一个边界都会走到这里，不能每次都把周期重置掉。
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w("DynamicSchedule", "兜底任务排程失败", it) }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE) }
    }
}

class DynamicScheduleWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DynamicScheduleScheduler.refresh(applicationContext)
        return Result.success()
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
