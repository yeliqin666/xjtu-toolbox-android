package com.xjtu.toolbox.bulletin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 公告 OSS 与 Gitee 两份取较新的那条规则：过期的 OSS 缓存不能盖掉 Gitee 上的新公告。 */
class BulletinApiNewerTest {

    private fun doc(updatedAt: String?) =
        if (updatedAt == null) """{"bulletins":[]}""" else """{"updatedAt":"$updatedAt","bulletins":[]}"""

    @Test
    fun `较新的一份胜出，不管来自哪边`() {
        val gitee = doc("2026-11-24T21:00:00+08:00")
        val oss = doc("2026-11-24T22:00:00+08:00")
        assertEquals(oss, BulletinApi.newer(gitee, oss))
        assertEquals(gitee, BulletinApi.newer(gitee, null))
    }

    @Test
    fun `OSS 是旧缓存（没有时间或时间更早）时用 Gitee`() {
        val gitee = doc("2026-11-24T21:00:00+08:00")
        assertEquals(gitee, BulletinApi.newer(gitee, doc(null)))
        assertEquals(gitee, BulletinApi.newer(gitee, doc("2026-10-23T17:04:16+08:00")))
        assertEquals(gitee, BulletinApi.newer(gitee, gitee))
    }

    @Test
    fun `一边取不到就用另一边`() {
        val oss = doc("2026-11-24T22:00:00+08:00")
        assertEquals(oss, BulletinApi.newer(null, oss))
        assertNull(BulletinApi.newer(null, null))
    }
}
