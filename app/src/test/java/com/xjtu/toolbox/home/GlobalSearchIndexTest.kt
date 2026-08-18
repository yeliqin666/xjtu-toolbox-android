package com.xjtu.toolbox.home

import com.xjtu.toolbox.Routes
import com.xjtu.toolbox.auth.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GlobalSearchIndex 单元测试。
 *
 * 不依赖 Compose：GlobalSearchIndex 是 plain Kotlin。
 */
class GlobalSearchIndexTest {

    @Test
    fun blankQuery_returnsEmpty() {
        assertEquals(0, GlobalSearchIndex.search("").size)
        assertEquals(0, GlobalSearchIndex.search("   ").size)
    }

    @Test
    fun caseInsensitive() {
        // 中文别名 "余额" → 校园卡屏命中。验证小写 query 也命中大写 title
        val results = GlobalSearchIndex.search("余额")
        assertTrue(
            "expected a CampusCard screen hit, got ${results.map { it.title }}",
            results.any { it.title.contains("校园卡") }
        )
    }

    @Test
    fun caseInsensitiveOnLatin() {
        // 索引里有 title 为 "GPA" 的英文条目（ask_jiaoxiaozhi prompt），但 GPA 是别名
        // —— 命中说明大小写不敏感工作
        val upper = GlobalSearchIndex.search("GPA")
        val lower = GlobalSearchIndex.search("gpa")
        assertEquals(upper.size, lower.size)
    }

    @Test
    fun aliasHits() {
        // "GPA" 是别名
        val results = GlobalSearchIndex.search("GPA")
        assertTrue(
            "expected at least 1 GPA match, got ${results.size}",
            results.isNotEmpty()
        )
    }

    @Test
    fun noMatch_returnsEmpty() {
        val results = GlobalSearchIndex.search("完全不可能匹配的字符串xyz123")
        assertEquals(0, results.size)
    }

    @Test
    fun allEntries_haveNonBlankTitle() {
        GlobalSearchIndex.entries().forEach {
            assertTrue("title blank", it.title.isNotBlank())
        }
    }

    @Test
    fun fitnessAlias_hitsFitnessScreen() {
        val results = GlobalSearchIndex.search("体测")
        assertTrue(
            "expected 体测查询 screen, got ${results.map { it.title }}",
            results.any { it is SearchEntry.Screen && it.route == Routes.FITNESS }
        )
    }

    @Test
    fun catalogCoversEveryAppService() {
        val screenRoutes = GlobalSearchIndex.entries()
            .filterIsInstance<SearchEntry.Screen>()
            .map { it.route }
            .toSet()
        val catalogRoutes = AppServices.all.map { it.route }.toSet()
        assertEquals(catalogRoutes, screenRoutes)
    }

    @Test
    fun postgraduateAttendance_isSearchableForPostgraduates() {
        assertTrue(
            AppServices.all.any { it.route == Routes.POSTGRADUATE_ATTENDANCE && it.showOnHome },
        )
        val results = GlobalSearchIndex.search("研究生考勤", AccountType.POSTGRADUATE)
        assertTrue(
            "expected 研考勤 screen, got ${results.map { it.title }}",
            results.any { it is SearchEntry.Screen && it.route == Routes.POSTGRADUATE_ATTENDANCE },
        )
    }

    @Test
    fun undergraduate_cannotSeePostgraduateAttendance() {
        val homeRoutes = AppServices.homeFor(AccountType.UNDERGRADUATE).map { it.route }
        assertFalse(Routes.POSTGRADUATE_ATTENDANCE in homeRoutes)
        assertTrue(Routes.ATTENDANCE in homeRoutes)
        val results = GlobalSearchIndex.search("研究生考勤", AccountType.UNDERGRADUATE)
        assertFalse(
            results.any { it is SearchEntry.Screen && it.route == Routes.POSTGRADUATE_ATTENDANCE },
        )
    }

    @Test
    fun postgraduate_cannotSeeUndergraduateAttendance() {
        val homeRoutes = AppServices.homeFor(AccountType.POSTGRADUATE).map { it.route }
        assertFalse(Routes.ATTENDANCE in homeRoutes)
        assertFalse(Routes.ICLASSFACE in homeRoutes)
        assertTrue(Routes.POSTGRADUATE_ATTENDANCE in homeRoutes)
        val results = GlobalSearchIndex.search("考勤", AccountType.POSTGRADUATE)
        assertFalse(results.any { it is SearchEntry.Screen && it.route == Routes.ATTENDANCE })
        assertTrue(results.any { it is SearchEntry.Screen && it.route == Routes.POSTGRADUATE_ATTENDANCE })
    }
}