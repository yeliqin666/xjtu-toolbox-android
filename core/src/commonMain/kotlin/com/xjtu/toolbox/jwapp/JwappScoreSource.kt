package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.score.ReportedGrade

/**
 * 移动教务（`jwapp.xjtu.edu.cn`）的**取数端口**：屏与 ViewModel 都在 `:core`，两端各自填实现。
 *
 * 方法就是搬迁前 `JwappApi`（`:app`，okhttp 抓 jwapp）里那三个动作的并集 —— 名称、参数、返回
 * 形状与搬迁前逐字相同，所以「jwapp 学期成绩为底 → 教务精确成绩替换 → 报表补未评教课程」
 * 的编排（[JwappScoreViewModel]）在两端是同一条代码路径。
 *
 * | 端 | 实现 | 备注 |
 * |---|---|---|
 * | Android / 桌面 | `:data` 的 `AppJwappScoreSource`（包住 `JwappApi`） | 走自己的站点会话（`jwapp`） |
 * | Web | 还没有 | 屏搬好了，取数要等 campus-api 或 `:server` 的 `/api` 端点给出（如实记在 `:web` 的未搬清单里） |
 *
 * ## 为什么 IO 调度不在端口上
 *
 * `:data` 那一份是阻塞式 okhttp，实现里自己 `withContext(Dispatchers.IO)`（搬迁前是 VM 里
 * `withContext(Dispatchers.IO)`，同一层同一个调度器）。VM 只在自己的可取消作用域里调用
 *（与 `AttendanceSource` / `CouponSource` 同一条约定）。
 *
 * ## 会话失效
 *
 * 三个方法都可能抛 `SessionExpiredFailure`（`:app`/`:data` 的 `AuthExpiredException` 是它的
 * 实现）—— VM 据此给「成绩同步暂不可用，显示缓存数据」那句提示，与搬迁前逐字相同。
 */
interface JwappScoreSource {

    /**
     * 本端能不能拿「一门课的分项成绩」（[getScoreDetail]）。
     *
     * serve 形态拿不到（`docs/api-contract.md` §5.6：那一条端点刻意不端）⇒ 报 `false`，
     * 屏上点开一门课**不再展开**那一段 —— 否则展开的是一个拿不到数据的格子，
     * 只会画出「无法加载详细成绩」那句假话。默认 `true`（Android / 桌面的行为一行未变）。
     */
    val canFetchScoreDetail: Boolean get() = true

    /** 全部学期的成绩（`termCode` 传 null = 所有学期，与搬迁前 `getGrade(null)` 同一个请求体）。 */
    suspend fun getGrade(termCode: String? = null): List<TermScore>

    /**
     * 一门课的分项成绩。
     *
     * 「这门课没有细则」抛 [NoScoreDetailException]（与搬迁前同一句话），其余失败抛别的异常 ——
     * VM 对这两种的处理不同（前者直接记一份空明细，后者再按 [isNoScoreDetailMessage] 判一次）。
     */
    suspend fun getScoreDetail(courseId: String): ScoreDetail

    /**
     * 错误页「重试」时先强制重登一次。
     *
     * 搬迁前这一枪写在 VM 里：`login.sessionManager?.credentials?.let { (u, p) -> site.ensureLogin(u, p, force = true, userInitiated = true) }`
     * —— 它要的那对凭据只有宿主（`:app` 的 `AppLoginState` / 桌面的 `DesktopAuth`）手里有，
     * 所以整件事收进这个端口。默认实现是什么都不做（取数自己会再试一次）。
     */
    suspend fun reAuthenticate() {}
}

/**
 * 教务（`jwxt.xjtu.edu.cn`）那半的**补充取数端口** —— 成绩屏的第二、第三条上游。
 *
 * 为什么单独一个端口而不是并进 [JwappScoreSource]：屏上的三个数据源住在**两个站点**上
 * （jwapp 一站、教务一站），搬迁前的 VM 也正是这么判的 ——
 * `val jwxt = jwxtSite ?: return@coroutineScope api.getGrade(null)`：教务会话拿不到就只出
 * jwapp 那一份，不再并行拉精确成绩 / 未评教 / 报表。两端都注册了教务站点，所以这个端口在
 * 两端都传得出来；传 null 时那条早退分支与搬迁前逐字一致。
 *
 * 三个方法都可能失败，而**失败是被容忍的**：VM 里各自 `catch` 之后退化成 null / 空集 /
 * 原成绩（与搬迁前逐字相同）—— 所以实现只管把异常抛出来。
 */
interface JwxtScoreSupplement {

    /** `xscjcx.do` 的全部有效成绩（自动分页），按学期 + 课程名替换 jwapp 的粗分。 */
    suspend fun preciseScores(): List<CjcxScore>

    /** 未评教课程的课名集合（`JudgeApi.unfinishedQuestionnaires`）：那些课 jwapp 不给成绩。 */
    suspend fun unevaluatedCourseNames(): Set<String>

    /** 成绩报表里那几门未评教的课（学号闭在实现里，与 `scoreReportSource` 同一条口径）。 */
    suspend fun reportedGrades(studentId: String): List<ReportedGrade>
}

/**
 * 成绩的**落盘缓存端口**（先给旧值秒显、后台再刷）。
 *
 * 为什么是端口而不是直接用 `:core` 的 `keyValueStore`：`:app` 侧的原实现是 `DataCache`
 * （按账号隔离的 JSON 文件缓存、原子写入、带 TTL），桌面侧则没有按账号分文件的宿主存储。
 * 保留这个缝，App 的行为才能逐字不变（搬迁前就是这么缓存的：同一个 key、同一个 TTL）。
 * 实现方自己决定 key 与落盘位置 —— VM 只负责「读得到就先显示、拉到了就写回去」。
 *
 * 两个方法都是**挂起的**：实现方是阻塞式存储（`:app` 是 `DataCache`），搬迁前那些调用都在
 * `withContext(Dispatchers.IO)` 里，所以由实现方自己包 —— `:core` commonMain 里连
 * `Dispatchers.IO` 都不存在（wasm 没有它），VM 里那几处 `withContext` 已经拿掉。
 * 与 `lms/LmsSource.kt` 的 `LmsCacheStore` 同一条口径（那边也是「端口挂起、实现方包 IO」）。
 */
interface JwappScoreCache {

    /** 读不到（不存在 / 过期 / 解析失败）就是 null。`maxAgeMs` 由调用方按「有没有数据源」给。 */
    suspend fun read(maxAgeMs: Long): List<TermScore>?

    suspend fun write(scores: List<TermScore>)

    companion object {
        /**
         * 默认 TTL：7 天 —— 与搬迁前 `DataCache.DEFAULT_TTL_MS` 同一个值（成绩在学期内基本稳定）。
         * 刻意在这里再写一遍而不是引 `:app` 的常量：那个常量在宿主模块，共享层看不见它。
         */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
