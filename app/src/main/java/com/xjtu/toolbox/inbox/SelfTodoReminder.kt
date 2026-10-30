package com.xjtu.toolbox.inbox

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.notification.ReminderKind
import com.xjtu.toolbox.notification.ReminderNotifier
import com.xjtu.toolbox.notification.ReminderStore
import java.util.concurrent.TimeUnit

/**
 * 自建待办的截止提醒。
 *
 * 和作业截止（`LmsDeadlineWorker`）用的是同一套规则（[DueReminder]）：周期扫一遍，谁的截止落进
 * 「最后两天」的窗口、又还没提醒过，就推一条；推过的记进去重游标，不再重复。
 *
 * 以前这里是「每条待办一个 WorkManager 一次性任务，精确到截止那一刻」，改掉有两个原因：
 * 1. 和作业截止同一口径 —— 两处各写一份「提前多久」，改一处忘一处就会变成「作业提前两天、
 *    自己记的到点才响」；
 * 2. 待办的截止多半是 23:59（「当天结束」的意思），精确到那一刻提醒会落在半夜。
 *
 * 纯本地、不登录任何学校系统，所以不受后台提醒那几个开关约束；系统通知权限没给就不发
 * （[ReminderNotifier.canPost]）。扫描只是读一遍本机 JSON，代价极低，周期比作业那档更短。
 *
 * 只扫**当前账号**那份（[SelfTodoStore.open] 的默认账号）：通知点开进的是当前账号的收纳，
 * 给别的账号的待办弹一条通知会跳错地方。
 */
object SelfTodoReminders {
    private const val TAG = "SelfTodoRemind"
    private const val PERIODIC = "self_todo_scan"
    private const val SCAN_ONCE = "self_todo_scan_once"

    /** 应用启动时挂周期扫描。 */
    internal fun apply(context: Context) {
        val request = PeriodicWorkRequestBuilder<SelfTodoReminderWorker>(1, TimeUnit.HOURS, 30, TimeUnit.MINUTES).build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }.onFailure { Log.w(TAG, "周期排期失败：${it.message}") }
    }

    /**
     * 存储在改：刚建的待办可能已经落进窗口里，立刻扫一次，不必等下一个周期。
     * 扫完发现窗口是空的也无所谓 —— 那只是一次本机文件读取。
     */
    internal fun sync(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(SCAN_ONCE, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SelfTodoReminderWorker>().build())
        }.onFailure { Log.w(TAG, "立即扫描排期失败：${it.message}") }
    }
}

class SelfTodoReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val now = System.currentTimeMillis()
        val open = SelfTodoStore.open().mapNotNull { todo ->
            SelfTodoRules.dueMillis(todo).takeIf { it > 0 }?.let { todo to it }
        }
        val fresh = open.filter { (todo, at) ->
            DueReminder.inWindow(at, now) && !ReminderStore.hasSeen(app, ReminderKind.SELF_TODO, todo.id)
        }
        if (fresh.isEmpty()) return Result.success()

        // 重要程度是用户对「收纳里那条」的标注，所以从收纳那边读；标过的才在通知标题前带一句
        val data = InboxStore.load()
        fresh.forEach { (todo, at) ->
            val level = InboxRules.importanceOf(data, SelfTodoRules.inboxId(todo.id))
            val prefix = InboxRules.importanceLabel(level)?.let { "$it · " }.orEmpty()
            val posted = ReminderNotifier.notifyTodo(
                context = app,
                todoId = todo.id,
                title = prefix + todo.title,
                detail = "${DueReminder.remainingText(at, now)}截止",
            )
            // 没发出去（通知权限没给）就不记游标：用户开了权限之后还该收到这一条
            if (posted) ReminderStore.markSeen(app, ReminderKind.SELF_TODO, listOf(todo.id))
        }
        return Result.success()
    }
}
