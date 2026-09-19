package com.xjtu.toolbox.account

/**
 * 进程级账号上下文。持有当前激活账号的 [accountId]（= 学号 / 手机号）。
 *
 * 供所有按账号命名空间的存储（DataCache、AgentSessionStore、
 * Room 查询的 accountId 过滤、校园卡缓存等）在每次 IO 时解析路径/key，
 * 从而做到「切换账号即切目录」，无需重建各 Store 实例。
 *
 * 由 [AccountManager.switchTo] 在切换账号的瞬间原子写入。
 * 值为 null 表示尚未登录或处于登出中间态——此时各 Store 应回退到默认（旧/匿名）路径，
 * 避免误把新账号数据写入默认空间。
 */
object AccountContext {
    @Volatile
    @JvmField
    var activeAccountId: String? = null

    /** 用于文件名/SharedPreferences 名的安全化账号后缀（当前激活账号）。 */
    fun safeSuffix(): String = suffixFor(activeAccountId)

    /**
     * 指定账号的后缀。异步任务应在**发起时**用它（或捕获 [activeAccountId]）定下命名空间，
     * 而不是在结果回来时再读 [safeSuffix]——中途切了账号，结果就写进了别人的目录。
     */
    fun suffixFor(accountId: String?): String {
        val id = accountId ?: return "default"
        return "_" + id.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
