package com.xjtu.toolbox.account

import com.xjtu.toolbox.auth.AccountType

import kotlin.concurrent.Volatile

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
    // 原来这里是 `@Volatile @JvmField`。`@JvmField` 属于 `kotlin.jvm`——它在 JVM 上是**默认
    // 导入**，所以文件里根本没有 import 行，任何基于 import 的启发式都看不见它，只有把
    // commonMain 真的编一遍才会红（这就是 CI 门禁的价值）。
    // 仓库里没有任何 Java 代码引用这两个字段（Java 只出现在 game/xiangqi/rules），也没有
    // 反射/Gson 碰它们，所以 `@JvmField` 直接去掉；`@Volatile` 换成 kotlin.concurrent.Volatile，
    // 跨线程可见性的语义不变。
    @Volatile
    var activeAccountId: String? = null

    /** 每切一次账号命名空间加一（见 SessionManager.reconfigureForAccount）；进行中的登录据此发现账号已经换了。 */
    @Volatile
    var switchEpoch: Long = 0L

    /**
     * 当前激活账号的身份（本科 / 研究生）。与 [activeAccountId] 由**同一处**、同一时刻写入。
     *
     * 为什么需要它：路由表（`nav/AppRoute`）要判断「研究生评教由页面自己登录、本科生先登教务」，
     * 而那正是「路由属于共享层、但当前账号身份属于宿主」的矛盾。这个值以前只存在于 :app 的
     * `SessionManager.accountType`（一个老的嵌套枚举）里，路由因此搬不进 :core。
     *
     * 写入点与 [activeAccountId] 对齐：`AppLoginState.loadIdentityFromAccount`（切账号/启动恢复，
     * 那里同时写 `accountType = account.accountType`）、`AccountTypeDetection`（一网通办回来纠正身份）、
     * `notification/HeadlessSessions`（后台起临时会话时）。
     *
     * ⚠️ 退出登录时**不**重置（与搬迁前的 `SessionManager.accountType` 行为一致：它同样保留最后
     * 一个身份）—— 如果要改，两边得一起改，否则「研究生」在退出后会突然走本科分支。
     */
    @Volatile
    var activeAccountType: AccountType = AccountType.UNDERGRADUATE

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
