package com.xjtu.toolbox.agent

import com.xjtu.toolbox.home.AppServices
import kotlin.test.Test
import kotlin.test.assertTrue

/** 屁岱的 app_guide 必须覆盖服务目录里的每个入口和页内功能，不然它会说 App 没有。 */
class AgentGuidesTest {

    @Test
    fun `指南列出目录里的每个入口和页内功能`() {
        val guide = APP_GUIDE
        AppServices.all.forEach { s ->
            assertTrue(s.title in guide, "指南缺入口：${s.title}")
            s.features.forEach { assertTrue(it in guide, "指南缺「${s.title}」的页内功能：$it") }
        }
    }

    @Test
    fun `页内功能写成名称加说明`() {
        AppServices.all.flatMap { it.features }.forEach {
            assertTrue('：' in it && it.substringBefore('：').isNotBlank() && it.substringAfter('：').isNotBlank(),
                "页内功能要写成「名称：说明」：$it")
        }
    }

    @Test
    fun `首页小贴士都能被小岱查到`() {
        com.xjtu.toolbox.tips.Tips.all.forEach { assertTrue(it.body in APP_GUIDE, "指南缺小贴士：${it.title}") }
    }

    @Test
    fun `干饭人账单能被屁岱查到`() {
        assertTrue("干饭人账单" in APP_GUIDE)
    }
}
