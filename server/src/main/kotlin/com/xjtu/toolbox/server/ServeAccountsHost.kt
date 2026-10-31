package com.xjtu.toolbox.server

import com.xjtu.toolbox.account.Account
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.SessionBackend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `/api/accounts` 四条**写**端点的动作本体（`docs/api-contract.md` §5.10，
 * `docs/parity-ledger.md` §6.1 #10 = 丙）。
 *
 * 为什么单独一个类：路由只该管「HTTP 形状 + 拒绝条件」，账号表怎么动、当前会话要不要跟着换，
 * 全在这一层。四条动作共享一个 [Mutex]（并发进来会互相踩：新增/切换/删除都在动同一张表 + 当前会话）。
 *
 * ## 幂等口径（选的是「目标态」而不是幂等键）
 *
 * 没有 `Idempotency-Key` 头，也不记请求号。理由：这四个动作的目标态天然唯一（「当前账号是它」/
 * 「它在表里」/「它不在表里」/「它的密码是新的」），重复提交必然落在同一个目标态上，于是按目标态判：
 *
 *  - 成功 = 目标态成立（[AccountsWriteOutcome.Done]），失败 = 够不到目标态；
 *  - `changed` 说这次到底改了没改：**重复提交 → `ok:true` + `changed:false`**，客户端可以据此提示「本来就是」；
 *  - 拒绝仍是 `ok:false`：够不到目标态的重放（比如删一个不存在的账号是「本来就是」，而切到不存在的账号没有目标态）；
 *  - 「不产生两份账号」落在 [add]：同一个 accountId 再提交一次只会命中已有那一行，**不写第二行**；
 *  - 「不重复删除」落在 [remove]：第二次删除也是 `ok:true` + `changed:false`，不会二次动盘、不会二次清缓存。
 *
 * ## 两条红线
 *
 *  - 密码只在请求体里过一遍：不落日志、不回显、不进 message（message 里只有打码 id）。
 *  - 落盘凭据复用现有那一套（`xjtu_credentials` + `xjtu_accounts`，JVM 上走 DPAPI/0600），
 *    本类不自己开存储、不自己加密。
 */
internal class ServeAccountsHost(
    private val session: ServeSession,
    /** 账号表（注入只为测试用同一个实例；生产默认就是那份全局存储）。 */
    private val store: AccountStore = AccountStore(),
    /** 取当前时间（测试里好固定）。 */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 四条写动作串行：切换会把当前会话整个换掉，删除会清缓存，并发进来分不清谁先谁后。 */
    private val mutex = Mutex()

    /** 切换当前账号：**只认 id，不收密码**（要新账号请先 [add]）。 */
    suspend fun switchTo(rawId: String): AccountsWriteOutcome = mutex.withLock {
        val account = when (val ref = resolve(rawId)) {
            is AccountRef.Found -> ref.account
            is AccountRef.Missing -> return@withLock AccountsWriteOutcome.Failed(
                ApiErrors.NOT_FOUND,
                "账号表里没有 ${maskedAccountId(rawId)}：新增用 POST $ACCOUNT_PATH/add",
            )
            is AccountRef.Ambiguous -> return@withLock ambiguous(ref.id)
        }
        val already = AccountContext.activeAccountId == account.accountId
        // 已经是它就不重建会话（切账号会把 cookie / 站点会话清一遍，白切一次 = 全站重新登录）
        if (!already) session.adoptAccount(account)
        store.update(account.accountId) { it.copy(lastUsedAt = now()) }
        AccountsWriteOutcome.Done(
            changed = !already,
            message = if (already) {
                "已经是当前账号（只刷新了上次使用时间）"
            } else {
                "已切换到 ${maskedAccountId(account.accountId)}"
            },
        )
    }

    /**
     * 新增账号。
     *
     * ⚠️ **本条不联网探活**：serve 这一侧的站点会话是懒的（第一次真取数时才 ensure），所以密码打错
     * 不会在这一刻被拦下，而会在下一次取数时报 401/502。取舍见 `docs/api-contract.md` §5.10 的说明：
     * 写端点要能在没有副作用的情况下被重复提交，而`探测一次登录`会顺带触发短信二验（150s 的挂起请求）。
     *
     * [activate] 为真时顺手认成当前账号（与 `:desktop` 的 `addAccount` 同一条路径）；默认不动当前会话。
     */
    suspend fun add(
        username: String,
        password: String,
        accountType: AccountType,
        activate: Boolean,
    ): AccountsWriteOutcome = mutex.withLock {
        val id = username.trim()
        if (id.isEmpty()) return@withLock AccountsWriteOutcome.Failed(ApiErrors.BAD_REQUEST, "学号/手机号不能为空")
        if (password.isEmpty()) return@withLock AccountsWriteOutcome.Failed(ApiErrors.BAD_REQUEST, "密码不能为空")
        val masked = maskedAccountId(id)

        val existing = store.get(id)
        if (existing != null) {
            return@withLock if (existing.password == password) {
                // 幂等：同一条再提交一次就是「它已经在表里」，不写第二行、也不改 lastUsedAt
                AccountsWriteOutcome.Done(false, "$masked 已经在账号表里了（这次没有产生第二行）")
            } else {
                AccountsWriteOutcome.Failed(
                    ApiErrors.ACTION_REJECTED,
                    "$masked 已经在账号表里，但密码不同：改密请用 POST $ACCOUNT_PATH/password",
                )
            }
        }

        val account = Account(accountId = id, password = password, accountType = accountType, lastUsedAt = now())
        // setActive = false：激活指针（active_account）是桌面端的主键，serve 不写（契约 §5.10）
        store.upsert(account, setActive = false)
        if (activate) session.adoptAccount(account)
        AccountsWriteOutcome.Done(
            changed = true,
            message = if (activate) "已新增 $masked 并切换过去" else "已新增 $masked（未切换；要切用 POST $ACCOUNT_PATH/switch）",
        )
    }

    /**
     * 删除账号：**凭据 + 会话缓存 + 账号表那一行**一起清（二次确认由路由层把关 `confirm`）。
     *
     * 删掉的正是当前账号 ⇒ 走 [ServeSession.logout]（先把会话放掉再删，否则 debounce 的写盘会把 TGC
     * 写回刚清掉的文件），serve 回到未登录；客户端要接着用别的账号请再发一发 [switchTo]。
     *
     * 删掉的不是当前账号 ⇒ 只清它自己命名空间的 cookie + 站点快照（[SessionBackend.wipe]）。
     *
     * 幂等：账号本来就不在表里 ⇒ `ok:true` + `changed:false`（目标态「它不在表里」已经成立），不二次删。
     */
    suspend fun remove(rawId: String): AccountsWriteOutcome = mutex.withLock {
        val account = when (val ref = resolve(rawId)) {
            is AccountRef.Found -> ref.account
            is AccountRef.Missing ->
                return@withLock AccountsWriteOutcome.Done(false, "${maskedAccountId(rawId)} 本来就不在账号表里（没有可删的）")
            is AccountRef.Ambiguous -> return@withLock ambiguous(ref.id)
        }
        val masked = maskedAccountId(account.accountId)
        val wasActive = AccountContext.activeAccountId == account.accountId
        if (wasActive) {
            session.logout()
        } else {
            runCatching { SessionBackend.wipe(AccountContext.suffixFor(account.accountId)) }
        }
        store.remove(account.accountId)
        AccountsWriteOutcome.Done(
            changed = true,
            message = if (wasActive) {
                "已删除 $masked，serve 现在没有登录（要换账号请 POST $ACCOUNT_PATH/switch）"
            } else {
                "已删除 $masked 及其会话缓存"
            },
        )
    }

    /**
     * 改密：只改账号表里已经存在的那一条（新增请走 [add]）。
     *
     * 改的正是当前账号时，内存会话与落盘那份凭据一起换（见 [ServeSession.rekeyAccount]）；否则只动表。
     * 幂等：新密码与旧的一样 ⇒ `ok:true` + `changed:false`，不写盘。
     */
    suspend fun updatePassword(rawId: String, newPassword: String): AccountsWriteOutcome = mutex.withLock {
        val account = when (val ref = resolve(rawId)) {
            is AccountRef.Found -> ref.account
            is AccountRef.Missing -> return@withLock AccountsWriteOutcome.Failed(
                ApiErrors.NOT_FOUND,
                "账号表里没有 ${maskedAccountId(rawId)}：改密只改已记下的账号，新增请用 POST $ACCOUNT_PATH/add",
            )
            is AccountRef.Ambiguous -> return@withLock ambiguous(ref.id)
        }
        if (newPassword.isEmpty()) return@withLock AccountsWriteOutcome.Failed(ApiErrors.BAD_REQUEST, "新密码不能为空")
        val masked = maskedAccountId(account.accountId)
        if (account.password == newPassword) {
            return@withLock AccountsWriteOutcome.Done(false, "$masked 的密码本来就是这个（没有改动）")
        }

        store.update(account.accountId) { it.copy(password = newPassword) }
        if (AccountContext.activeAccountId == account.accountId) session.rekeyAccount(account.accountId, newPassword)
        AccountsWriteOutcome.Done(
            changed = true,
            message = "已更新 $masked 的密码" + if (AccountContext.activeAccountId == account.accountId) "（当前会话已换用新密码）" else "",
        )
    }

    /** 客户端手上是打码 id（`GET` 给的就是它），所以先按原样认一遍，再按打码形式认。 */
    private fun resolve(raw: String): AccountRef {
        val id = raw.trim()
        if (id.isEmpty()) return AccountRef.Missing(id)
        store.get(id)?.let { return AccountRef.Found(it) }
        val matches = store.list().filter { maskedAccountId(it.accountId) == id }
        return when (matches.size) {
            1 -> AccountRef.Found(matches.single())
            0 -> AccountRef.Missing(id)
            else -> AccountRef.Ambiguous(id)
        }
    }

    /** 打码 id 撞车（`2023****0001` 同时指向两条）⇒ 409，让调用方改用完整学号，而不是猜一条。 */
    private fun ambiguous(id: String) = AccountsWriteOutcome.Failed(
        ApiErrors.ACTION_REJECTED,
        "$id 是打码后的形式、对上了不止一个账号：请改用完整学号/手机号",
    )
}

/** [ServeAccountsHost] 的一次动作结果：路由层只认「够到了目标态没有」+「改了什么没有」+「给人看的一句话」。 */
internal sealed interface AccountsWriteOutcome {

    /** 这次到底改了没改：重复提交（目标态本来就成立）为 `false`。 */
    val changed: Boolean

    /** 给人看的中文短句（**不含**学号原文，只有打码 id）。 */
    val message: String

    /** 目标态已成立（幂等重放也算成功）。 */
    data class Done(override val changed: Boolean, override val message: String) : AccountsWriteOutcome

    /** 够不到目标态：[code] 与信封里的 `code` 同一个约定（= HTTP 状态码）。 */
    data class Failed(val code: Int, override val message: String) : AccountsWriteOutcome {
        override val changed: Boolean get() = false
    }
}

/** [ServeAccountsHost.resolve] 的三种答案。 */
private sealed interface AccountRef {
    data class Found(val account: Account) : AccountRef

    data class Missing(val id: String) : AccountRef

    data class Ambiguous(val id: String) : AccountRef
}
