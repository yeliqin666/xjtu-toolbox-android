package com.xjtu.toolbox.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTest {
    @Test fun paymentWaitsForAllAuthenticationHops() {
        assertTrue(isAuthHop("https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1659"))
        assertTrue(isAuthHop("https://login.xjtu.edu.cn/cas/login?service=venue"))
        assertTrue(isAuthHop("http://202.117.17.144:8080/web/login/pre.html"))
        assertTrue(isAuthHop("http://202.117.17.144:8080/web/cas/oauth2url.html?code=temporary"))
        assertFalse(isAuthHop("http://202.117.17.144:8080/web/index.html"))
        assertFalse(isAuthHop("http://202.117.17.144:8080/web/pay/show.html?id=order"))
    }

    /** 移动交大子服务各自的登录入口与回调（地址取自服务列表）：后退要跳过，免得重放一次性凭据。 */
    @Test fun superAppServiceAuthHops() {
        listOf(
            "https://webvpn.xjtu.edu.cn/login",
            "https://dzpz.xjtu.edu.cn/wui/cas-entrance.jsp?path=%2Fspa%2Fworkflow",
            "https://egc.xjtu.edu.cn/page/cas/receiveCas.html?version=SAFT_VERSION",
            "https://ncard.xjtu.edu.cn/berserker-auth/cas/redirect/xjtu?targetName=6",
            "http://housing2021.xjtu.edu.cn/appsys/xjtuCASLogin/mobileLogin?code=abc",
            "http://jdwxpt.xjtu.edu.cn/htwxAPP/OAuthPage?type=LKCX",
            "https://tyxylp.xjtu.edu.cn/bdlp_h5_fitness_test/public/index.php/index/login/xjtuLogin",
            "https://wxjf.xjtu.edu.cn/login-indentify?code=abc&state=login2",
            "https://lcdp.xjtu.edu.cn/apaas/plug/server/login/callback/231249554826615811?code=abc",
            "https://gyyd.xjtu.edu.cn/h5/index.html?code=abc&state=1234",
            "https://reservation.xjtu.edu.cn/h5/?ticket=ST-1-abc",
        ).forEach { assertTrue(it, isAuthHop(it)) }
        listOf(
            "https://workflow.xjtu.edu.cn/fe/taskCenter/one/application?app_id=278",
            "https://kq.xjtu.edu.cn/mobile",
            "https://lms-h5.xjtu.edu.cn/home",
            "https://health.xjtu.edu.cn/homePage/index1",
            "https://ssn.xjtu.edu.cn/cems/index/mobile/pay",
            "https://heemuseum.xjtu.edu.cn/info/1005/1432.htm",
            "https://reservation.xjtu.edu.cn/h5/pages/index/homeCenter?serviceId=1",
            "https://www.example.com/castle/blog?code=promo",
        ).forEach { assertFalse(it, isAuthHop(it)) }
    }
}
