package com.xjtu.toolbox.nav

import com.xjtu.toolbox.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 深链、桌面快捷方式、通知存的都是路由字符串，换成 miuix-nav 以后要靠 [appRouteOf] 解析回来。
 * 这里把「字符串 → 对象 → 字符串」来回一圈钉死，尤其是三种带参数的写法。
 */
class AppRouteTest {

    @Test
    fun `无参数路由来回一致`() {
        listOf(Routes.MAIN, Routes.SETTINGS, Routes.JIAOCAI1, Routes.MATCH, Routes.GAME_2048).forEach { id ->
            assertEquals(id, appRouteOf(id)?.id)
        }
    }

    @Test
    fun `思源学堂带不带课程号都认`() {
        assertEquals(AppRoute.Lms(null), appRouteOf(Routes.LMS))
        assertEquals(AppRoute.Lms(123), appRouteOf(Routes.lmsCourse(123)))
        assertEquals(Routes.lmsCourse(123), AppRoute.Lms(123).id)
    }

    @Test
    fun `浏览器链接里的特殊字符来回不走样`() {
        val url = "https://example.com/a?b=1&c=中文#frag"
        val route = appRouteOf(Routes.browser(url))
        assertEquals(AppRoute.Browser(url), route)
        assertEquals(Routes.browser(url), route?.id)
        assertEquals(AppRoute.Browser(""), appRouteOf("browser"))
    }

    @Test
    fun `教材阅读器的书号和书名`() {
        val id = Routes.jiaocai1Reader("12345", "高等数学（第七版）上册")
        assertEquals(AppRoute.Jiaocai1Reader("12345", "高等数学（第七版）上册"), appRouteOf(id))
        assertNull(appRouteOf("jiaocai1_reader/"))
    }

    @Test
    fun `返回栈能序列化再读回来`() {
        // miuix-nav 用 kotlinx.serialization 把返回栈存进 rememberSaveable，切到后台时才触发；
        // 这里提前在单测里走一遍，插件没生效或者哪个路由漏了 @Serializable 会在这里就挂。
        val stack = listOf(
            AppRoute.Main, AppRoute.Settings, AppRoute.Lms(7),
            AppRoute.Browser("https://a.b/?c=中"), AppRoute.Jiaocai1Reader("1", "书"),
        )
        val ser = kotlinx.serialization.serializer<List<AppRoute>>()
        val json = kotlinx.serialization.json.Json.encodeToString(ser, stack)
        assertEquals(stack, kotlinx.serialization.json.Json.decodeFromString(ser, json))
    }

    @Test
    fun `不认识的字符串返回 null 而不是抛异常`() {
        assertNull(appRouteOf("no_such_route"))
        assertNull(appRouteOf(""))
        // 付款码不进返回栈，由 AppNavigator 单独处理
        assertNull(appRouteOf(Routes.PAYMENT_CODE))
    }
}
