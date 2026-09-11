package com.xjtu.toolbox.zyxf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZyxfDownloaderTest {

    @Test
    fun cmsOneShot_requiresCodeValueOnDownloadJsp() {
        val withCode =
            "https://dean.xjtu.edu.cn/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=1&wbfileid=2&codeValue=ab12"
        val captchaPage =
            "https://dean.xjtu.edu.cn/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=1&wbfileid=2"
        assertTrue(isCmsOneShotDownload(withCode))
        assertFalse(isCmsOneShotDownload(captchaPage))
        assertFalse(isCmsOneShotDownload("https://dean.xjtu.edu.cn/info/1092/10439.htm"))
    }

    @Test
    fun htmlBodyWithoutDisposition_isRejected() {
        val html = "<!DOCTYPE html><html><title>附件下载</title></html>".toByteArray()
        assertTrue(isHtmlDisguisedAsFile("text/html;charset=UTF-8", null, html))
        assertFalse(
            isHtmlDisguisedAsFile(
                "application/octet-stream",
                "attachment; filename=\"a.docx\"",
                html,
            )
        )
        assertFalse(
            isHtmlDisguisedAsFile(
                "application/pdf",
                null,
                "%PDF-1.4".toByteArray(),
            )
        )
    }
}
