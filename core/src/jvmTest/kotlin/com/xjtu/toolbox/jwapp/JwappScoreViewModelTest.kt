package com.xjtu.toolbox.jwapp

import com.xjtu.toolbox.error.SessionExpiredFailure
import com.xjtu.toolbox.score.ReportedGrade
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 成绩屏**编排**（[JwappScoreViewModel]）在 JVM 上的口径 —— 第 19 条真数据路由搬屏时钉住的那一半。
 *
 * ## 它钉的是什么
 *
 * 屏与 VM 搬进 `:core` 时，取数从 `JwappApi(site)` / `CjcxApi(jwxtSite)` / `JudgeApi` /
 * `ScoreReportApi` 换成了两个端口（[JwappScoreSource] + [JwxtScoreSupplement]，IO 由实现方自己包），
 * 缓存换成 [JwappScoreCache]，会话失效从 `AuthExpiredException` 换成了 [SessionExpiredFailure]
 * 标记 —— **编排一行未改**：缓存先秒显 → jwapp 粗分 → 教务精确总评替换（按学期 + 课名，退而求其次
 * 按课程号）→ 通核/通选分组 → 未评教的课从报表补上 → 出错时「有缓存就提示、没缓存就报错」，
 * 以及「重试先强制重登一枪」。
 *
 * 这些行为各有它的消费方：替换决定屏上显示的是 86 还是 88，报表补充决定「未评教的课也能看到分数」，
 * 缓存决定断网时屏上有没有东西 —— 所以它们是行为，不是实现细节。
 *
 * ## 为什么在 jvmTest
 *
 * `viewModelScope` 要一个 Main 调度器，`Dispatchers.setMain` 只在 JVM 侧稳定（与
 * `AttendanceViewModelTest` / `TranscriptViewModelTest` 同一条理由）。VM 内部会
 * `withContext(Dispatchers.IO)`（实现方那一层），所以断言前统一用 [awaitIdle] 等它落地 ——
 * 不靠「跑得够快刚好过」。
 */
class JwappScoreViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 样本（编造值；课名与夹具那门课同一个，方便对着看）──

    private val coarseGrade = TermScore(
        termCode = TERM,
        termName = "2026-2027学年 第一学期",
        scoreList = listOf(
            ScoreItem(id = "SCORE-1", termCode = TERM, courseName = COURSE_NAME, score = "86", scoreValue = 86.0),
        ),
    )

    private val precise = listOf(
        CjcxScore(
            courseName = COURSE_NAME,
            termCode = TERM,
            zcj = 88.0,
            xfjd = 3.9,
            xf = 5.0,
            djcjlxdm = "",
            djcjmc = null,
            pscj = null,
            pscjxs = null,
            qmcj = null,
            qmcjxs = null,
            kch = "MATH1001",
            jxbid = "JXB-9101",
            passFlag = true,
            examProp = "必修",
            kclbdm = "必修",
        ),
    )

    @Test
    fun `打开即加载：缓存先秒显，随后 jwapp 的粗分被教务的精确总评替换掉`() {
        val source = FakeJwappScoreSource(grades = listOf(coarseGrade))
        val jwxt = FakeJwxtScoreSupplement(precise = precise)
        val cache = FakeJwappScoreCache(stored = listOf(coarseGrade))
        val vm = JwappScoreViewModel(source, jwxt, STUDENT_ID, cache)

        assertTrue(awaitIdle(vm), "加载应当在超时前落地")
        // 教务的精确总评/绩点覆盖了 jwapp 那份粗分（按「学期 + 课名」对上）
        val item = vm.allTermScores.single().scoreList.single()
        assertEquals(88.0, item.scoreValue)
        assertEquals(3.9, item.gpa)
        assertEquals("MATH1001", item.courseCode, "课程号也照教务那份补上")
        // 缓存：先读过一次（TTL 是「有数据源」那一档），拉完再写回被替换过的那一份
        assertEquals(1, cache.readCalls)
        assertEquals(JwappScoreCache.DEFAULT_TTL_MS, cache.lastReadMaxAgeMs)
        val written = assertNotNull(cache.written)
        assertEquals(88.0, written.single().scoreList.single().scoreValue)
        assertNull(vm.errorMessage)
        // 两台上游都真拉过
        assertEquals(1, source.gradeCalls)
        assertEquals(1, jwxt.preciseCalls)
        assertNull(source.lastTermCode, "整表拉取就是 termCode = null（搬迁前的 body 也是 *）")
    }

    @Test
    fun `未评教的课：从成绩报表补进对应学期，并给出「补充了 N 门」的提示`() {
        val source = FakeJwappScoreSource(grades = listOf(coarseGrade))
        val jwxt = FakeJwxtScoreSupplement(
            precise = emptyList(),
            unevaluated = setOf("大学物理"),
            reported = listOf(ReportedGrade(courseName = "大学物理", coursePoint = 4.0, score = "91", gpa = 4.1, term = TERM)),
        )
        val vm = JwappScoreViewModel(source, jwxt, STUDENT_ID)

        assertTrue(awaitIdle(vm), "加载应当在超时前落地")
        val term = vm.allTermScores.single()
        assertEquals(2, term.scoreList.size, "原来那门 + 报表补的那门")
        val fromReport = term.scoreList.single { it.courseName == "大学物理" }
        assertEquals(ScoreSource.REPORT, fromReport.source)
        assertEquals("91", fromReport.score)
        assertEquals(setOf("大学物理"), vm.unevaluatedCourses)
        assertEquals("已从报表补充 1 门未评教课程成绩", vm.reportHint)
        assertEquals(STUDENT_ID, jwxt.lastReportedStudentId)
    }

    @Test
    fun `教务那一站拿不到时不早退失败：只出 jwapp 那一份（与搬迁前的 null 早退同义）`() {
        val source = FakeJwappScoreSource(grades = listOf(coarseGrade))
        val vm = JwappScoreViewModel(source, jwxt = null, studentId = STUDENT_ID)

        assertTrue(awaitIdle(vm), "加载应当在超时前落地")
        val item = vm.allTermScores.single().scoreList.single()
        assertEquals(86.0, item.scoreValue, "没有教务那一份时显示 jwapp 的粗分")
        assertNull(vm.errorMessage)
        assertTrue(vm.unevaluatedCourses.isEmpty())
    }

    @Test
    fun `会话失效：有缓存时只提示「同步暂不可用」，没缓存时落成错误文案`() {
        // 有缓存 + jwapp 挂了 ⇒ 缓存照显示，只发一句提示（不弹回主页）
        val withCache = JwappScoreViewModel(
            FakeJwappScoreSource(failure = FakeSessionExpired()),
            jwxt = null,
            studentId = STUDENT_ID,
            cache = FakeJwappScoreCache(stored = listOf(coarseGrade)),
        )
        assertTrue(awaitIdle(withCache), "加载应当在超时前落地")
        assertEquals(86.0, withCache.allTermScores.single().scoreList.single().scoreValue)
        assertNull(withCache.errorMessage, "有缓存时不落成错误页")
        val message = runCatching { kotlinx.coroutines.runBlocking { withTimeout1(withCache) } }.getOrNull()
        assertTrue(message == "成绩同步暂不可用，显示缓存数据。下拉刷新可重试", "实际提示：$message")

        // 没缓存 + jwapp 挂了 ⇒ 错误文案（屏上那张错误页）
        val noCache = JwappScoreViewModel(
            FakeJwappScoreSource(failure = FakeSessionExpired()),
            jwxt = null,
            studentId = STUDENT_ID,
        )
        assertTrue(awaitIdle(noCache), "加载应当在超时前落地")
        assertTrue(noCache.allTermScores.isEmpty())
        assertNotNull(noCache.errorMessage, "没缓存时要给出错误文案")
        assertTrue(noCache.canRefresh, "有源就允许下拉刷新")
    }

    @Test
    fun `重试：先强制重登一枪，再重新拉一遍`() {
        val source = FakeJwappScoreSource(failure = FakeSessionExpired())
        val vm = JwappScoreViewModel(source, jwxt = null, studentId = STUDENT_ID)
        assertTrue(awaitIdle(vm), "第一次加载应当在超时前落地")
        assertEquals(1, source.gradeCalls)

        // 重登之后假上游「好了」：第二次应当成功
        source.failure = null
        vm.retry()
        assertTrue(awaitIdle(vm), "重试应当在超时前落地")
        assertEquals(1, source.reauthCalls, "重试要先强制重登一次")
        assertEquals(2, source.gradeCalls, "重登之后再拉一遍")
        assertNull(vm.errorMessage)
    }

    // ── 测试内的等待：VM 里那一层 withContext(Dispatchers.IO) 落地 ──

    private fun awaitIdle(vm: JwappScoreViewModel, timeoutMs: Long = 3_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!vm.isLoading && !vm.isRefreshing) return true
            Thread.sleep(5)
        }
        return false
    }

    private suspend fun withTimeout1(vm: JwappScoreViewModel): String? =
        kotlinx.coroutines.withTimeoutOrNull(1_000L) { vm.messages.first() }?.text

    private class FakeJwappScoreSource(
        var grades: List<TermScore> = emptyList(),
        var failure: Throwable? = null,
    ) : JwappScoreSource {
        var gradeCalls = 0
        var reauthCalls = 0
        var lastTermCode: String? = null

        override suspend fun getGrade(termCode: String?): List<TermScore> {
            gradeCalls++
            lastTermCode = termCode
            failure?.let { throw it }
            return grades
        }

        override suspend fun getScoreDetail(courseId: String): ScoreDetail =
            error("这个用例不拉分项成绩（id=$courseId）")

        override suspend fun reAuthenticate() {
            reauthCalls++
        }
    }

    private class FakeJwxtScoreSupplement(
        var precise: List<CjcxScore> = emptyList(),
        var unevaluated: Set<String> = emptySet(),
        var reported: List<ReportedGrade> = emptyList(),
    ) : JwxtScoreSupplement {
        var preciseCalls = 0
        var lastReportedStudentId: String? = null

        override suspend fun preciseScores(): List<CjcxScore> {
            preciseCalls++
            return precise
        }

        override suspend fun unevaluatedCourseNames(): Set<String> = unevaluated

        override suspend fun reportedGrades(studentId: String): List<ReportedGrade> {
            lastReportedStudentId = studentId
            return reported
        }
    }

    private class FakeJwappScoreCache(var stored: List<TermScore>? = null) : JwappScoreCache {
        var readCalls = 0
        var lastReadMaxAgeMs: Long = -1
        var written: List<TermScore>? = null

        override suspend fun read(maxAgeMs: Long): List<TermScore>? {
            readCalls++
            lastReadMaxAgeMs = maxAgeMs
            return stored
        }

        override suspend fun write(scores: List<TermScore>) {
            written = scores
        }
    }

    private class FakeSessionExpired : RuntimeException("登录态已失效"), SessionExpiredFailure

    private companion object {
        const val TERM = "2026-2027-1"
        const val COURSE_NAME = "高等数学（上）"
        const val STUDENT_ID = "2021000001"
    }
}
