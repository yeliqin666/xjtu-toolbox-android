package com.xjtu.toolbox.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 后台检查所选通知源。
 *
 * 严格按系统省电策略排程，不申请忽略电池优化、不用精确闹钟、不拉前台服务：
 * - 周期 4 小时，弹性窗口 1 小时，交给系统和其他作业一起批次跑
 * - 没网、电量低、存储紧张时不跑
 * - 失败用指数退避，最短 10 分钟
 * - 系统处于 Doze / App Standby 时推迟，这是预期行为
 */
class NoticeWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!NoticeWatchStore.isEnabled(applicationContext)) return Result.success()
        if (NoticeWatchStore.sources(applicationContext).isEmpty()) return Result.success()
        return try {
            NoticeWatchSync.sync(applicationContext, notify = true, force = false)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object NoticeWatchScheduler {
    private const val UNIQUE_PERIODIC = "notice_watch"
    private const val UNIQUE_ONCE = "notice_watch_once"

    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        val enabled = NoticeWatchStore.isEnabled(app) && NoticeWatchStore.sources(app).isNotEmpty()
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
            wm.cancelUniqueWork(UNIQUE_ONCE)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val periodic = PeriodicWorkRequestBuilder<NoticeWatchWorker>(
            4, TimeUnit.HOURS,
            1, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        // 从未同步过时补一次即时任务。不加速、不精确，等约束满足再跑。
        if (NoticeWatchStore.lastSyncAt(app) == 0L) {
            val once = OneTimeWorkRequestBuilder<NoticeWatchWorker>()
                .setConstraints(constraints)
                .build()
            wm.enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, once)
        }
    }
}
