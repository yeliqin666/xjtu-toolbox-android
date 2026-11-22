package com.xjtu.toolbox.notification

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import com.xjtu.toolbox.jwapp.JwappApi
import com.xjtu.toolbox.jwapp.NewGradeCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "GradeWatch"

/**
 * 后台查新成绩（#428）：出了分就发一条系统通知，不用等打开 App。
 *
 * 成绩走移动教务（和首页成绩源同一个接口），会话用 [HeadlessSessions]；拿不到会话（要短信验证、
 * 密码失效）就直接结束这一轮，不重试，和 [ScheduleWatchWorker] 一样。
 *
 * 去重游标和首页刷新共用（[HomeStats.advanceGradeCursor]）：这里报过的，前台就不再冒气泡；
 * 前台先看到的，这里也不再报。通知发不出去（没授权 / 渠道被关）时退回写气泡待办，让屁岱接手。
 */
class GradeWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // 开关关着：连会话都不要，更不发请求
        if (!ReminderStore.isEnabled(app, ReminderKind.GRADE)) return Result.success()

        val terms = try {
            val site = HeadlessSessions.site(LoginType.JWAPP) ?: return Result.success()
            withContext(Dispatchers.IO) { JwappApi(site).getGrade() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "本轮检查失败：${e.message}")
            return Result.retry()
        }
        val total = terms.sumOf { it.scoreList.size }
        // 空表多半是教务抽风，不拿它推进游标
        if (total == 0) return Result.success()

        val account = AccountContext.activeAccountId
        val fresh = HomeStats.advanceGradeCursor(app, terms, account)
        if (fresh.isEmpty()) return Result.success()

        InboxStore.post(OwnInbox.grade(fresh.size, total, fresh.map { NewGradeCursor.displayName(it.courseName) }), account)
        if (!ReminderNotifier.notifyNewGrades(app, NewGradeCursor.summary(fresh))) {
            HomeStats.setPendingNewScores(app, HomeStats.pendingNewScores(app, account) + fresh.size, account)
        }
        return Result.success()
    }
}

object GradeWatchScheduler {
    private const val UNIQUE = "grade_watch"

    /**
     * 6 小时一轮、弹性 1 小时：出分是低频事件，晚一两个小时知道无妨，没必要比课表、作业查得更勤；
     * 没网 / 电量低 / 存储紧张不跑。
     */
    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!ReminderStore.isEnabled(app, ReminderKind.GRADE) || !HeadlessSessions.hasAccount()) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }

        val request = PeriodicWorkRequestBuilder<GradeWatchWorker>(
            6, TimeUnit.HOURS,
            1, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
