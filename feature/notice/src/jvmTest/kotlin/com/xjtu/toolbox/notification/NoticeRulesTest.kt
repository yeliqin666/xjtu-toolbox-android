package com.xjtu.toolbox.notification

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeRulesTest {
    private var previousRoot: File? = null

    @BeforeTest fun setup() {
        previousRoot = keyValueStoreRootOverride
        keyValueStoreRootOverride = Files.createTempDirectory("notice-rules-").toFile()
    }

    @AfterTest fun teardown() {
        keyValueStoreRootOverride = previousRoot
    }

    @Test fun wordsSplitOnCommonSeparators() {
        assertEquals(listOf("考试", "选课", "缓考", "讲座"), noticeRuleWords(" 考试，选课、缓考  讲座,考试 "))
    }

    @Test fun blockWinsAndOnlyNeedsAnyWord() {
        val rule = NoticeRule(only = "考试 选课", block = "研究生")
        assertTrue(rule.accepts("关于期末考试安排的通知"))
        assertTrue(rule.accepts("本科生选课说明"))
        assertFalse(rule.accepts("研究生考试报名"))
        assertFalse(rule.accepts("讲座预告"))
        assertTrue(NoticeRule(block = "招聘").accepts("任何不含屏蔽词的标题"))
        assertTrue(NoticeRule().isEmpty)
    }

    @Test fun storeRoundTripsAndDropsClearedRules() {
        val store = NoticeRuleStore(keyValueStore("rules"))
        store.save(mapOf(NotificationSource.JWC to NoticeRule(only = "考试"), NotificationSource.GS to NoticeRule(block = "招生")))
        assertEquals(
            mapOf(NotificationSource.JWC to NoticeRule(only = "考试"), NotificationSource.GS to NoticeRule(block = "招生")),
            NoticeRuleStore(keyValueStore("rules")).load(),
        )

        store.save(mapOf(NotificationSource.JWC to NoticeRule(only = "  ")))
        assertEquals(emptyMap(), NoticeRuleStore(keyValueStore("rules")).load())
    }
}
