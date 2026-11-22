package com.xjtu.toolbox.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampusGuideTest {

    private fun top(query: String) =
        rankCampusGuide(CAMPUS_GUIDE, query).firstOrNull()?.title ?: error("「$query」没有命中")

    @Test
    fun `每节都解析出条目，条目名跳过书名号里的竖线`() {
        assertEquals(CAMPUS_GUIDE_SECTIONS.size, CAMPUS_GUIDE.map { it.section }.distinct().size)
        assertTrue(CAMPUS_GUIDE.size > 180, "只解析出 ${CAMPUS_GUIDE.size} 条")
        assertTrue(CAMPUS_GUIDE.any { it.title == "《新生百问｜你的大学\"联络簿\"》" }, CAMPUS_GUIDE.take(3).joinToString { it.title })
    }

    @Test
    fun `口语问句也能找到对应条目`() {
        assertTrue("转专业" in top("怎么转专业"))
        assertTrue("校园网" in top("校园网流量怎么收费"))
        assertTrue("补考" in top("补考可以考几次"))
        assertTrue("图书馆" in top("兴庆图书馆几点关门"))
        assertTrue("VPN" in top("校外怎么连vpn"))
    }

    @Test
    fun `不相关的词不硬凑`() {
        assertTrue(rankCampusGuide(CAMPUS_GUIDE, "量子纠缠").isEmpty())
        assertTrue("没有" in campusGuideText("量子纠缠", null, null))
    }

    @Test
    fun `无参数给目录，只给节名列整节`() {
        assertTrue(campusGuideText(null, null, null).startsWith("校园指南共"))
        val sec = campusGuideText(null, "选课", null)
        assertTrue("【三、选课】" in sec && "《第四轮补退选》" in sec)
        assertTrue("没有「火星」这一节" in campusGuideText(null, "火星", null))
        assertEquals(10, campusGuideText("学生", null, 50).lines().count { it.startsWith("【") })
    }
}
