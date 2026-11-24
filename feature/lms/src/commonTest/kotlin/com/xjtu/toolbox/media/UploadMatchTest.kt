package com.xjtu.toolbox.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「这份资料是不是已经下过了」的判据。
 *
 * 这条判据判错的两个方向都有代价：判松了会把「老师换了新版课件」当成已下载，用户点开看到旧内容；
 * 判紧了就退回「每次点都下一份重复文件」，正是 2026-11 要修的那个毛病。所以两个方向都钉住。
 */
class UploadMatchTest {
    private fun file(name: String, size: Long) =
        DownloadedFile(name = name, mimeType = "application/pdf", uri = "content://x/$name", savedAt = 1L, size = size)

    @Test
    fun 同名同大小算同一份() {
        assertTrue(file("第一章.pdf", 1024).matchesUpload("第一章.pdf", 1024))
    }

    @Test
    fun 同名但大小不同是老师换了新版要重新下() {
        assertFalse(
            file("第一章.pdf", 1024).matchesUpload("第一章.pdf", 4096),
            "文件名一模一样也不能算已下载：内容换过了",
        )
    }

    @Test
    fun 不同名不算同一份() {
        assertFalse(file("第一章.pdf", 1024).matchesUpload("第二章.pdf", 1024))
    }

    @Test
    fun 拿不到大小时退化成只比名字() {
        // 老接口不给字节数 / 老登记里没有大小：这时名字是唯一能拿到的事实
        assertTrue(file("第一章.pdf", 0).matchesUpload("第一章.pdf", 1024))
        assertTrue(file("第一章.pdf", 1024).matchesUpload("第一章.pdf", 0))
        assertTrue(file("第一章.pdf", 0).matchesUpload("第一章.pdf", 0))
        // 但名字不同照样不是同一份
        assertFalse(file("第一章.pdf", 0).matchesUpload("第二章.pdf", 0))
    }
}
