package com.xjtu.toolbox.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 装完新包重画桌面小组件。
 *
 * 系统换包后把小组件重置成初始布局却不发更新，定好的刷新闹钟也随之作废：
 * 课表、校园卡要等 30 分钟一次的周期更新，通知小组件没有周期更新，会一直空到用户打开 App。
 * 桌面此时还没重连小组件服务，立刻推的内容会被它随后的重新加载盖掉（实测十几秒后），
 * 所以 30 秒后再推一次。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        refreshAll(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget_refresh_after_update",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Delayed>().setInitialDelay(30, TimeUnit.SECONDS).build(),
        )
    }

    class Delayed(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            refreshAll(applicationContext)
            return Result.success()
        }
    }

    private companion object {
        fun refreshAll(context: Context) {
            runCatching {
                ScheduleWidgetUpdater.requestUpdate(context, resetToToday = false)
                CampusCardWidgetUpdater.requestUpdate(context)
                NoticeWidgetUpdater.requestUpdate(context)
            }
        }
    }
}
