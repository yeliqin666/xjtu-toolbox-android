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

    /**
     * 字符串/Ktor 版必须与 HttpUrl 版逐条一致 —— 新的 Ktor 出口（`SiteSession.sendWithReAuth`）
     * 靠它判断「是不是被网关打回登录前页」，两边判得不一样就会有一半路径重登、一半不重登。
     */
    @Test
    fun gatewayLoginLandingStringMatchesHttpUrlVersion() {
        val samples = listOf(
            "https://webvpn.xjtu.edu.cn/login",
            WebVpnUtil.WEBVPN_LOGIN_URL,
            "https://webvpn.xjtu.edu.cn/login?cas_login=true&ticket=ST-1",
            "https://webvpn.xjtu.edu.cn/login/",
            "https://webvpn.xjtu.edu.cn/",
            "https://jwxt.xjtu.edu.cn/login",
            "https://webvpn.xjtu.edu.cn/http/77726476706e69737468656265737421f7e140d22520/seat/",
            "not a url",
            "",
        )
        for (s in samples) {
            val byHttpUrl = runCatching { landing(s) }.getOrElse { false }
            org.junit.Assert.assertEquals(
                "isLoginLanding 两个版本必须一致：$s",
                byHttpUrl,
                WebVpnUtil.isLoginLanding(s),
            )
        }
    }
}
