package com.xjtu.toolbox.desktop.notify

/**
 * 桌面的提醒**送达**层：只做「把已经判出来的那句话送到系统通知」，自己不判任何东西。
 *
 * ## 评测在哪儿（读清楚才敢接）
 * 判「该不该提醒你」的代码分两种去处：课表变更那份在**共享层**（桌面与 Android 跑同一份），
 * 其余几类在 `:app` 的 WorkManager 里（所以桌面根本没有）：
 *
 * | 提醒 | 评测 | 桌面有没有 |
 * |---|---|---|
 * | 课表变更（调课 / 停课 / 换教室） | `ScheduleDiff.diffAndStore` + `summarize`，在 `feature/schedule` 的 `ScheduleViewModel.paintCourses` 里判完，判出东西才回调 `ScheduleHost.postScheduleChange` | **有**（同一份共享代码，桌面这一端就在跑） |
 * | 通知公告有新的一条 | `:app` 的 `NoticeWatchWorker`：`ReminderStore`（`reminder_watch` 那份 SharedPreferences）存开关与游标，WorkManager 4h 排程 | 没有 |
 * | 作业截止（48h 内） | `:app` 的 `LmsDeadlineWorker` + `LmsDueCollector`，同样挂 WorkManager | 没有 |
 * | 图书馆座位 / 考试临近 | `:app` 的 `LibraryReminderWorker` / `ScheduleWatchWorker`（`ExamCountdown`，提前 3 天） | 没有 |
 * | 后台排程载体本身 | Android 的 `WorkManager` | 没有（见 `docs/desktop-port-plan.md` 的 D6：后台能力只放 serve 模式） |
 *
 * 所以这一轮只接**课程变更**这一类 —— 它是唯一「评测结果在桌面上真的算得出来」的那一类。
 * 其余几类的评测代码属于 Android 源集（`ReminderStore` 是 Android 的 SharedPreferences），
 * 桌面既没有那份代码也没有排程载体；搬过来就是新写一遍评测 + 新造一个排程，那是另一件事，
 * 不在 B-7 这条线里。
 *
 * ## 为什么课表变更接在 `postScheduleChange` 上
 * 那个回调是共享日程页**判出变更之后**的唯一出口（见 `ScheduleHost.postScheduleChange`），
 * 桌面这一端本来就用它往消息收纳里塞一条（`InboxStore`）。通知就挂在同一格：
 * 收纳那条照旧写，能弹系统通知就再弹一条。两条路都留着是有意的 —— 托盘装不上时
 * `notify` 返回 `false`，收纳里那条还在，用户进屏仍看得到。
 *
 * `onScheduleSynced` 那一格**不放**通知：它只是「课表落地了，首页重读缓存」，落地不等于有变更
 * （每次进日程页都会走到它），在那儿弹就成了「每开一次日程页弹一次」。
 */
internal object DesktopReminders {

    /** 课表有变更（[message] 是共享层 `ScheduleDiff.summarize` 那句人话）。 */
    fun scheduleChanged(message: String): Boolean =
        DesktopSystemTray.notify("课表有变动", message)
}
