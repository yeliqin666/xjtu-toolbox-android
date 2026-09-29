package com.xjtu.toolbox.error

import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.PasswordInvalidatedException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FriendlyErrorTest {

    private val expired = "登录已过期，请在「我的」中重新登录"

    @Test
    fun `按异常类型分类`() {
        assertEquals(expired, FriendlyError.of(AuthExpiredException("教务")))
        assertEquals("无法连接到服务器，请检查网络", FriendlyError.of(UnknownHostException("jwxt.xjtu.edu.cn")))
        assertEquals("请求超时，请稍后重试", FriendlyError.of(SocketTimeoutException("timeout")))
        assertEquals("查询成绩失败：网络异常，请稍后重试", FriendlyError.of(IOException("Connection reset"), "查询成绩"))
    }

    @Test
    fun `应用自己写的中文提示原样给用户`() {
        assertEquals("账号或密码无效", FriendlyError.of(PasswordInvalidatedException("教务")))
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
        assertThrows(CancellationException::class.java) { FriendlyError.of(CancellationException("cancel")) }
    }
}
