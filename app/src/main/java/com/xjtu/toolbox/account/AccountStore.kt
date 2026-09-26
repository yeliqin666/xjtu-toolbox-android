package com.xjtu.toolbox.account

import kotlinx.serialization.json.decodeFromJsonElement
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xjtu.toolbox.data.SecurePrefs
import com.xjtu.toolbox.util.AppJson
import kotlinx.serialization.json.jsonArray

/**
 * 多账号持久化存储。
 *
 * 单一 EncryptedSharedPreferences 文件 `xjtu_accounts` 内存放：
 *  - `accounts`        JSON 数组，全部 [Account]
 *  - `active_account`  当前激活 accountId
 *  - `migration_done`  旧单账号数据迁移是否已完成（幂等防重入）
 *
 * 线程安全：读写均 synchronized(this)。
 */
class AccountStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { SecurePrefs.open(appContext, FILE_NAME) }

    /** 全部账号。 */
    @Synchronized
    fun list(): List<Account> = decodeAccounts(prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList())

    @Synchronized
    fun get(accountId: String): Account? = list().firstOrNull { it.accountId == accountId }

    @Synchronized
    fun activeAccountId(): String? = prefs.getString(KEY_ACTIVE, null)

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
        prefs.edit().putString(KEY_ACTIVE, accountId).apply()
    }

    @Synchronized
    fun clearActive() {
        prefs.edit().remove(KEY_ACTIVE).apply()
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
        @Synchronized set(value) { prefs.edit().putBoolean(KEY_MIGRATION_DONE, value).apply() }

    @Synchronized
    fun isEmpty(): Boolean = list().isEmpty()

    @Synchronized
    private fun writeAccounts(list: List<Account>) {
        prefs.edit().putString(KEY_ACCOUNTS, AppJson.encodeToString(list)).apply()
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
        internal const val FILE_NAME = "xjtu_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_account"
        private const val KEY_MIGRATION_DONE = "migration_done"
    }
}
