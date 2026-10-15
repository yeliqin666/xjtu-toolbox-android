package com.xjtu.toolbox.schedule

/** 从本地缓存读「下一场考试」，不发任何请求。 */
fun cachedNextExam(cache: ScheduleCacheStore): ExamCountdown.Next? = try {
    ScheduleCache.readCurrentTerm(cache)?.let { term ->
        ScheduleCache.readExams(cache, term)?.let { exams -> ExamCountdown.next(exams) }
    }
} catch (_: Exception) {
    null
}
