package com.xjtu.toolbox.error

import com.xjtu.toolbox.auth.AccountSwitchedException
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LoginCooldownException
import com.xjtu.toolbox.auth.MfaCancelledException
import com.xjtu.toolbox.auth.MfaRequiredException
import com.xjtu.toolbox.auth.PasswordInvalidatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `:app` 侧认证异常的接线测试。 */
class AuthFailureWiringTest {

    @Test
    fun `认证失效异常走会话过期文案`() {
        assertTrue(AuthExpiredException("教务") is SessionExpiredFailure)
        assertEquals("登录已过期，请在「我的」中重新登录", FriendlyError.of(AuthExpiredException("教务")))
        assertEquals("登录已过期，请在「我的」中重新登录", FriendlyError.of(AuthExpiredException()))
    }

    @Test
    fun `自带中文提示的认证异常原样显示`() {
        // 声明成 List<Throwable>：不让编译器把类型推成「都实现了 UserFacingFailure」的交叉类型，
        // 否则下面两个 is 检查会被静态判定为恒真（那就不再是运行期接线测试了）
        val cases: List<Throwable> = listOf(
            PasswordInvalidatedException("教务"),
            MfaRequiredException("教务"),
            MfaCancelledException("教务"),
            AccountSwitchedException("教务"),
            LoginCooldownException("教务", 60),
        )
        for (e in cases) {
            assertTrue("${e::class.simpleName} 应实现 UserFacingFailure", e is UserFacingFailure)
            assertEquals(e.message, FriendlyError.of(e, "登录"))
            // 顺带盯住「它仍然是 IOException」：站点层的失败计数、冷却都建立在这条父类型上
            assertTrue("${e::class.simpleName} 必须仍是 java.io.IOException", e is java.io.IOException)
        }
    }
}
