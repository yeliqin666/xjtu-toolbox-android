package com.xjtu.toolbox.inbox

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xjtu.toolbox.notification.ReminderKind
import com.xjtu.toolbox.notification.ReminderNotifier
import com.xjtu.toolbox.notification.ReminderStore
import java.util.concurrent.TimeUnit

/**
 * 自建待办的截止提醒。
 *
 * 提醒时刻由 [SelfTodoRules.reminderAt] 推算：截止前 48 小时，撞上 0–7 点就顺延到当天 7 点；
 * 建得太晚（算出来的时刻已经过去）就立刻补一条。一条待办一个 WorkManager 唯一任务，排到那个
 * 时刻再跑，WorkManager 自己负责跨重启、跨灭屏保留。
 *
 * 精度是「几分钟内」，不是闹钟：Doze 下系统会把它并到下一个维护窗口。要准点到秒就得申请
 * 精确闹钟权限，待办提醒不值得为此多弹一个权限。
 *
 * **只提醒一次**：推过的记进 [ReminderStore] 的游标（[ReminderKind.SELF_TODO]，这个类别只有
 * 游标、没有开关），之后再改这条待办（换颜色、改备注）也不会重排第二条。
 *
 * 不登录任何学校系统，纯本地，所以不受后台提醒那几个开关约束；系统通知权限没给就不发
 * （[ReminderNotifier.canPost]）。
 */
object SelfTodoReminders {
    private const val TAG = "SelfTodoRemind"
    private const val KEY_ID = "id"
    private const val KEY_ACCOUNT = "account"
    private const val HAS_ACCOUNT = "has_account"

    private fun uniqueName(todoId: String) = "self_todo_remind_$todoId"

    /** 存储变化后对一遍：还要提醒的重新排期（REPLACE，幂等），办完、删掉、已提醒过的取消。 */
    internal fun sync(context: Context, account: String?, before: List<SelfTodo>, after: List<SelfTodo>) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val keep = after.mapTo(HashSet()) { it.id }
        before.filter { it.id !in keep }.forEach { cancel(app, it.id) }
        after.forEach { todo ->
            // 推过的那条不再排：reminderAt 对「该提醒的时刻已过」的待办会返回「此刻」，
            // 不拦住的话改个颜色就会再推一遍
            val at = if (ReminderStore.hasSeen(app, ReminderKind.SELF_TODO, todo.id)) null
            else SelfTodoRules.reminderAt(todo, now)
            if (at != null) schedule(app, account, todo.id, at, now) else cancel(app, todo.id)
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

        // 重要程度是用户对「收纳里那条」的标注，标过的在通知标题前带一句
        val level = InboxRules.importanceOf(InboxStore.load(account), SelfTodoRules.inboxId(todo.id))
        val prefix = InboxRules.importanceLabel(level)?.let { "$it · " }.orEmpty()
        val posted = ReminderNotifier.notifyTodo(
            context = app,
            todoId = todo.id,
            title = prefix + todo.title,
            detail = SelfTodoRules.dueText(todo)?.let { "截止 $it" } ?: "快截止了",
        )
        // 没发出去（通知权限没给）就不记游标：用户开了权限之后还该收到这一条
        if (posted) ReminderStore.markSeen(app, ReminderKind.SELF_TODO, listOf(todo.id))
        return Result.success()
    }
}
