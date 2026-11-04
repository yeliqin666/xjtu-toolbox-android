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
import com.xjtu.toolbox.community.CommunityActivityKind
import com.xjtu.toolbox.community.CommunityRepo
import com.xjtu.toolbox.community.SecureGithubSession
import com.xjtu.toolbox.community.communityDelta
import com.xjtu.toolbox.inbox.InboxStore
import com.xjtu.toolbox.inbox.OwnInbox
import java.util.concurrent.TimeUnit

/**
 * 后台看社区：自己的帖子被别人回复、任意帖子里被 @，就投进消息收纳（#236）。
 *
 * 排程照搬 [NoticeWatchWorker]。只用社区登录拿到的 GitHub 令牌查一次 GraphQL（约 4 点额度）。
 * 没登录、没网、被限流都静默跳过：不退避重试、不弹通知、不建基线，等下一轮。
 */
class CommunityWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val enabled = CommunityWatchScheduler.enabledKinds(app)
        if (enabled.isEmpty()) return Result.success()
        val session = SecureGithubSession.get()
        val login = session.login.value ?: return Result.success()
        val items = session.repository.activity(CommunityRepo.OWNER, CommunityRepo.NAME, login).getOrElse {
            Log.w(TAG, "本轮跳过：${it.message}")
            return Result.success()
        }
        val delta = communityDelta(
            items, enabled,
            seen = enabled.associateWith { ReminderStore.seen(app, it.reminder) },
            baselined = CommunityActivityKind.entries.filterTo(mutableSetOf()) { ReminderStore.isBaselined(app, it.reminder) },
        )
        InboxStore.post(delta.fresh.map(OwnInbox::community))
        delta.seen.forEach { (kind, ids) -> ReminderStore.markSeen(app, kind.reminder, ids) }
        delta.baselined.forEach { ReminderStore.setBaselined(app, it.reminder) }
        return Result.success()
    }

    private companion object {
        const val TAG = "CommunityWatch"
    }
}

/** 两项社区提醒各对应一个 [ReminderKind]，开关和去重游标都存在 [ReminderStore]。 */
internal val CommunityActivityKind.reminder: ReminderKind
    get() = when (this) {
        CommunityActivityKind.REPLY -> ReminderKind.COMMUNITY_REPLY
        CommunityActivityKind.MENTION -> ReminderKind.COMMUNITY_MENTION
    }

object CommunityWatchScheduler {
    private const val UNIQUE = "community_watch"

    internal fun enabledKinds(context: Context): Set<CommunityActivityKind> =
        CommunityActivityKind.entries.filterTo(mutableSetOf()) { ReminderStore.isEnabled(context, it.reminder) }

    fun apply(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (enabledKinds(app).isEmpty()) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }

        val request = PeriodicWorkRequestBuilder<CommunityWatchWorker>(
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
