package com.xjtu.toolbox.account

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.platform.secureKeyValueStore
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * 多账号持久化存储。
 *
 * 单一密文键值存储 `xjtu_accounts` 内存放：
 *  - `accounts`        JSON 数组，全部 [Account]
 *  - `active_account`  当前激活 accountId
 *  - `migration_done`  旧单账号数据迁移是否已完成（幂等防重入）
 *
 * 线程安全：读写均 synchronized(this)。
 *
 * ⚠️ 文件名与键名一个都不能改，改了所有人要重登（与 `secureKeyValueStore` 那条红线同源）。
 */
class AccountStore {
    private val prefs: KeyValueStore by lazy { secureKeyValueStore(FILE_NAME) }
    /** 全部账号。 */
    @Synchronized
    fun list(): List<Account> = decodeAccounts(prefs.getString(KEY_ACCOUNTS) ?: return emptyList())

    @Synchronized
    fun get(accountId: String): Account? = list().firstOrNull { it.accountId == accountId }

    @Synchronized
    fun activeAccountId(): String? = prefs.getString(KEY_ACTIVE)

    @Synchronized
    fun activeAccount(): Account? = activeAccountId()?.let { get(it) }

    /** upsert 一个账号（按 accountId 去重），并可选设为激活。 */
    @Synchronized
    fun upsert(account: Account, setActive: Boolean = false) {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.accountId == account.accountId }
        if (idx >= 0) list[idx] = account else list.add(account)
        writeAccounts(list)
        if (setActive) setActive(account.accountId)
    }

    @Synchronized
    fun setActive(accountId: String) {
        prefs.putString(KEY_ACTIVE, accountId)
    }

    @Synchronized
    fun clearActive() {
        prefs.remove(KEY_ACTIVE)
    }

    @Synchronized
    fun remove(accountId: String) {
        val list = list().filterNot { it.accountId == accountId }
        writeAccounts(list)
        if (activeAccountId() == accountId) clearActive()
    }

    /** 更新单个账号的局部字段，返回更新后的账号。 */
    @Synchronized
    fun update(accountId: String, block: (Account) -> Account): Account? {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.accountId == accountId }
        if (idx < 0) return null
        val updated = block(list[idx])
        list[idx] = updated
        writeAccounts(list)
        return updated
    }

    var migrationDone: Boolean
        @Synchronized get() = prefs.getBoolean(KEY_MIGRATION_DONE, false)
        @Synchronized set(value) { prefs.putBoolean(KEY_MIGRATION_DONE, value) }

    @Synchronized
    fun isEmpty(): Boolean = list().isEmpty()

    @Synchronized
    private fun writeAccounts(list: List<Account>) {
        prefs.putString(KEY_ACCOUNTS, AppJson.encodeToString(list))
    }

    companion object {
        /** 逐条解码：某一条坏了只丢那一条，缺学号的条目没法用也丢掉。 */
        internal fun decodeAccounts(raw: String): List<Account> =
            runCatching { AppJson.parseToJsonElement(raw).jsonArray }
                .onFailure { Log.w(TAG, "list: parse failed", it) }
                .getOrNull().orEmpty()
                .mapNotNull { el -> runCatching { AppJson.decodeFromJsonElement<Account>(el) }.getOrNull() }
                .filter { it.accountId.isNotBlank() }

        private const val TAG = "AccountStore"
        /** 密文存储的文件名。 */
        const val FILE_NAME = "xjtu_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_account"
        private const val KEY_MIGRATION_DONE = "migration_done"
    }
}

/**
 * 小组件、后台广播拉起的进程不经过界面，[AccountContext.activeAccountId] 还是 null，读到的是匿名空间。
 * 这类入口先调它从 [AccountStore] 恢复激活账号。
 */
fun AccountContext.restoreIfNeeded() {
    if (activeAccountId != null) return
    runCatching { AccountStore().activeAccountId()?.let { activeAccountId = it } }
}