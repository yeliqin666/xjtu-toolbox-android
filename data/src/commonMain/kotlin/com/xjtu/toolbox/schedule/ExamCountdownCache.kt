package com.xjtu.toolbox.schedule

/**
 * 从本地缓存读「下一场考试」，不发任何请求。
 *
 * 日程页每次加载都会把考试表写进 `exams_<学期>`，所以这里总能拿到最近一次的结果。
 * 屁岱的倒计时气泡与首页的今日栏走这条路：为了提醒单独去拉一次教务是不值得的，
 * 而且那会变成一次后台登录。缓存是空的（还没进过日程页）就不提醒，可以接受。
 *
 * 从 `:app` 的 `ExamCountdown.fromCache(ctx)` 搬到了 `:data`：它要读 `DataCache`，
 * 而 `:core` 不认识那个类型。参数换成 [ScheduleCacheStore] 之后，Android 传 `DataCache`、
 * 桌面端传键值文件那一份，读数逐字节相同（读的还是 `schedule_current_term` 与 `exams_<学期>`）。
 *
 * 只算**本学期**的考试：不能读「上次看的学期」，翻了一眼去年的课表倒计时就会拿去年的算。
 */
fun cachedNextExam(cache: ScheduleCacheStore): ExamCountdown.Next? = try {
    ScheduleCache.readCurrentTerm(cache)?.let { term ->
        ScheduleCache.readExams(cache, term)?.let { exams -> ExamCountdown.next(exams) }
    }
} catch (_: Exception) {
    null
}
