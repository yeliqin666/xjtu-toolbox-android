package com.xjtu.toolbox.error

import com.xjtu.toolbox.platform.NetworkFailure
import com.xjtu.toolbox.platform.classifyNetworkFailure
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * `FriendlyError` 的**策略**测试：类型分类、状态码、中文短句、兜底。
 *
 * 原文件在 `app/src/test/java/com/xjtu/toolbox/error/FriendlyErrorTest.kt`，随 `FriendlyError`
 * 一起搬进 :core —— 断言逐条保留，只换了壳（JUnit → kotlin.test、
 * `assertThrows` → `assertFailsWith`）。
 *
 * 两个刻意的差异：
 *  1. 放在 `jvmTest` 而不是 `commonTest`：要真的构造 `java.net.*` / `javax.net.ssl.*` 异常，
 *     那是这一族在 JVM 侧的取值来源（wasmJs 侧如实返回 null，见 `platform/NetworkFailure.wasmJs.kt`）。
 *  2. :app 的认证异常类换成了本文件里的假实现（:core 不能依赖 :app）：会话过期与「自带中文提示」
 *     这两种归类由标记接口表达，真实类是否认领它们另有 :app 的接线测试
 *     （`app/src/test/.../error/AuthFailureWiringTest.kt`）盯着。
 */

/** 替代 :app 的 `AuthExpiredException`：只保留「会话过期」这一条契约。 */
private class FakeSessionExpired : IOException("登录态已失效"), SessionExpiredFailure

/** 替代 :app 那几个「消息就是给用户看的中文短句」的认证异常。继承 IOException 是为了盯住分支顺序。 */
private class FakeUserFacing(message: String) : IOException(message), UserFacingFailure

class FriendlyErrorTest {

    private val expired = "登录已过期，请在「我的」中重新登录"

    @Test
    fun `按异常类型分类`() {
        assertEquals(expired, FriendlyError.of(FakeSessionExpired()))
        assertEquals("无法连接到服务器，请检查网络", FriendlyError.of(UnknownHostException("jwxt.xjtu.edu.cn")))
        assertEquals("请求超时，请稍后重试", FriendlyError.of(SocketTimeoutException("timeout")))
        assertEquals("无法连接到服务器，请检查网络或 VPN 设置", FriendlyError.of(ConnectException("Connection refused")))
        assertEquals("安全连接失败，请检查网络环境（VPN/代理）", FriendlyError.of(SSLException("handshake failed")))
        assertEquals("查询成绩失败：网络异常，请稍后重试", FriendlyError.of(IOException("Connection reset"), "查询成绩"))
    }

    @Test
    fun `应用自己写的中文提示原样给用户`() {
        assertEquals("账号或密码无效", FriendlyError.of(FakeUserFacing("账号或密码无效")))
        assertEquals("该账号已存在", FriendlyError.of(IllegalArgumentException("该账号已存在")))
    }

    @Test
    fun `按 HTTP 状态码分类`() {
        assertEquals(expired, FriendlyError.of(RuntimeException("xscjcx.do HTTP 401")))
        assertEquals("服务器开小差了，请稍后再试", FriendlyError.of(RuntimeException("楼层信息加载失败: HTTP 502")))
        assertEquals("服务响应超时，请稍后再试", FriendlyError.of(RuntimeException("HTTP 504: Gateway Timeout")))
        assertEquals("服务暂不可用，请稍后再试", FriendlyError.of(IOException("HTTP 404")))
    }

    @Test
    fun `消息里恰好带关键字不再误判`() {
        // 以前只要消息里有 token / 401 / 404 / gateway 就会被当成登录过期或服务故障
        assertNotEquals(expired, FriendlyError.of(RuntimeException("invalid token in json"), "解析数据"))
        assertEquals("解析数据失败，请稍后重试", FriendlyError.of(RuntimeException("invalid token in json"), "解析数据"))
        assertEquals("解析数据失败，请稍后重试", FriendlyError.of(RuntimeException("payment gateway offline"), "解析数据"))
        assertEquals("课程编号 4041 已存在", FriendlyError.of(RuntimeException("课程编号 4041 已存在")))
    }

    @Test
    fun `HTTP 200 不是错误状态`() {
        assertNull(FriendlyError.forStatus(200))
        assertNull(FriendlyError.forStatus(302))
    }

    @Test
    fun `取消原样抛出`() {
        assertFailsWith<CancellationException> { FriendlyError.of(CancellationException("cancel")) }
    }

    /**
     * 分支顺序的守卫：自带中文提示的异常**也是** IOException，先被标记接口接走，
     * 不能因为「它是个 IO 失败」就变成「网络异常」。
     */
    @Test
    fun `标记接口优先于网络分类`() {
        val e = FakeUserFacing("账号或密码无效")
        assertEquals(NetworkFailure.IO, classifyNetworkFailure(e)) // 分类层照旧认它是 IO 失败
        assertEquals("账号或密码无效", FriendlyError.of(e, "查询成绩")) // 但文案由标记接口决定
    }

    /** 平台家族本身的取值：顺序即优先级（`SocketTimeoutException` 也是 `IOException`）。 */
    @Test
    fun `网络失败分类逐档对应，认不出返回 null`() {
        assertEquals(NetworkFailure.NO_HOST, classifyNetworkFailure(UnknownHostException("jwxt.xjtu.edu.cn")))
        assertEquals(NetworkFailure.TIMEOUT, classifyNetworkFailure(SocketTimeoutException("read timed out")))
        assertEquals(NetworkFailure.REFUSED, classifyNetworkFailure(ConnectException("refused")))
        assertEquals(NetworkFailure.TLS, classifyNetworkFailure(SSLException("tls")))
        // okhttp 实际的 TLS 失败类型（SSLHandshakeException extends SSLException）
        assertEquals(NetworkFailure.TLS, classifyNetworkFailure(SSLHandshakeException("handshake failed")))
        assertEquals(NetworkFailure.IO, classifyNetworkFailure(IOException("Connection reset")))
        assertNull(classifyNetworkFailure(IllegalStateException("business failure")))
        assertNull(classifyNetworkFailure(RuntimeException("解析失败")))
    }
}
