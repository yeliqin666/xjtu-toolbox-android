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
 * 线程安全：所有实例共用一把锁，整个账号表的读改写必须在锁内完成。
 *
 * ⚠️ 文件名与键名一个都不能改，改了所有人要重登（与 `secureKeyValueStore` 那条红线同源）。
 */
class AccountStore {
    private val prefs: KeyValueStore by lazy { secureKeyValueStore(FILE_NAME) }
    /** 全部账号。 */
    fun list(): List<Account> = synchronized(lock) {
        decodeAccounts(prefs.getString(KEY_ACCOUNTS) ?: return@synchronized emptyList())
    }

    fun get(accountId: String): Account? = synchronized(lock) {
        list().firstOrNull { it.accountId == accountId }
    }

    fun activeAccountId(): String? = synchronized(lock) { prefs.getString(KEY_ACTIVE) }

    fun activeAccount(): Account? = synchronized(lock) { activeAccountId()?.let { get(it) } }

    /** upsert 一个账号（按 accountId 去重），并可选设为激活。 */
    fun upsert(account: Account, setActive: Boolean = false): Unit = synchronized(lock) {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.accountId == account.accountId }
        if (idx >= 0) list[idx] = account else list.add(account)
        writeAccounts(list)
        if (setActive) setActive(account.accountId)
    }

    fun setActive(accountId: String) = synchronized(lock) {
        prefs.putString(KEY_ACTIVE, accountId)
    }

    fun clearActive() = synchronized(lock) { prefs.remove(KEY_ACTIVE) }

    fun remove(accountId: String): Unit = synchronized(lock) {
        val list = list().filterNot { it.accountId == accountId }
        writeAccounts(list)
        if (activeAccountId() == accountId) clearActive()
    }

    /** 更新单个账号的局部字段，返回更新后的账号。 */
    fun update(accountId: String, block: (Account) -> Account): Account? = synchronized(lock) {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.accountId == accountId }
        if (idx < 0) return@synchronized null
        val updated = block(list[idx])
        list[idx] = updated
        writeAccounts(list)
        updated
    }

    var migrationDone: Boolean
        get() = synchronized(lock) { prefs.getBoolean(KEY_MIGRATION_DONE, false) }
        set(value) = synchronized(lock) { prefs.putBoolean(KEY_MIGRATION_DONE, value) }

    fun isEmpty(): Boolean = list().isEmpty()

    private fun writeAccounts(list: List<Account>) {
        prefs.putString(KEY_ACCOUNTS, AppJson.encodeToString(list))
    }

    companion object {
        private val lock = Any()

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