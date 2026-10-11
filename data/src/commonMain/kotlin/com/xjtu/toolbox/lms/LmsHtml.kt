package com.xjtu.toolbox.lms

import org.jsoup.Jsoup

/**
 * 富文本正文转纯文本：换行只认 `<br>` 和块级元素，HTML 源码里的换行缩进当空格。
 * 段落里套着 span 等行内元素时文字不在 body 直属节点上，不能只取 own text。
 *
 * 为什么在 `:data`：jsoup 只有一份 JVM jar（`data/build.gradle.kts` 的文件头写了），
 * 进不了 `:core`（wasm 编不过）—— 所以从 `:app` 的 `LmsActivityDetail.kt` 原样搬来这里，
 * `:core` 的屏经 [com.xjtu.toolbox.lms.LmsSource.htmlToPlainText] 端口调它。方法体一行未改，
 * `:app` 的 `LmsHtmlTextTest` 继续钉着这四个形状。
 */
fun htmlToPlainText(html: String): String {
    val body = Jsoup.parse(html).body()
    body.select("*").forEach { el -> el.textNodes().forEach { it.text(it.text()) } }
    // wholeText 自己会把 <br> 算成换行，块级元素要补
    body.select("p, div, li, h1, h2, h3, h4, h5, h6, tr, blockquote").after("\n")
    return body.wholeText().lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}