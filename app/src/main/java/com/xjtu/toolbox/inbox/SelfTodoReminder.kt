package com.xjtu.toolbox.inbox

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.notification.ReminderNotifier
import java.util.concurrent.TimeUnit

/**
 * 自建待办的到点提醒。
 *
 * 一条待办一个 WorkManager 唯一任务，延迟到它下一次要提醒的时刻再跑；WorkManager 自己负责跨重启、跨重启机器保留。
 * 精度是「几分钟内」，不是闹钟：Doze 下系统会把它并到下一个维护窗口。要准点到秒就得申请精确闹钟权限，
 * 待办提醒不值得为此多弹一个权限。
 *
 * 不登录任何学校系统，纯本地，所以不受后台提醒那三个开关约束；系统通知权限没给就不发（[ReminderNotifier.canPost]）。
 */
object SelfTodoReminders {
    private const val TAG = "SelfTodoRemind"
    private const val KEY_ID = "id"
    private const val KEY_ACCOUNT = "account"
    private const val HAS_ACCOUNT = "has_account"

    private fun uniqueName(todoId: String) = "self_todo_remind_$todoId"

    /** 存储变化后对一遍：还要提醒的重新排期（REPLACE，幂等），办完、删掉的取消。 */
    internal fun sync(context: Context, account: String?, before: List<SelfTodo>, after: List<SelfTodo>) {
        val now = System.currentTimeMillis()
        val keep = after.mapTo(HashSet()) { it.id }
        before.filter { it.id !in keep }.forEach { cancel(context, it.id) }
        after.forEach { todo ->
            val at = SelfTodoRules.nextReminderAt(todo, now)
            if (at != null) schedule(context, account, todo.id, at, now) else cancel(context, todo.id)
        }
    }

    internal fun schedule(context: Context, account: String?, todoId: String, at: Long, now: Long = System.currentTimeMillis()) {
        val request = OneTimeWorkRequestBuilder<SelfTodoReminderWorker>()
            .setInitialDelay((at - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_ID, todoId)
                    .putBoolean(HAS_ACCOUNT, account != null)
                    .apply { account?.let { putString(KEY_ACCOUNT, it) } }
                    .build(),
            )
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(todoId), ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "排期失败：${it.message}") }
    }

    internal fun cancel(context: Context, todoId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(uniqueName(todoId)) }
    }

    internal fun accountOf(data: Data): String? =
        if (data.getBoolean(HAS_ACCOUNT, false)) data.getString(KEY_ACCOUNT) else null

    internal fun idOf(data: Data): String? = data.getString(KEY_ID)
}

class SelfTodoReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val id = SelfTodoReminders.idOf(inputData) ?: return Result.success()
        val account = SelfTodoReminders.accountOf(inputData)
        val todo = SelfTodoStore.all(account).firstOrNull { it.id == id }
        // 办完或删掉的，任务本该已经取消；没来得及取消的到这里也不发
        if (todo == null || todo.done) return Result.success()

        val allDay = todo.isAllDay
        ReminderNotifier.notifyTodo(
            context = app,
            todoId = todo.id,
            title = todo.title,
            detail = if (allDay) "今天截止" else "到点了",
        )
        // 还有后面的提醒（比如「提前一天」之后又有「到点」）就接着排；没有就到此为止
        SelfTodoRules.nextReminderAt(todo, System.currentTimeMillis())
            ?.let { SelfTodoReminders.schedule(app, account, todo.id, it) }
        return Result.success()
    }
}
