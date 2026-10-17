package com.xjtu.toolbox.lms

import kotlin.test.Test
import kotlin.test.assertEquals

class LmsBulletinImageUrlTest {

    @Test
    fun extractsHttpImageSources() {
        val html = "<p><img alt=\"Image\" class=\"lms-sim-image\" src=\"https://lms.xjtu.edu.cn:443/api/uploads/6156269/in-rich-content?created_at=2026-09-10T11:23:27Z\" width=\"269\"><br></p>"
        assertEquals(
            listOf("https://lms.xjtu.edu.cn:443/api/uploads/6156269/in-rich-content?created_at=2026-09-10T11:23:27Z"),
            extractImageUrls(html),
        )
    }

    @Test
    fun skipsNonHttpAndDecodesAmp() {
        val html = "<img src='data:image/png;base64,AAA'><IMG SRC=https://a.example/x.png?a=1&amp;b=2>"
        assertEquals(listOf("https://a.example/x.png?a=1&b=2"), extractImageUrls(html))
    }
}