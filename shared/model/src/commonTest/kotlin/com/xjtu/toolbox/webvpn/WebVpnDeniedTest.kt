package com.xjtu.toolbox.webvpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网关拒绝页是 HTTP 200 的 HTML（#125 的现场样本），只能认内容。
 * 「目标站点连不上」的页面也是 200，但那不是权限问题，不能被误认。
 * 拦截器那半在 `:shared:data` 的 `WebVpnInterceptorDeniedTest`。
 */
class WebVpnDeniedTest {

    private val deniedBody = "<html><head><title>访问被拒绝</title></head><body>您没有访问该资源的权限，请联系管理员处理</body></html>"
    private val unreachableBody = "<html><head><title>访问出错</title></head><body><h3>出错啦！该网站无法访问!</h3></body></html>"

    @Test
    fun 拒绝页能认出() {
        assertTrue(WebVpnDenied.isDeniedPage(deniedBody))
        assertTrue(WebVpnDenied.isDeniedPage("<title> 访问被拒绝 </title>"))
        assertTrue(WebVpnDenied.isDeniedPage("您没有访问该资源的权限，请联系管理员处理"))
    }

    @Test
    fun 目标站点连不上和正常页面都不算拒绝() {
        assertFalse(WebVpnDenied.isDeniedPage(unreachableBody))
        assertFalse(WebVpnDenied.isDeniedPage("<html><title>思源学堂</title></html>"))
        assertFalse(WebVpnDenied.isDeniedPage(""))
    }

    @Test
    fun 申请入口指向事务中心() {
        assertTrue(WebVpnDenied.APPLY_URL.startsWith("https://workflow.xjtu.edu.cn/"))
        assertTrue("app_id=326" in WebVpnDenied.APPLY_URL)
    }
}