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

/**
 * `FriendlyError` 搬到 :core 之后，**:app 侧的接线测试**：真实的认证异常类必须认领 :core 的
 * 标记接口，串起来之后的文案与搬迁前逐字一致。
 *
 * 为什么这条测试必须在 :app：那 6 个异常类留在 :app —— 它们继承 `java.io.IOException` 是有语义的
 * （站点层按 IOException 记「本次登录失败、进冷却」），所以「谁实现了哪个标记」这件事只有
 * :app 能看见。:core 的策略测试用的是同构的假实现（`core/src/jvmTest/.../FriendlyErrorTest.kt`）。
 *
 * 这一条同时是交接文档 §1.1 那类事故的探针：跨模块搬迁后，只有「真实类型 + 真实策略」串一遍
 * 才能发现编译期解析到新类、运行期找不到旧方法的错位。
 */
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
