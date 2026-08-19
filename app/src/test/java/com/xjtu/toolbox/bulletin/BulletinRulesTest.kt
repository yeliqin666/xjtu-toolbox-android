package com.xjtu.toolbox.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BulletinRulesTest {

    private val now = Instant.parse("2026-08-19T08:00:00+08:00")

    @Test
    fun parse_invalidJson_returnsEmpty() {
        assertTrue(BulletinRules.parsePayload("not-json").isEmpty())
        assertTrue(BulletinRules.parsePayload("").isEmpty())
        assertTrue(BulletinRules.parsePayload("{\"hello\":1}").isEmpty())
    }

    @Test
    fun parse_skipsItemsWithoutIdOrTitle() {
        val items = BulletinRules.parsePayload(
            """
            {"bulletins":[
              {"id":"","title":"x"},
              {"id":"a","title":""},
              {"id":"ok","title":"有标题","level":"warn"}
            ]}
            """.trimIndent()
        )
        assertEquals(1, items.size)
        assertEquals("ok", items[0].id)
        assertEquals(BulletinLevel.WARN, items[0].level)
    }

    @Test
    fun critical_defaultsMustAck() {
        val items = BulletinRules.parsePayload(
            """{"bulletins":[{"id":"c","title":"重大","level":"critical"}]}"""
        )
        assertTrue(items[0].mustAck)
    }

    @Test
    fun timeWindow_beforeStart_hidden() {
        val b = sample(startsAt = Instant.parse("2026-08-20T00:00:00+08:00"))
        assertFalse(BulletinRules.isActive(b, now, "4.71"))
    }

    @Test
    fun timeWindow_afterEnd_hidden() {
        val b = sample(endsAt = Instant.parse("2026-08-19T07:00:00+08:00"))
        assertFalse(BulletinRules.isActive(b, now, "4.71"))
    }

    @Test
    fun timeWindow_inside_active() {
        val b = sample(
            startsAt = Instant.parse("2026-08-19T00:00:00+08:00"),
            endsAt = Instant.parse("2026-08-21T23:59:59+08:00"),
        )
        assertTrue(BulletinRules.isActive(b, now, "4.71"))
    }

    @Test
    fun parseInstant_beijingLocalDateTime() {
        val instant = BulletinRules.parseInstant("2026-08-19T08:00:00")
        assertEquals(Instant.parse("2026-08-19T00:00:00Z"), instant)
    }

    @Test
    fun versionRange_minAndMax() {
        val b = sample(minVersion = "4.6", maxVersion = "4.71")
        assertTrue(BulletinRules.isActive(b, now, "4.71"))
        assertFalse(BulletinRules.isActive(b, now, "4.5"))
        assertFalse(BulletinRules.isActive(b, now, "4.72"))
    }

    @Test
    fun targetVersion_hidesWhenAlreadyThere() {
        val b = sample(targetVersion = "4.72")
        assertTrue(BulletinRules.isActive(b, now, "4.71"))
        assertFalse(BulletinRules.isActive(b, now, "4.72"))
        assertFalse(BulletinRules.isActive(b, now, "4.73"))
    }

    @Test
    fun forceBelow_escalatesSuggestedUpdate() {
        val suggested = sample(level = BulletinLevel.UPDATE, forceBelow = "4.72")
        assertEquals(
            BulletinLevel.FORCE_UPDATE,
            BulletinRules.resolveForVersion(suggested, "4.71").level,
        )
        assertEquals(
            BulletinLevel.UPDATE,
            BulletinRules.resolveForVersion(suggested, "4.72").level,
        )
    }

    @Test
    fun parse_targetVersionAndForceBelow() {
        val items = BulletinRules.parsePayload(
            """{"bulletins":[{"id":"u","title":"升到 4.72","level":"update","targetVersion":"4.72","forceBelow":"4.70"}]}"""
        )
        assertEquals("4.72", items[0].targetVersion)
        assertEquals("4.70", items[0].forceBelow)
    }

    @Test
    fun parse_updateLevel() {
        val items = BulletinRules.parsePayload(
            """{"bulletins":[{"id":"u","title":"发现新版本","level":"update"}]}"""
        )
        assertEquals(BulletinLevel.UPDATE, items[0].level)
        assertFalse(items[0].mustAck)
    }

    @Test
    fun pick_updateOutranksWarn() {
        val items = listOf(
            sample(id = "w", level = BulletinLevel.WARN),
            sample(id = "u", level = BulletinLevel.UPDATE),
        )
        val picked = BulletinRules.pick(items, now, "4.71", emptySet(), emptySet(), emptySet())
        assertEquals("u", picked?.id)
    }

    @Test
    fun pick_prefersHigherLevelThenNewerId() {
        val items = listOf(
            sample(id = "2026-08-01-a", level = BulletinLevel.WARN),
            sample(id = "2026-08-19-b", level = BulletinLevel.INFO),
            sample(id = "2026-08-10-c", level = BulletinLevel.CRITICAL),
            sample(id = "2026-08-18-d", level = BulletinLevel.CRITICAL),
        )
        val picked = BulletinRules.pick(items, now, "4.71", emptySet(), emptySet(), emptySet())
        assertEquals("2026-08-18-d", picked?.id)
    }

    @Test
    fun visible_stacksByLevelThenNewerId() {
        val items = listOf(
            sample(id = "2026-08-01-a", level = BulletinLevel.WARN),
            sample(id = "2026-08-19-b", level = BulletinLevel.INFO),
            sample(id = "2026-08-10-c", level = BulletinLevel.CRITICAL),
            sample(id = "2026-08-18-d", level = BulletinLevel.CRITICAL),
        )
        val visible = BulletinRules.visible(items, now, "4.71", emptySet(), emptySet(), emptySet())
        assertEquals(
            listOf("2026-08-18-d", "2026-08-10-c", "2026-08-01-a", "2026-08-19-b"),
            visible.map { it.id },
        )
    }

    @Test
    fun pick_skipsDismissedInfo() {
        val items = listOf(sample(id = "gone", level = BulletinLevel.INFO))
        val picked = BulletinRules.pick(items, now, "4.71", setOf("gone"), emptySet(), emptySet())
        assertNull(picked)
    }

    @Test
    fun pick_skipsAckedCritical() {
        val items = listOf(sample(id = "c1", level = BulletinLevel.CRITICAL, mustAck = true))
        val picked = BulletinRules.pick(items, now, "4.71", emptySet(), setOf("c1"), emptySet())
        assertNull(picked)
    }

    @Test
    fun pick_skipsSnoozedForceUpdate() {
        val items = listOf(sample(id = "fu", level = BulletinLevel.FORCE_UPDATE))
        val picked = BulletinRules.pick(items, now, "4.71", emptySet(), emptySet(), setOf("fu"))
        assertNull(picked)
    }

    @Test
    fun launchDialog_forCriticalForceUpdateOrBlock() {
        assertTrue(BulletinRules.shouldShowLaunchDialog(sample(level = BulletinLevel.CRITICAL)))
        assertTrue(BulletinRules.shouldShowLaunchDialog(sample(level = BulletinLevel.FORCE_UPDATE)))
        assertTrue(BulletinRules.shouldShowLaunchDialog(sample(level = BulletinLevel.INFO, block = true)))
        assertFalse(BulletinRules.shouldShowLaunchDialog(sample(level = BulletinLevel.WARN)))
        assertFalse(BulletinRules.shouldShowLaunchDialog(sample(level = BulletinLevel.UPDATE)))
    }

    @Test
    fun compareVersions_compactEqualsDotted() {
        assertEquals(0, BulletinRules.compareVersions("4.72", "4.7.2"))
        assertEquals(0, BulletinRules.compareVersions("4.71", "4.7.1"))
        assertEquals(0, BulletinRules.compareVersions("4.61", "4.6.1"))
        assertTrue(BulletinRules.compareVersions("4.72", "4.7.3") < 0)
        assertTrue(BulletinRules.compareVersions("4.71", "4.7.3") < 0)
        assertTrue(BulletinRules.compareVersions("4.7.3", "4.7") > 0)
    }

    @Test
    fun syntheticUpdate_usesUpdateLevel() {
        val syn = BulletinRules.syntheticUpdate("4.8", "gitee")
        assertEquals(BulletinLevel.UPDATE, syn.level)
        assertTrue(syn.synthesized)
        assertEquals("auto_gitee_4.8", syn.id)
    }

    private fun sample(
        id: String = "sample",
        level: BulletinLevel = BulletinLevel.INFO,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
        minVersion: String? = null,
        maxVersion: String? = null,
        targetVersion: String? = null,
        forceBelow: String? = null,
        mustAck: Boolean = level == BulletinLevel.CRITICAL,
        block: Boolean = false,
    ) = Bulletin(
        id = id,
        level = level,
        title = "t",
        body = "b",
        startsAt = startsAt,
        endsAt = endsAt,
        minVersion = minVersion,
        maxVersion = maxVersion,
        targetVersion = targetVersion,
        forceBelow = forceBelow,
        mustAck = mustAck,
        block = block,
    )
}
