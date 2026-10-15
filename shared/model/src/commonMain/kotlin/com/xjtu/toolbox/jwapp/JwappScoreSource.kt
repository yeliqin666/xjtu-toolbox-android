package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.score.ReportedGrade

/** 移动教务（`jwapp.xjtu.edu.cn`）的取数端口：屏与 ViewModel 都在 `:core`，两端各自填实现。 */
interface JwappScoreSource {

    /** 本端能不能拿「一门课的分项成绩」（[getScoreDetail]）。 */
    val canFetchScoreDetail: Boolean get() = true

    /** 全部学期的成绩（`termCode` 传 null = 所有学期）。 */
    suspend fun getGrade(termCode: String? = null): List<TermScore>

    /** 一门课的分项成绩。 */
    suspend fun getScoreDetail(courseId: String): ScoreDetail

    /** 错误页「重试」时先强制重登一次。 */
    suspend fun reAuthenticate() {}
}

/** 教务（`jwxt.xjtu.edu.cn`）那半的补充取数端口 —— 成绩屏的第二、第三条上游。 */
interface JwxtScoreSupplement {

    /** `xscjcx.do` 的全部有效成绩（自动分页），按学期 + 课程名替换 jwapp 的粗分。 */
    suspend fun preciseScores(): List<CjcxScore>

    /** 未评教课程的课名集合（`JudgeApi.unfinishedQuestionnaires`）：那些课 jwapp 不给成绩。 */
    suspend fun unevaluatedCourseNames(): Set<String>

    /** 成绩报表里那几门未评教的课。 */
    suspend fun reportedGrades(studentId: String): List<ReportedGrade>
}

/** 成绩的落盘缓存端口（先给旧值秒显、后台再刷）。 */
interface JwappScoreCache {

    /** 读不到（不存在 / 过期 / 解析失败）就是 null。`maxAgeMs` 由调用方按「有没有数据源」给。 */
    suspend fun read(maxAgeMs: Long): List<TermScore>?

    suspend fun write(scores: List<TermScore>)

    companion object {
        /** 默认 TTL：7 天（成绩在学期内基本稳定）。 */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
