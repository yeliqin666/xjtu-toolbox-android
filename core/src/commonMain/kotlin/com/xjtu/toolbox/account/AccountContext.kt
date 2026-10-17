package com.xjtu.toolbox.account

import com.xjtu.toolbox.auth.AccountType

import kotlin.concurrent.Volatile

/** 进程级账号上下文。 */
object AccountContext {
    @Volatile
    var activeAccountId: String? = null

    /** 每切一次账号命名空间加一（见 SessionManager.reconfigureForAccount）；进行中的登录据此发现账号已经换了。 */
    @Volatile
    var switchEpoch: Long = 0L

    /** 当前激活账号的身份（本科 / 研究生）。 */
    @Volatile
    var activeAccountType: AccountType = AccountType.UNDERGRADUATE

    /** 用于文件名/SharedPreferences 名的安全化账号后缀（当前激活账号）。 */
    fun safeSuffix(): String = suffixFor(activeAccountId)

    /** 指定账号的后缀。 */
    fun suffixFor(accountId: String?): String {
        val id = accountId ?: return "default"
        return "_" + id.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
