package com.xjtu.toolbox.auth

import com.xjtu.toolbox.webvpn.WebVpnUtil
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginLandingTest {
    private fun cas(url: String) = XJTULogin.isCasLoginUrl(url.toHttpUrl())
    private fun landing(url: String) = WebVpnUtil.isLoginLanding(url.toHttpUrl())

    @Test
    fun casLoginPageDirectAndProxied() {
        assertTrue(cas("https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fywtb.xjtu.edu.cn%2F"))
        assertTrue(cas(WebVpnUtil.getVpnUrl("https://login.xjtu.edu.cn/cas/login?service=x")))
        assertFalse(cas("https://login.xjtu.edu.cn/cas/oauth2.0/authorize?client_id=1"))
        assertFalse(cas("https://ywtb.xjtu.edu.cn/?ticket=ST-1"))
        assertFalse(cas(WebVpnUtil.getVpnUrl("https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do")))
        assertTrue(XJTULogin.casPath(WebVpnUtil.getVpnUrl("https://login.xjtu.edu.cn/cas/sec/initByType").toHttpUrl()) != null)
        assertTrue(XJTULogin.casPath("https://ywtb.xjtu.edu.cn/".toHttpUrl()) == null)
    }

    @Test
    fun gatewayLoginLanding() {
        assertTrue(landing("https://webvpn.xjtu.edu.cn/login"))
        assertFalse(landing(WebVpnUtil.WEBVPN_LOGIN_URL))
        assertFalse(landing("https://webvpn.xjtu.edu.cn/login?cas_login=true&ticket=ST-1"))
        assertFalse(landing("https://webvpn.xjtu.edu.cn/"))
        assertFalse(landing("https://jwxt.xjtu.edu.cn/login"))
    }
}
