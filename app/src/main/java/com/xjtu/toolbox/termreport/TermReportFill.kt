package com.xjtu.toolbox.termreport

import android.content.Context
import android.util.Log
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.attendance.AttendanceApi
import com.xjtu.toolbox.attendance.AttendanceRecordStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.LoginType
import com.xjtu.toolbox.card.AppCampusCardSource
import com.xjtu.toolbox.card.Transaction
import com.xjtu.toolbox.card.allTransactions
import com.xjtu.toolbox.card.appCampusCardStore
import com.xjtu.toolbox.data.DataCache
import com.xjtu.toolbox.error.FriendlyError
import com.xjtu.toolbox.fitness.FitnessApi
import com.xjtu.toolbox.fitness.hasResults
import com.xjtu.toolbox.fitness.orderedFitnessYears
import com.xjtu.toolbox.home.HomeStats
import com.xjtu.toolbox.home.fitnessHomeStat
import com.xjtu.toolbox.jwapp.CjcxApi
import com.xjtu.toolbox.jwapp.JwappApi
import com.xjtu.toolbox.jwapp.appJwappScoreCache
import com.xjtu.toolbox.jwapp.withPreciseScores
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.notification.HeadlessSessions
import com.xjtu.toolbox.schedule.JwxtScheduleApi
import com.xjtu.toolbox.schedule.ScheduleCache
import com.xjtu.toolbox.schedule.ScheduleSourceRouter
import com.xjtu.toolbox.util.CampusTimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

private const val TAG = "TermReportFill"

/** [UNAVAILABLE]：学校那边就没有这块（如新考勤系统里没有这个学期），记下来以后不再拉、也不提示去加载。 */
internal enum class FillState { WAITING, RUNNING, DONE, SKIPPED, UNAVAILABLE, FAILED }

private val unavailableStore get() = com.xjtu.toolbox.platform.keyValueStore("term_report_unavailable")
private fun unavailableKey(account: String?, code: String, key: String) = "${account.orEmpty()}|$code|$key"

/** 这学期确认拿不到的几块（按 [TermDataFill] 的 key）。 */
internal fun unavailableBlocks(account: String?, code: String): Set<String> =
    TermDataFill.KEYS.filterTo(HashSet()) { unavailableStore.getString(unavailableKey(account, code, it)) != null }

/** 总结里「还没读到 X」的 X → 补齐步骤的 key。 */
internal val MISSING_LABEL_KEYS = mapOf(
    "课表" to TermDataFill.SCHEDULE, "考勤" to TermDataFill.ATTENDANCE, "成绩" to TermDataFill.GRADES,
    "校园卡" to TermDataFill.CARD, "体测" to TermDataFill.FITNESS,
)

internal data class FillStep(val key: String, val label: String, val state: FillState = FillState.WAITING, val note: String = "")

/** 补到的整学期校园卡流水单独存一份：不动校园卡页那份快照（它还管首页余额和今日消费）。 */
private fun cardKey(code: String) = "term_report_card_$code"

/** 读补齐时存下的整学期流水；没补过就是 null。 */
internal fun termCardTransactions(context: Context, account: String?, code: String): List<Transaction>? =
    runCatching { DataCache(context, account).readStale<List<Transaction>>(cardKey(code)) }.getOrNull()

/**
 * 学期总结生成前，把本机不全的几块用 App 现成的取数接口补上：课表、考勤（整学期）、成绩、
 * 校园卡流水（整学期）、体测。[plan] 只看本地缓存、不联网；[run] 逐项去拉，每项单独超时、
 * 单独失败，拉不到的那块照旧用本机已有的（或在总结里提示去对应页面加载）。
 */
internal class TermDataFill(private val context: Context, private val term: KnownTerm) {
    private val account = AccountContext.activeAccountId
    private val cache = DataCache(context, account)
    /** 学期最后一天结束的时刻：整学期考勤要在这之后全量扫过才算全。 */
    private val termOverMillis = term.endDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(CampusTimeZone).toEpochMilliseconds()

    private val postgraduate: Boolean
        get() = runCatching { AccountStore().activeAccount()?.accountType == AccountType.POSTGRADUATE }
            .getOrDefault(AccountContext.activeAccountType == AccountType.POSTGRADUATE)

    /** 哪几块需要补。只读本地，很快。 */
    suspend fun plan(): List<FillStep> = withContext(Dispatchers.IO) {
        val unavailable = unavailableBlocks(account, term.code)
        buildList {
            if (ScheduleCache.readCourses(cache, term.code).isNullOrEmpty()) add(FillStep(SCHEDULE, "课表"))
            val shard = AttendanceRecordStore.load(postgraduate, term.code, account)
            if (shard == null || shard.fullScanAt < termOverMillis) add(FillStep(ATTENDANCE, "考勤记录"))
            val gradeAge = cache.ageMs(GRADE_KEY)
            if (gradeAge == null || gradeAge > GRADE_TTL_MS) add(FillStep(GRADES, "成绩"))
            if (!cardCovered()) add(FillStep(CARD, "校园卡流水"))
            if (HomeStats.pushed(context, AppRoute.Fitness) == null) add(FillStep(FITNESS, "体测"))
        }.filter { it.key !in unavailable }
    }

    private suspend fun cardCovered(): Boolean {
        if (termCardTransactions(context, account, term.code) != null) return true
        val snap = runCatching { appCampusCardStore(context).load(account) }.getOrNull() ?: return false
        return snap.transactionsComplete && snap.rangeStart.isNotBlank() &&
            snap.rangeStart <= term.start && snap.rangeEnd >= term.end
    }

    /** 拉一项；返回完成后的状态说明。异常照常抛，由 [runStep] 统一兜。 */
    private suspend fun fetch(key: String): Pair<FillState, String> = withContext(Dispatchers.IO) {
        when (key) {
            SCHEDULE -> {
                val site = HeadlessSessions.site(LoginType.JWXT) ?: return@withContext notLoggedIn()
                ScheduleSourceRouter.ensureCached(cache, JwxtScheduleApi(site), HeadlessSessions.manager(), term.code)
                val n = ScheduleCache.readCourses(cache, term.code)?.size ?: 0
                if (n > 0) FillState.DONE to "$n 条课程" else FillState.UNAVAILABLE to "教务里这学期没有课"
            }
            ATTENDANCE -> {
                val site = HeadlessSessions.site(LoginType.ATTENDANCE) ?: return@withContext notLoggedIn()
                val api = AttendanceApi(site)
                val t = api.getTermList().firstOrNull { it.code == term.code }
                    ?: return@withContext FillState.UNAVAILABLE to "考勤系统里没有这个学期"
                val records = api.getWaterRecords(t.bh, t.startDate.ifBlank { term.start }, t.endDate.ifBlank { term.end })
                val pg = postgraduate
                AttendanceRecordStore.save(
                    pg,
                    AttendanceRecordStore.mergedShard(
                        termCode = term.code,
                        fresh = records,
                        cached = AttendanceRecordStore.load(pg, term.code, account),
                        fullScan = true,
                        now = System.currentTimeMillis(),
                    ),
                    account,
                )
                FillState.DONE to "${records.size} 条"
            }
            GRADES -> {
                val site = HeadlessSessions.site(LoginType.JWAPP) ?: return@withContext notLoggedIn()
                // 和成绩页一样换上 xscjcx 的精确总评（等级制也有具体分），否则会把成绩页存的精确分覆盖掉
                val precise = runCatching { HeadlessSessions.site(LoginType.JWXT)?.let { CjcxApi(it).getAllScores() } }
                    .onFailure { if (it is CancellationException) throw it }.getOrNull()
                val terms = JwappApi(site).getGrade().withPreciseScores(precise)
                if (terms.isEmpty()) return@withContext FillState.SKIPPED to "教务没有返回成绩"
                appJwappScoreCache(context, account).write(terms)
                val n = terms.firstOrNull { it.termCode == term.code }?.scoreList?.size ?: 0
                if (n > 0) FillState.DONE to "这学期 $n 门" else FillState.SKIPPED to "这学期成绩还没出"
            }
            CARD -> {
                val site = HeadlessSessions.site(LoginType.CAMPUS_CARD) ?: return@withContext notLoggedIn()
                val txs = AppCampusCardSource(site).allTransactions(term.startDate, term.endDate, maxPages = 400, allowIncomplete = false)
                cache.write(cardKey(term.code), txs)
                FillState.DONE to "${txs.size} 笔"
            }
            FITNESS -> {
                val site = HeadlessSessions.site(LoginType.FITNESS) ?: return@withContext notLoggedIn()
                val api = FitnessApi(site)
                val picked = orderedFitnessYears(api.years()).take(3).firstNotNullOfOrNull { y ->
                    runCatching { api.score(y.yearNum) }.getOrNull()?.takeIf { it.hasResults() }?.let { y.name to it }
                } ?: return@withContext FillState.UNAVAILABLE to "没有体测成绩"
                val stat = fitnessHomeStat(picked.first, picked.second)
                HomeStats.push(context, AppRoute.Fitness, stat.value, stat.detail, account, stat.lines)
                HomeStats.markFetched(context, AppRoute.Fitness, account)
                FillState.DONE to picked.first
            }
            else -> FillState.SKIPPED to ""
        }
    }

    private fun notLoggedIn() = FillState.SKIPPED to "没登录或需要验证，先用本机已有的"

    /**
     * 跑一项，结果一定落到 DONE / SKIPPED / FAILED 之一：超时、网络错、接口改版都不往外抛，
     * 只有整体被取消（用户点了跳过、离开页面）才抛 [CancellationException]。
     */
    suspend fun runStep(step: FillStep): FillStep = try {
        val (state, note) = withTimeout(if (step.key == CARD) CARD_TIMEOUT_MS else STEP_TIMEOUT_MS) { fetch(step.key) }
        if (state == FillState.UNAVAILABLE) unavailableStore.putString(unavailableKey(account, term.code, step.key), note)
        step.copy(state = state, note = note)
    } catch (e: TimeoutCancellationException) {
        step.copy(state = FillState.FAILED, note = "超时了，先用本机已有的")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "${step.key} 补齐失败：${e.javaClass.simpleName} ${e.message}")
        step.copy(state = FillState.FAILED, note = FriendlyError.of(e, "加载${step.label}"))
    }

    companion object {
        const val SCHEDULE = "schedule"
        const val ATTENDANCE = "attendance"
        const val GRADES = "grades"
        const val CARD = "card"
        const val FITNESS = "fitness"
        val KEYS = listOf(SCHEDULE, ATTENDANCE, GRADES, CARD, FITNESS)
        /** 成绩缓存文件名，和 appJwappScoreCache 同一个。 */
        private const val GRADE_KEY = "score_all_terms"
        /** 成绩出得晚：缓存超过半天就重拉一次。 */
        private const val GRADE_TTL_MS = 12L * 60 * 60 * 1000
        private const val STEP_TIMEOUT_MS = 45_000L
        /** 整学期流水可能上百页。 */
        private const val CARD_TIMEOUT_MS = 120_000L
    }
}

/** 学期码 → 中文名：「2025-2026-2」→「2025-2026学年第二学期」。 */
internal fun termNameOf(code: String): String {
    val parts = code.split('-')
    if (parts.size != 3) return code
    val n = when (parts[2]) { "1" -> "一"; "2" -> "二"; "3" -> "三"; else -> parts[2] }
    return "${parts[0]}-${parts[1]}学年第${n}学期"
}

/**
 * 按学期码找学期：先看校历 / 本机记过的，没有就问教务要开学日和周数。调试版指定学期时用。
 */
internal suspend fun resolveTerm(code: String): KnownTerm? {
    knownTerms().firstOrNull { it.code == code }?.let { return it }
    return runCatching {
        withContext(Dispatchers.IO) {
            val site = HeadlessSessions.site(LoginType.JWXT) ?: return@withContext null
            val api = JwxtScheduleApi(site)
            val start: LocalDate = api.termStartDate(code)
            val weeks = api.termWeeksOf(code) ?: 18
            KnownTerm(code, termNameOf(code), start.toString(), start.plus(weeks * 7 - 1, DateTimeUnit.DAY).toString(), weeks)
        }
    }.getOrNull()
}
