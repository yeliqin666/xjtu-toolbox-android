package com.xjtu.toolbox.lms

import org.jsoup.Jsoup

/** 富文本正文转纯文本：换行只认 `<br>` 和块级元素，HTML 源码里的换行缩进当空格。 */
fun htmlToPlainText(html: String): String {
    val body = Jsoup.parse(html).body()
    body.select("*").forEach { el -> el.textNodes().forEach { it.text(it.text()) } }
    // wholeText 自己会把 <br> 算成换行，块级元素要补
    body.select("p, div, li, h1, h2, h3, h4, h5, h6, tr, blockquote").after("\n")
    return body.wholeText().lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}