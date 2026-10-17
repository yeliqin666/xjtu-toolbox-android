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
import com.xjtu.toolbox.schedule.ExamCountdown
import com.xjtu.toolbox.schedule.JwxtScheduleApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleDiff
import com.xjtu.toolbox.schedule.scheduleDiffStore
import com.xjtu.toolbox.schedule.ScheduleSourceRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "ScheduleWatch"

/** 考试提前几天开始提醒。与屁岱气泡的口径一致，别一个说三天一个说一周。 */
private const val EXAM_AHEAD_DAYS = 3

/**
 * 后台盯课表：被调课了、考试快到了，就发一条通知。
 *
 * 排程策略完全照搬 [NoticeWatchWorker]：周期 4 小时、弹性 1 小时，没网 / 电量低 /
 * 存储紧张不跑，不申请忽略电池优化、不用精确闹钟、不拉前台服务。课表变更和考试
 * 倒计时都不是按分钟算的事，跟着系统的批次跑就够。
 */
class ScheduleWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ReminderStore.isEnabled(app, ReminderKind.SCHEDULE)) return Result.success()

        return try {
            // 需要短信验证 / 密码失效：这一轮直接放弃，等下次正常调度，别退避重试再提交一次密码
            val site = HeadlessSessions.site(LoginType.JWXT) ?: return Result.success()
            withContext(Dispatchers.IO) {
                val api = JwxtScheduleApi(site)
                val term = api.currentTerm()
                checkScheduleChange(app, api, term)
                checkUpcomingExam(app, api, term)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "本轮检查失败：${e.message}")
            Result.retry()
        }
    }

    /**
     * [ScheduleDiff.diffAndStore] 比完就把快照推进了，所以这一轮的变更只会被报一次：
     * 这里发了通知，日程页下次进来就不会再弹同样的 snackbar。发不出通知（没授权 /
     * 渠道被关）时退回写 pending，让屁岱气泡接手——总不能两边都不说。
     */
    private suspend fun checkScheduleChange(context: Context, api: JwxtScheduleApi, term: String) {
        val courses = ScheduleSourceRouter.getSchedule(
            jwxt = api,
            termCode = term,
            manager = HeadlessSessions.manager(),
        )
        if (courses.isEmpty()) return
        val summary = ScheduleDiff.summarize(ScheduleDiff.diffAndStore(scheduleDiffStore(), term, courses, ScheduleSourceRouter.servedSource().key)) ?: return
        com.xjtu.toolbox.inbox.InboxStore.post(com.xjtu.toolbox.inbox.OwnInbox.scheduleChange(summary))
        if (!ReminderNotifier.notifyScheduleChange(context, summary)) {
            ScheduleDiff.setPending(scheduleDiffStore(), summary)
        }
    }

    /**
     * 拉到的考试表写回缓存：首页考试格、倒计时横幅、屁岱气泡都读缓存，不写的话老师改了考场，
     * 没进日程页的人看到的一直是旧的。
     *
     * 快到的考试只提醒一次：游标记「课程名+日期」，改期算新的一场，会再提醒。
     */
    private suspend fun checkUpcomingExam(context: Context, api: JwxtScheduleApi, term: String) {
        val exams = runCatching { api.examSchedule(term) }.getOrElse {
            Log.w(TAG, "考试表拉取失败：${it.message}")
            return
        }
        val cache = com.xjtu.toolbox.data.DataCache(context)
        val cached = ScheduleCache.readExams(cache, term)
        // 空表不覆盖已有的：教务偶尔返回空表，别把还没考的场次清掉
        if (exams.isNotEmpty() || cached.isNullOrEmpty()) ScheduleCache.writeExams(cache, term, exams)
        val next = ExamCountdown.next(exams) ?: return
        if (next.daysLeft > EXAM_AHEAD_DAYS) return

        val id = "${next.exam.courseName}|${next.exam.examDate}"
        if (ReminderStore.hasSeen(context, ReminderKind.SCHEDULE, id)) return

        val detail = listOfNotNull(
            next.exam.examDate.takeIf { it.isNotBlank() },
            next.exam.examTime.takeIf { it.isNotBlank() },
            next.exam.location.takeIf { it.isNotBlank() },
            next.exam.seatNumber.takeIf { it.isNotBlank() }?.let { "座位 $it" },
        ).joinToString(" · ")
        if (ReminderNotifier.notifyExam(context, next.exam.courseName, next.label, detail)) {
            ReminderStore.markSeen(context, ReminderKind.SCHEDULE, listOf(id))
        }
    }
}

object ScheduleWatchScheduler {
    private const val UNIQUE = "schedule_watch"

    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!ReminderStore.isEnabled(app, ReminderKind.SCHEDULE) || !HeadlessSessions.hasAccount()) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }

        val request = PeriodicWorkRequestBuilder<ScheduleWatchWorker>(
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
