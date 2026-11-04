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
import com.xjtu.toolbox.lms.LmsApi
import com.xjtu.toolbox.lms.LmsDueCollector
import com.xjtu.toolbox.lms.remaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import java.util.concurrent.TimeUnit

private const val TAG = "LmsDeadline"

/** 距截止还有这么久就提醒。 */
private const val AHEAD_HOURS = 48L

/**
 * 后台盯思源学堂的作业截止时间。
 *
 * 要逐门课查活动列表，是这几类提醒里最费的一个，所以周期压到 4 小时并要求电量不低。
 * 拉到的作业顺手写进待办读的截止缓存（不受 48 小时窗口限制）；关掉这条提醒后 Worker 不跑，
 * 待办改由首页刷新一天一更。
 *
 * 每份作业只提醒一次：第一次落进 48 小时窗口时发，之后不再重复；已经过了截止时间的
 * 不发——那时候提醒除了添堵没有别的用。
 *
 * 自建待办（`SelfTodoReminderWorker`）另算：它按「截止前 48 小时、遇到 0–7 点顺延到 7 点」
 * 推一次，窗口时长和这里刻意保持一致，但两条链路各走各的（作业这边要联网拉取，不能被本地待办拖累）。
 */
class LmsDeadlineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ReminderStore.isEnabled(app, ReminderKind.LMS)) return Result.success()

        val due = try {
            // 需要短信验证 / 密码失效：这一轮直接放弃，等下次正常调度，别退避重试再提交一次密码
            val site = HeadlessSessions.site(LoginType.LMS) ?: return Result.success()
            val account = AccountContext.activeAccountId
            withContext(Dispatchers.IO) {
                val api = LmsApi(site)
                val perCourse = LmsDueCollector.activities(api, api.getMyCourses())
                LmsDueCollector.collect(app, api, perCourse, account.orEmpty()) { AccountContext.activeAccountId == account }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "本轮检查失败：${e.message}")
            return Result.retry()
        }

        val now = Clock.System.now()
        val horizon = now + AHEAD_HOURS.hours
        val fresh = due
            .filter { !it.submitted }
            .mapNotNull { d -> runCatching { Instant.parse(d.deadline) }.getOrNull()?.let { it to d } }
            .filter { (deadline, _) -> deadline >= now && deadline <= horizon }
            .sortedBy { it.first }
            .map { (deadline, d) -> "${d.courseId}-${d.activityId}-$deadline" to "[${d.courseName}] ${d.title} · ${remaining(now, deadline)}" }
            .filterNot { ReminderStore.hasSeen(app, ReminderKind.LMS, it.first) }
        if (fresh.isEmpty()) return Result.success()

        if (ReminderNotifier.notifyLmsDeadlines(app, fresh.map { it.second })) {
            ReminderStore.markSeen(app, ReminderKind.LMS, fresh.map { it.first })
        }
        return Result.success()
    }
}

object LmsDeadlineScheduler {
    private const val UNIQUE = "lms_deadline"

    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!ReminderStore.isEnabled(app, ReminderKind.LMS) || !HeadlessSessions.hasAccount()) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }

        val request = PeriodicWorkRequestBuilder<LmsDeadlineWorker>(
            4, TimeUnit.HOURS,
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
