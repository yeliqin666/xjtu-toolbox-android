package com.xjtu.toolbox.auth

import com.xjtu.toolbox.account.AccountContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SiteSessionAccountSwitchTest {

    /** 登录进行到一半时切了账号：[runLogin] 里拿到的是旧账号的 token。 */
    private class SwitchMidLogin(private val failWith: IOException? = null) : SiteSession("t", "测试站") {
        override suspend fun runLogin(username: String, password: String) {
            localToken["token"] = "旧账号的 token"
            AccountContext.switchEpoch++
            failWith?.let { throw it }
        }
    }

    @Test
    fun `登录途中切了账号，这次登录作废且不留旧账号的 token`() = runBlocking {
        val site = SwitchMidLogin()
        try {
            site.ensureLogin("a", "p")
            fail("应当抛出 AccountSwitchedException")
        } catch (_: AccountSwitchedException) {
        }
        assertFalse(site.hasLogin)
        assertTrue(site.localToken.isEmpty())
    }

    @Test
    fun `旧账号密码错不会当成新账号密码错`() = runBlocking {
        val site = SwitchMidLogin(PasswordInvalidatedException("测试站"))
        try {
            site.ensureLogin("a", "p")
            fail("应当抛出 AccountSwitchedException")
        } catch (_: AccountSwitchedException) {
        }
    }
}
