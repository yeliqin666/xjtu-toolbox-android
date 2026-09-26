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
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.lms.LmsActivityType
import com.xjtu.toolbox.lms.LmsApi
import com.xjtu.toolbox.lms.deadlineInstant
import com.xjtu.toolbox.lms.remaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val TAG = "LmsDeadline"

/** 距截止还有这么久就提醒。 */
private const val AHEAD_HOURS = 48L

/**
 * 后台盯思源学堂的作业截止时间。
 *
 * 要逐门课查活动列表，是这三类提醒里最费的一个，所以周期压到 4 小时并要求电量不低。
 *
 * 每份作业只提醒一次：第一次落进 48 小时窗口时发，之后不再重复；已经过了截止时间的
 * 不发——那时候提醒除了添堵没有别的用。
 */
class LmsDeadlineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ReminderStore.isEnabled(app, ReminderKind.LMS)) return Result.success()

        val site = HeadlessSessions.site(app, LoginType.LMS) ?: return Result.retry()
        val due = try {
            withContext(Dispatchers.IO) { collectDue(LmsApi(site)) }
        } catch (e: Exception) {
            Log.w(TAG, "本轮检查失败：${e.message}")
            return Result.retry()
        }

        val fresh = due.filterNot { ReminderStore.hasSeen(app, ReminderKind.LMS, it.id) }
        if (fresh.isEmpty()) return Result.success()

        if (ReminderNotifier.notifyLmsDeadlines(app, fresh.map { it.line })) {
            ReminderStore.markSeen(app, ReminderKind.LMS, fresh.map { it.id })
        }
        return Result.success()
    }

    private class Due(val id: String, val line: String)

    private suspend fun collectDue(api: LmsApi): List<Due> {
        val now = Instant.now()
        val horizon = now.plus(Duration.ofHours(AHEAD_HOURS))
        val result = mutableListOf<Pair<Instant, Due>>()
        // 单门课查失败不该毁掉整轮：思源学堂对个别课程偶发 403（课程已归档等）。
        for (course in api.getMyCourses()) {
            runCatching {
                api.getCourseActivities(course.id)
                    .filter { it.type == LmsActivityType.HOMEWORK }
                    .forEach { activity ->
                        val deadline = activity.deadlineInstant() ?: return@forEach
                        if (deadline.isBefore(now) || deadline.isAfter(horizon)) return@forEach
                        if (activity.userSubmitCount > 0) return@forEach
                        result += deadline to Due(
                            id = "${course.id}-${activity.id}-$deadline",
                            line = "[${course.name}] ${activity.title} · ${remaining(now, deadline)}",
                        )
                    }
            }.onFailure { Log.w(TAG, "课程 ${course.name} 活动拉取失败：${it.message}") }
        }
        return result.sortedBy { it.first }.map { it.second }
    }
}

object LmsDeadlineScheduler {
    private const val UNIQUE = "lms_deadline"

    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!ReminderStore.isEnabled(app, ReminderKind.LMS) || !HeadlessSessions.hasAccount(app)) {
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
