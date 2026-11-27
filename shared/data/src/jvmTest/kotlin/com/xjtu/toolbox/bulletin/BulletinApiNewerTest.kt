package com.xjtu.toolbox.bulletin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 公告 OSS 与 GitHub 两份取较新的那条规则：过期的 OSS 边缘缓存不能盖掉分支上的新公告。 */
class BulletinApiNewerTest {

    private fun doc(updatedAt: String?) =
        if (updatedAt == null) """{"bulletins":[]}""" else """{"updatedAt":"$updatedAt","bulletins":[]}"""

    @Test
    fun `较新的一份胜出，不管来自哪边`() {
        val github = doc("2026-11-24T21:00:00+08:00")
        val oss = doc("2026-11-24T22:00:00+08:00")
        assertEquals(oss, BulletinApi.newer(github, oss))
        assertEquals(github, BulletinApi.newer(github, null))
    }

    @Test
    fun `OSS 是旧缓存（没有时间或时间更早）时用 GitHub`() {
        val github = doc("2026-11-24T21:00:00+08:00")
        assertEquals(github, BulletinApi.newer(github, doc(null)))
        assertEquals(github, BulletinApi.newer(github, doc("2026-10-23T17:04:16+08:00")))
        assertEquals(github, BulletinApi.newer(github, github))
    }

    @Test
    fun `一边取不到就用另一边`() {
        val oss = doc("2026-11-24T22:00:00+08:00")
        assertEquals(oss, BulletinApi.newer(null, oss))
        assertNull(BulletinApi.newer(null, null))
    }
}
