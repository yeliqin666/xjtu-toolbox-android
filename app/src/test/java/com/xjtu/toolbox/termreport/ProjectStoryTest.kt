package com.xjtu.toolbox.termreport

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectStoryTest {

    private val story = ProjectStory(
        date = "2026-11-23",
        born = "2026-02-18T23:10",
        stars = listOf("2026-02-19", "2026-03-01", "2026-03-02") + List(57) { "2026-04-01" },
        coders = listOf(ProjectStory.Person("Alice", 30), ProjectStory.Person("bob", 2)),
        askers = listOf(ProjectStory.Person("alice", 5), ProjectStory.Person("Carol", 1)),
        talkers = listOf(ProjectStory.Person("BOB", 1)),
    )

    @Test
    fun `不止在一处留名的人按小写名认`() {
        assertEquals(setOf("alice", "bob"), story.multiNames)
    }

    @Test
    fun `星星里程碑取第 1 10 50 颗和最新一颗`() {
        val marks = story.starMilestones()
        assertEquals(listOf(1, 10, 50, 60), marks.map { it.first })
        assertEquals(LocalDate(2026, 2, 19), marks.first().second)
        assertEquals(LocalDate(2026, 4, 1), marks.last().second)
    }

    @Test
    fun `第一颗星离诞生几天`() {
        assertEquals(1L, story.firstStarGap())
        assertEquals(null, story.copy(stars = emptyList()).firstStarGap())
    }
}
