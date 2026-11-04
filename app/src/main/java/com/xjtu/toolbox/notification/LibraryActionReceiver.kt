package com.xjtu.toolbox.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.home.HomeStatsRefresher
import com.xjtu.toolbox.library.AppLibrarySession
import com.xjtu.toolbox.library.LibraryApi
import com.xjtu.toolbox.library.LibraryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LibraryAction"

/** 通知上的「签到」按钮（#204）：后台现查预约拿最新链接再签，不打开 App。 */
class LibraryActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(LibraryReminderWorker.KEY_ACTION) ?: return
        if (!HomeStatsRefresher.isOnline(context)) {
            ReminderNotifier.notifyLibrary(context, action, null, status = "无网络，稍后重试")
            return
        }
        ReminderNotifier.notifyLibrary(context, action, null, status = "正在签到…", withAction = false)
        // REPLACE：上一次卡住时再点能重来。不用 setExpedited：API<31 上需重写 getForegroundInfo；
        // 点通知按钮只有短暂后台豁免，Doze/待机下仍可能延后，几秒的签到操作可接受。
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LibraryActionWorker>()
                .setInputData(Data.Builder().putString(LibraryReminderWorker.KEY_ACTION, action).build())
                .build(),
        )
    }

    companion object {
        const val INTENT_ACTION = "com.xjtu.toolbox.action.LIBRARY_SIGN"
        private const val UNIQUE_WORK = "library_notification_action"
    }
}

class LibraryActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private sealed interface Outcome {
        data object Done : Outcome
        data class Failed(val text: String) : Outcome
    }

    override suspend fun doWork(): Result {
        val app = applicationContext
        val action = inputData.getString(LibraryReminderWorker.KEY_ACTION) ?: return Result.success()
        // OkHttp 的阻塞读不响应协程取消（客户端无 callTimeout，重登还会多发请求），放到进程级作用域里跑：
        // 超时直接返回，残留请求受读超时兜底，结果仍会经 LibraryStatus 发布；
        // 加锁串行，REPLACE 起的新任务等旧请求结束后再现查按钮，避免重复签到。
        val job = signScope.async { signLock.withLock { signIn(action) } }
        val outcome = try {
            withTimeoutOrNull(TIMEOUT_MS) { job.await() }
                ?: Outcome.Failed("签到超时，可能已生效，请打开 App 确认")
        } catch (e: CancellationException) {
            // Worker 被系统取消时一并取消（尚在排队的不再发请求）
            job.cancel()
            throw e
        }
        when (outcome) {
            Outcome.Done -> ReminderNotifier.cancelLibrary(app)
            is Outcome.Failed -> ReminderNotifier.notifyLibrary(app, action, null, status = outcome.text)
        }
        return Result.success()
    }

    private suspend fun signIn(action: String): Outcome = try {
        val site = HeadlessSessions.site(LoginType.LIBRARY)
        if (site == null) Outcome.Failed("登录需要验证，请打开 App 签到")
        else withContext(Dispatchers.IO) {
            val api = LibraryApi(AppLibrarySession(site))
            val booking = api.fetchMyBooking().getOrThrow()
            val url = booking?.actionUrls?.get(action)
            if (url == null) {
                LibraryStatus.publish(applicationContext, booking)
                return@withContext Outcome.Done
            }
            val result = api.executeAction(url)
            val after = api.fetchMyBooking().onSuccess { LibraryStatus.publish(applicationContext, it) }
            if (result.success && after.getOrNull()?.actionUrls?.containsKey(action) != true) Outcome.Done
            else Outcome.Failed(result.message.ifBlank { "签到未生效" })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "通知签到失败", e)
        Outcome.Failed("签到失败，请稍后重试")
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val signScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val signLock = Mutex()
    }
}
