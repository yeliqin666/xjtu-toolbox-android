package com.xjtu.toolbox.faculty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacultyApiTest {

    @Test
    fun `主页地址的各种写法都规整成标准中文主页`() {
        val std = "https://gr.xjtu.edu.cn/candyly/zh_CN/index.htm"
        listOf(
            std,
            "https://gr.xjtu.edu.cn/candyly/en/index.htm",
            "https://gr.xjtu.edu.cn/candyly",
            "https://gr.xjtu.edu.cn/candyly/",
            "https://gr.xjtu.edu.cn/web/candyly/home",
            "https://gr.xjtu.edu.cn/en/web/candyly",
            "http://gr.xjtu.edu.cn/web/candyly",
            "https://faculty.xjtu.edu.cn/candyly/zh_CN/index.htm",
            " $std ",
            com.xjtu.toolbox.webvpn.WebVpnUtil.getVpnUrl(std),
        ).forEach { assertEquals(it, std, FacultyApi.normalizeHomepage(it)) }
    }

    @Test
    fun `站点名带点、带空格照样认`() {
        assertEquals("https://gr.xjtu.edu.cn/xiaogang.han/zh_CN/index.htm", FacultyApi.normalizeHomepage("https://gr.xjtu.edu.cn/xiaogang.han/en/index.htm"))
        val spaced = FacultyApi.normalizeHomepage("https://gr.xjtu.edu.cn/Jianyong Lou/en/index.htm")
        assertEquals("https://gr.xjtu.edu.cn/Jianyong%20Lou/zh_CN/index.htm", spaced)
        assertEquals("Jianyong Lou", FacultyApi.siteOf(spaced))
    }

    @Test
    fun `站外地址和空值原样返回`() {
        listOf(
            "",
            "https://orcid.org/0000-0002-0379-4059",
            "https://chem.xjtu.edu.cn/szdw1/jsml.htm",
            "https://faculty.xjtu.edu.cn/search.jsp",
            "https://gr.xjtu.edu.cn/system/resource/tsites/x.jsp",
        ).forEach { assertEquals(it, it, FacultyApi.normalizeHomepage(it)) }
    }

    @Test
    fun `只认标准主页的站点名`() {
        assertEquals("candyly", FacultyApi.siteOf("https://gr.xjtu.edu.cn/candyly/zh_CN/index.htm"))
        assertNull(FacultyApi.siteOf("https://gr.xjtu.edu.cn/candyly"))
        assertNull(FacultyApi.siteOf("https://intelligentenergy.github.io"))
        assertNull(FacultyApi.siteOf(""))
    }
}
