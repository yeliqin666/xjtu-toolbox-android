package com.xjtu.toolbox.lms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 批量下载的**纯编排**钉住测试 —— 从 `:app` 搬到 `:core` 之后，这三段（文件名消毒 / 收集 /
 * 重名去重）要能在三端编过、能离开 MediaStore 单测。逻辑与搬迁前逐字同构；「落到哪儿」由
 * [LmsDownloadSink] 的宿主实现负责，这里不碰。
 */
class LmsBatchDownloadTest {

    @Test
    fun sanitize_换掉非法字符并收尾() {
        assertEquals("a_b_c", LmsBatchDownload.sanitize("a/b\\c"))
        assertEquals("作业", LmsBatchDownload.sanitize(" 作业. "))
        assertEquals("file", LmsBatchDownload.sanitize("..."))
        assertEquals("file", LmsBatchDownload.sanitize(""))
        // 「全是非法字符」不等于空白：正则的 `+` 把连续非法字符**并成一个**下划线
        // ⇒ "???" 落成 "_"（不是 "file"）。只有去掉首尾空白与点之后真空了（"..." / ""）才兜底 "file"。
        assertEquals("_", LmsBatchDownload.sanitize("???"))
        // 超长截断到 120
        assertTrue(LmsBatchDownload.sanitize("x".repeat(200)).length == 120)
    }

    @Test
    fun collect_作业的附件与提交与批阅版按选项收集() {
        val homework = LmsActivity(
            id = 5,
            type = LmsActivityType.HOMEWORK,
            title = "第十三次作业",
            uploads = listOf(LmsUpload(id = 1, name = "题目.pdf", type = "application/pdf")),
            submissionList = LmsSubmissionListResponse(
                list = listOf(
                    LmsSubmissionItem(
                        id = 9,
                        activityId = 5,
                        uploads = listOf(
                            LmsUpload(id = 2, name = "我交的.zip", type = "application/zip", attachmentUrl = "https://x/marked.zip"),
                        ),
                    ),
                ),
            ),
        )

        // 默认档（`marked = false`）：题目附件 + 我提交的 —— 批阅版要用户自己勾（与搬迁前同一个默认）
        val defaults = mutableListOf<LmsDownloadJob>()
        LmsBatchDownload.collect(homework, "数据结构", LmsBatchDownload.Options(), defaults)
        assertEquals(2, defaults.size, "附件 + 我的提交")
        assertEquals(LmsDownloadJob("题目.pdf", "application/pdf", "数据结构", upload = homework.uploads[0]), defaults[0])
        assertEquals("提交_我交的.zip", defaults[1].name)

        // 勾上「批阅版」才多出的那一条：upload 为空、url 是批阅附件直链 ——
        // 搬迁前这批的 mime 是「按扩展名猜」，所以传空串
        val marked = mutableListOf<LmsDownloadJob>()
        LmsBatchDownload.collect(homework, "数据结构", LmsBatchDownload.Options(marked = true), marked)
        assertEquals(3, marked.size, "附件 + 我的提交 + 批阅版")
        assertEquals("批阅_我交的_批阅.zip", marked[2].name)
        assertEquals("", marked[2].mimeType)
        assertEquals("https://x/marked.zip", marked[2].url)
        assertTrue(marked[2].upload == null)

        // 关掉 submissions / marked：只剩题目附件
        val uploadsOnly = mutableListOf<LmsDownloadJob>()
        LmsBatchDownload.collect(homework, "数据结构", LmsBatchDownload.Options(submissions = false, marked = false), uploadsOnly)
        assertEquals(1, uploadsOnly.size)
        assertEquals("题目.pdf", uploadsOnly[0].name)
    }

    @Test
    fun dedupe_同一文件夹重名加序号_不同文件夹各留各的() {
        val upload = LmsUpload(id = 1, name = "第一章课件.pdf", type = "application/pdf")
        val jobs = listOf(
            LmsDownloadJob("第一章课件.pdf", "application/pdf", "A", upload = upload),
            LmsDownloadJob("第一章课件.pdf", "application/pdf", "A", upload = upload),
            LmsDownloadJob("第一章课件.pdf", "application/pdf", "B", upload = upload),
        )
        val deduped = LmsBatchDownload.dedupe(jobs)
        assertEquals("第一章课件.pdf", deduped[0].name)
        assertEquals("第一章课件 (2).pdf", deduped[1].name)
        assertEquals("第一章课件.pdf", deduped[2].name)
    }
}