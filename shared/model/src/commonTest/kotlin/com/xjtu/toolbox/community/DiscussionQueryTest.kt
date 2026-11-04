package com.xjtu.toolbox.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscussionQueryTest {
    private val general = GithubDiscussionCategory("c1", "综合讨论", false)
    private val notice = GithubDiscussionCategory("c2", ANNOUNCEMENT_CATEGORY, false)

    @Test
    fun searchTextCombinesStateAuthorCategoryAndSort() {
        val text = discussionSearchText(
            "o", "r",
            DiscussionQuery(categoryName = "综合\"讨论", state = DiscussionStateFilter.CLOSED, sort = DiscussionSort.CREATED, author = "@a b-1"),
        )
        assertEquals("repo:o/r is:discussion is:closed author:ab-1 category:\"综合讨论\" sort:created-desc", text)
    }

    @Test
    fun searchTextDefaultsToOpenAndRecentlyUpdated() {
        assertEquals("repo:o/r is:discussion is:open sort:updated-desc", discussionSearchText("o", "r", DiscussionQuery()))
        assertEquals(
            "repo:o/r is:discussion sort:updated-desc",
            discussionSearchText("o", "r", DiscussionQuery(state = DiscussionStateFilter.ALL)),
        )
    }

    @Test
    fun onlyAdminAndMaintainCanPostAnnouncements() {
        for (perm in listOf("ADMIN", "MAINTAIN")) assertTrue(canPostIn(notice, perm), perm)
        for (perm in listOf("WRITE", "TRIAGE", "READ", null)) assertFalse(canPostIn(notice, perm), perm.toString())
    }

    @Test
    fun otherCategoriesAreOpenToEveryone() {
        for (perm in listOf("ADMIN", "WRITE", "READ", null)) assertTrue(canPostIn(general, perm), perm.toString())
    }

    @Test
    fun postableCategoriesHidesAnnouncementFromOrdinaryUsers() {
        val all = listOf(general, notice)
        assertEquals(listOf(general), postableCategories(all, "WRITE"))
        assertEquals(listOf(general), postableCategories(all, null))
        assertEquals(all, postableCategories(all, "MAINTAIN"))
    }
}
