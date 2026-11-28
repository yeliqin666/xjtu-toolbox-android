package com.xjtu.toolbox.schedule

import androidx.lifecycle.viewModelScope
import com.xjtu.toolbox.core.net.TermStartData
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleTermSwitchTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun offlineSwitchReadsOnlyTheSelectedTermsCoursesAndStartDate() = withSchedule { vm, cache ->
        val course = CourseItem(courseName = "数学", weekBits = "1")
        val start = LocalDate(2025, 9, 1)
        ScheduleCache.writeRawCourses(cache, "full", listOf(course))
        ScheduleCache.writeStartDate(cache, "full", start, 16)
        ScheduleCache.writeOptimizedCourses(cache, "coursesOnly", listOf(course.copy(courseName = "物理")))
        ScheduleCache.writeStartDate(cache, "startOnly", start)
        ScheduleCache.writeOptimizedCourses(cache, "empty", emptyList())

        vm.switchTerm("full")
        runCurrent()
        assertEquals(listOf(course), vm.courses)
        assertEquals(start, vm.startOfTerm)

        vm.switchTerm("missing")
        // 导出菜单在异步读取新学期缓存之前也不能取到上学期的数据。
        assertTrue(vm.courses.isEmpty())
        assertNull(vm.startOfTerm)
        runCurrent()
        assertTrue(vm.errorMessage.orEmpty().contains("暂无缓存"))
        assertFalse(vm.showingStaleData)

        vm.switchTerm("coursesOnly")
        runCurrent()
        assertEquals(listOf("物理"), vm.courses.map { it.courseName })
        assertNull(vm.startOfTerm)
        assertTrue(vm.showingStaleData)

        vm.switchTerm("startOnly")
        runCurrent()
        assertTrue(vm.courses.isEmpty())
        assertEquals(start, vm.startOfTerm)
        assertTrue(vm.errorMessage.orEmpty().contains("暂无缓存"))

        vm.switchTerm("full")
        vm.switchTerm("empty")
        runCurrent()
        assertEquals("empty", vm.selectedTermCode)
        assertTrue(vm.courses.isEmpty())
        assertNull(vm.startOfTerm)
        assertNull(vm.errorMessage)
        assertTrue(vm.showingStaleData)
    }

    @Test
    fun failedRequestAfterAutomaticTermChangeDoesNotFallBackToThePreviousTerm() {
        val oldCourse = CourseItem(courseName = "数学", weekBits = "1")
        var lateRefresh: CompletableDeferred<SourceSchedule>? = null
        val source = object : TermSwitchSource() {
            override suspend fun schedule(term: String?): SourceSchedule {
                if (term == "old") return lateRefresh?.await() ?: SourceSchedule(listOf(oldCourse))
                throw java.io.IOException("offline")
            }
        }
        withSchedule(source) { vm, cache ->
            ScheduleCache.writeLastTerm(cache, "old")
            ScheduleCache.writeOptimizedCourses(cache, "old", listOf(oldCourse))
            ScheduleCache.writeStartDate(cache, "old", LocalDate(2025, 9, 1))
            vm.bind()
            runCurrent()
            assertEquals("new", vm.selectedTermCode)
            assertTrue(vm.errorMessage.orEmpty().contains("暂无缓存"))
            assertFalse(vm.showingStaleData)
            assertTrue(vm.courses.isEmpty())
            assertNull(vm.startOfTerm)

            ScheduleCache.writeOptimizedCourses(cache, "empty", emptyList())
            vm.switchTerm("empty")
            runCurrent()
            assertNull(vm.errorMessage)
            assertTrue(vm.showingStaleData)

            vm.switchTerm("old")
            runCurrent()
            lateRefresh = CompletableDeferred()
            vm.refreshSchedule()
            runCurrent()
            vm.switchTerm("missing")
            runCurrent()
            lateRefresh.complete(SourceSchedule(listOf(oldCourse)))
            runCurrent()
            assertTrue(vm.errorMessage.orEmpty().contains("暂无缓存"))
            assertFalse(vm.showingStaleData)
            assertTrue(vm.courses.isEmpty())
        }
    }

    @Test
    fun termSwitchLoadsTheStartDateWhenCoursesOrExamsFallBackToCache() {
        val start = LocalDate(2025, 9, 1)
        val freshCourse = CourseItem(courseName = "数学", dayOfWeek = 1, weekBits = "1")
        val cachedExam = ExamItem(courseName = "物理考试")
        val source = object : TermSwitchSource() {
            override suspend fun schedule(term: String?) =
                if (term == "cachedOnly") throw java.io.IOException("课程接口不可用") else SourceSchedule(listOf(freshCourse))
            override suspend fun examSchedule(term: String?): List<ExamItem> =
                if (term == "old") throw java.io.IOException("考试接口不可用") else emptyList()
            override suspend fun termStart(term: String) = TermStartData(term, start.toString())
        }
        withSchedule(source) { vm, cache ->
            vm.bind()
            runCurrent()
            ScheduleCache.writeRawCourses(cache, "old", listOf(freshCourse.copy(courseName = "旧课")))
            ScheduleCache.writeExams(cache, "old", listOf(cachedExam))
            vm.switchTerm("old")
            runCurrent()

            assertEquals(start, vm.startOfTerm)
            assertEquals(start, ScheduleCache.readStartDate(cache, "old"))
            assertEquals(listOf(freshCourse), vm.courses)
            assertEquals(listOf(cachedExam), vm.exams)
            assertEquals(listOf(cachedExam), ScheduleCache.readExams(cache, "old"))
            val ics = ScheduleExport.generateIcs(vm.courses, checkNotNull(vm.startOfTerm), vm.selectedTermCode)
            assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20250901T"))

            ScheduleCache.writeExams(cache, "empty", listOf(cachedExam))
            vm.switchTerm("empty")
            runCurrent()
            assertTrue(vm.exams.isEmpty())
            assertEquals(emptyList(), ScheduleCache.readExams(cache, "empty"))

            val cachedCourse = freshCourse.copy(courseName = "缓存课")
            ScheduleCache.writeRawCourses(cache, "cachedOnly", listOf(cachedCourse))
            vm.switchTerm("cachedOnly")
            runCurrent()
            assertEquals(start, vm.startOfTerm)
            assertEquals(start, ScheduleCache.readStartDate(cache, "cachedOnly"))
            assertEquals(listOf(cachedCourse), vm.courses)
            assertTrue(vm.showingStaleData)
        }
    }

    @Test
    fun lateExamRefreshUpdatesOnlyTheOriginalTermsCache() {
        val oldExam = ExamItem(courseName = "数学", location = "教一")
        val movedExam = oldExam.copy(location = "教二")
        val newExam = ExamItem(courseName = "物理")
        var oldRefresh: CompletableDeferred<List<ExamItem>>? = null
        val source = object : TermSwitchSource() {
            override suspend fun schedule(term: String?) = SourceSchedule(listOf(CourseItem(courseName = "数学")))
            override suspend fun examSchedule(term: String?) =
                if (term == "old") oldRefresh?.await() ?: listOf(oldExam) else listOf(newExam)
        }

        withSchedule(source) { vm, cache ->
            vm.bind()
            runCurrent()
            vm.switchTerm("old")
            runCurrent()
            assertEquals(listOf(oldExam), vm.exams)

            oldRefresh = CompletableDeferred()
            vm.refreshExams()
            runCurrent()
            vm.switchTerm("new")
            runCurrent()
            assertEquals(listOf(newExam), vm.exams)

            oldRefresh.complete(listOf(movedExam))
            runCurrent()
            assertEquals(listOf(newExam), vm.exams)
            assertEquals(listOf(newExam), ScheduleCache.readExams(cache, "new"))
            assertEquals(listOf(movedExam), ScheduleCache.readExams(cache, "old"))
        }
    }

    @Test
    fun automaticTermChangeKeepsLateCourseAndExamRefreshesOffTheNewPage() {
        val oldTermLookup = CompletableDeferred<String>()
        val oldCourses = CompletableDeferred<SourceSchedule>()
        val oldExams = CompletableDeferred<List<ExamItem>>()
        var refreshing = false
        val source = object : TermSwitchSource() {
            override suspend fun currentTerm() = if (refreshing) "new" else oldTermLookup.await()
            override suspend fun schedule(term: String?): SourceSchedule =
                if (refreshing && term == "old") oldCourses.await()
                else SourceSchedule(listOf(CourseItem(courseName = term.orEmpty())))

            override suspend fun examSchedule(term: String?): List<ExamItem> =
                if (refreshing && term == "old") oldExams.await()
                else listOf(ExamItem(courseName = term.orEmpty()))
        }

        withSchedule(source) { vm, cache ->
            ScheduleCache.writeLastTerm(cache, "old")
            vm.bind()
            runCurrent()
            assertEquals("old", vm.selectedTermCode)
            refreshing = true
            vm.refreshSchedule()
            vm.refreshExams()
            runCurrent()

            oldTermLookup.complete("new")
            runCurrent()
            assertEquals("new", vm.selectedTermCode)
            val newCourse = CourseItem(courseName = "new")
            val newExam = ExamItem(courseName = "new")
            assertEquals(listOf(newCourse), vm.courses)
            assertEquals(listOf(newExam), vm.exams)

            oldCourses.complete(SourceSchedule(listOf(CourseItem(courseName = "old refresh"))))
            oldExams.complete(listOf(ExamItem(courseName = "old refresh")))
            runCurrent()
            assertEquals(listOf(newCourse) to listOf(newExam), vm.courses to vm.exams)
        }
    }

    @Test
    fun refreshFiltersHolidaysUsingTheTermStartReceivedWhileItWasRunning() {
        val start = LocalDate(2025, 9, 1)
        val startResponse = CompletableDeferred<TermStartData>()
        val refreshResponse = CompletableDeferred<SourceSchedule>()
        val course = CourseItem(courseName = "数学", dayOfWeek = 1, weekBits = "11")
        var refreshing = false
        val source = object : TermSwitchSource() {
            override suspend fun currentTerm() = "old"
            override suspend fun termStart(term: String) = startResponse.await()
            override suspend fun schedule(term: String?) =
                if (refreshing) refreshResponse.await() else SourceSchedule(listOf(course))
        }

        withSchedule(source, holidays = mapOf(start to "假期")) { vm, cache ->
            ScheduleCache.writeLastTerm(cache, "old")
            vm.bind()
            runCurrent()
            assertEquals(listOf(course), vm.courses)
            assertNull(vm.startOfTerm)
            refreshing = true
            vm.refreshSchedule()
            runCurrent()
            startResponse.complete(TermStartData("old", start.toString()))
            runCurrent()
            assertEquals(start, vm.startOfTerm)

            refreshResponse.complete(SourceSchedule(listOf(course)))
            runCurrent()
            assertEquals(listOf(course), ScheduleCache.readRawCourses(cache, "old"))
            assertEquals(listOf(course.copy(weekBits = "01")), ScheduleCache.readOptimizedCourses(cache, "old"))
        }
    }

    private abstract class TermSwitchSource : JwxtScheduleSource {
        override fun termNames() = emptyMap<String, String>()
        override fun termWeeksOf(term: String): Int? = null
        override suspend fun currentTerm() = "new"
        override suspend fun termList() = listOf("new", "old")
        override suspend fun examSchedule(term: String?) = emptyList<ExamItem>()
        override suspend fun changes(term: String) = emptyList<ScheduleChangeEvent>()
        override suspend fun termStart(term: String) = TermStartData(term, if (term == "old") "2025-09-01" else null)
        override suspend fun textbooks(studentId: String, term: String?) = emptyList<TextbookItem>()
    }

    private fun withSchedule(
        source: JwxtScheduleSource? = null,
        holidays: Map<LocalDate, String> = emptyMap(),
        check: TestScope.(ScheduleViewModel, ScheduleCacheStore) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previousRoot = keyValueStoreRootOverride
        keyValueStoreRootOverride = files.root
        Dispatchers.setMain(dispatcher)
        val cache = KeyValueScheduleCacheStore(keyValueStore("schedule"))
        val host = object : ScheduleHost {
            override val io = dispatcher
            override val honored = emptySet<ScheduleCapability>()
            override val courseLinks = null
            override val login = object : ScheduleLogin {
                override val accountId = "test"
                override val studentId = "test"
                override val hasCredentials = false
            }
            override val customCourses = KeyValueCustomCourseStore(keyValueStore("custom"))
            override val session = object : ScheduleSession {
                override val source = source
                override val isLoggedIn = source != null
                override suspend fun ensureLogin(userInitiated: Boolean) = checkNotNull(source)
                override suspend fun reAuthenticate(userInitiated: Boolean) = checkNotNull(source)
                override suspend fun fetchSchedule(source: JwxtScheduleSource, termCode: String, userInitiated: Boolean) =
                    ServedSchedule(source.schedule(termCode).courses, "jwxt")
            }
            override val holidays = object : HolidaySource {
                override fun peekCached(cache: ScheduleCacheStore) = holidays
                override suspend fun refresh(cache: ScheduleCacheStore, force: Boolean) = peekCached(cache)
            }
            override fun cache(accountId: String?): ScheduleCacheStore = cache
        }
        val vm = ScheduleViewModel(host)
        try {
            check(vm, cache)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
            keyValueStoreRootOverride = previousRoot
        }
    }
}
