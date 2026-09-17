package com.xjtu.toolbox.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.MyBookingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "LibraryReminder"

/**
 * 到点了去问一次图书馆：那件必须做的事做了没有。
 *
 * **必须现查，不能凭 App 自己记的状态判断**：学生完全可能走到闸机前刷卡签到，
 * 这件事 App 一无所知。只有 `getMyBooking` 现在还把「入馆签到」列为可做操作，
 * 才说明真的还没签。做过了就悄悄退出，一条通知也不发。
 */
class LibraryReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ReminderStore.isEnabled(app, ReminderKind.LIBRARY)) return Result.success()

        val action = inputData.getString(KEY_ACTION) ?: return Result.success()
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)

        val booking = try {
            val site = HeadlessSessions.site(app, LoginType.LIBRARY) ?: return Result.retry()
            withContext(Dispatchers.IO) { LibraryApi(site).getMyBooking() }
        } catch (e: Exception) {
            Log.w(TAG, "查预约失败，稍后重试：${e.message}")
            return Result.retry()
        }

        val stillPending = booking?.actionUrls?.keys?.contains(action) == true
        if (!stillPending) {
            Log.d(TAG, "「$action」已不在待办里，收工")
            return Result.success()
        }

        ReminderNotifier.notifyLibrary(app, action, booking?.seatId)

        // 入馆签到有确定的 30 分钟窗口，提醒一次就够；中途返回学校没给时限，
        // 只能隔一段时间再看一眼，直到用户返座或到达复查上限。
        if (action == ACTION_RETURN && attempt < MAX_RETURN_CHECKS) {
            LibraryReminderScheduler.scheduleReturnCheck(app, attempt + 1)
        }
        return Result.success()
    }

    companion object {
        const val KEY_ACTION = "action"
        const val KEY_ATTEMPT = "attempt"
        const val ACTION_CHECK_IN = "入馆签到"
        const val ACTION_RETURN = "中途返回"

        /** 中途返回最多复查这么多轮；配合 [LibraryReminderScheduler.RETURN_INTERVAL_MINUTES] 约 4 小时封顶。 */
        const val MAX_RETURN_CHECKS = 12
    }
}

/**
 * 按当前预约状态排 / 撤图书馆提醒。
 *
 * 入口只有一个：页面每次拿到新的「我的预约」就调一次 [sync]。预约、换座、
 * 中途离开、签到完成全都会经过那里，不必在每个动作后各挂一段排程逻辑。
 */
object LibraryReminderScheduler {

    private const val UNIQUE_CHECK_IN = "library_check_in"
    private const val UNIQUE_RETURN = "library_return"

    /** 学校给 30 分钟签到，提前几分钟叫，留出走到图书馆的时间。 */
    private const val CHECK_IN_DELAY_MINUTES = 25L

    internal const val RETURN_INTERVAL_MINUTES = 20L

    fun sync(context: Context, booking: MyBookingInfo?) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!ReminderStore.isEnabled(app, ReminderKind.LIBRARY)) {
            wm.cancelUniqueWork(UNIQUE_CHECK_IN)
            wm.cancelUniqueWork(UNIQUE_RETURN)
            return
        }

        val pending = booking?.actionUrls?.keys.orEmpty()
        if (LibraryReminderWorker.ACTION_CHECK_IN in pending) {
            // KEEP 而不是 REPLACE：30 分钟是从预约那一刻起算的，页面每刷新一次就把
            // 倒计时重置一次，等于永远叫不响。
            wm.enqueueUniqueWork(
                UNIQUE_CHECK_IN,
                ExistingWorkPolicy.KEEP,
                request(LibraryReminderWorker.ACTION_CHECK_IN, 1, CHECK_IN_DELAY_MINUTES),
            )
        } else {
            wm.cancelUniqueWork(UNIQUE_CHECK_IN)
        }

        if (LibraryReminderWorker.ACTION_RETURN in pending) {
            wm.enqueueUniqueWork(
                UNIQUE_RETURN,
                ExistingWorkPolicy.KEEP,
                request(LibraryReminderWorker.ACTION_RETURN, 1, RETURN_INTERVAL_MINUTES),
            )
        } else {
            wm.cancelUniqueWork(UNIQUE_RETURN)
        }
    }

    internal fun scheduleReturnCheck(context: Context, attempt: Int) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_RETURN,
            ExistingWorkPolicy.REPLACE,
            request(LibraryReminderWorker.ACTION_RETURN, attempt, RETURN_INTERVAL_MINUTES),
        )
    }

    private fun request(action: String, attempt: Int, delayMinutes: Long) =
        OneTimeWorkRequestBuilder<LibraryReminderWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(LibraryReminderWorker.KEY_ACTION, action)
                    .putInt(LibraryReminderWorker.KEY_ATTEMPT, attempt)
                    .build()
            )
            // 只要网络，不要 batteryNotLow：座位是有硬截止时间的，
            // 低电量时推迟半小时等于这条提醒作废。
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
}
