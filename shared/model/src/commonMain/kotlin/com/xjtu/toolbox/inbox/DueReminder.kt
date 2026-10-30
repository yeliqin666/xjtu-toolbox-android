package com.xjtu.toolbox.inbox

/**
 * 截止提醒的公共规则：一件「有截止的事」什么时候该推一条通知。
 *
 * 作业截止（`:app` 的 `LmsDeadlineWorker`）和自建待办（同目录的 `SelfTodoDeadlineWorker`）
 * 用的是同一套判断 —— 以前这两处各写一份「48 小时窗口 + 只推一次」，改一处忘了另一处就会
 * 出现「作业提前两天提醒、自己记的待办到点才响」。窗口、措辞要调就改这里一处。
 *
 * 三条约定：
 * 1. **进窗口才提醒**：距截止不足 [AHEAD_MS] 才算「快截止」，进了窗口就一直算（由调用方的
 *    扫描周期决定实际提醒落在窗口里的哪一刻）；
 * 2. **只提醒一次**：每件事一个稳定 id，推过就记进 `ReminderStore` 的去重游标，之后不再重复；
 * 3. **过了截止不推**：那时候提醒除了添堵没有别的用。
 */
object DueReminder {

    /** 距截止还有这么久就进入提醒窗口（约两天）。 */
    const val AHEAD_MS: Long = 48L * 60 * 60 * 1000

    /** 截止时刻 [dueMs] 在 [nowMs] 这一刻是否落在提醒窗口里（毫秒时间戳）。 */
    fun inWindow(dueMs: Long, nowMs: Long): Boolean = dueMs > nowMs && dueMs - nowMs <= AHEAD_MS

    /**
     * 剩余时间的措辞，和作业截止通知里那句保持一致：
     * 不到一小时说「不到 1 小时」，够一天说「剩 N 天 M 小时」，不够一天说「剩 N 小时」。
     */
    fun remainingText(dueMs: Long, nowMs: Long): String {
        val totalMinutes = (dueMs - nowMs) / 60_000
        if (totalMinutes < 60) return "不到 1 小时"
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        return when {
            days <= 0 -> "剩 $hours 小时"
            hours <= 0 -> "剩 $days 天"
            else -> "剩 $days 天 $hours 小时"
        }
    }
}
