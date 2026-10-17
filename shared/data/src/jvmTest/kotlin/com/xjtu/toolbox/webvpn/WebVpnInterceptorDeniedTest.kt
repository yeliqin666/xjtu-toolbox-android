package com.xjtu.toolbox.webvpn

import com.xjtu.toolbox.error.FriendlyError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** [WebVpnInterceptor] 遇到网关拒绝页（200 的 HTML）要抛 [WebVpnDeniedException]，别的照常放行。 */
class WebVpnInterceptorDeniedTest {

    private val deniedBody = "<html><head><title>访问被拒绝</title></head><body>您没有访问该资源的权限，请联系管理员处理</body></html>"

    private fun clientReturning(contentType: String, body: String) = OkHttpClient.Builder()
        .addInterceptor(WebVpnInterceptor())
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", contentType)
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }
        .build()

    private fun fetch(client: OkHttpClient) =
        client.newCall(Request.Builder().url("https://rg.lib.xjtu.edu.cn/qspace").build()).execute()

    @Test
    fun 拦截器遇到拒绝页抛专门的异常() {
        try {
            fetch(clientReturning("text/html; charset=utf-8", deniedBody)).use { }
            fail("应该抛 WebVpnDeniedException")
        } catch (e: WebVpnDeniedException) {
            assertEquals(WebVpnDeniedException.MESSAGE, FriendlyError.of(e))
        }
    }

    @Test
    fun 拦截器放行正常页面和接口数据() {
        fetch(clientReturning("text/html", "<html>座位图</html>")).use { assertEquals(200, it.code) }
        // 业务接口返回的 JSON 里恰好出现这句话也不该被截
        fetch(clientReturning("application/json", """{"msg":"您没有访问该资源的权限"}""")).use { assertEquals(200, it.code) }
    }
}