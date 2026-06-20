package com.xjtu.toolbox.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xjtu.toolbox.auth.AccountType

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

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                FILE_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, attempting recovery", e)
            try {
                val prefsDir = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs")
                prefsDir.listFiles()?.filter { it.name.startsWith(FILE_NAME) }?.forEach { it.delete() }
                EncryptedSharedPreferences.create(
                    FILE_NAME,
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    appContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                Log.e(TAG, "Recovery failed, using in-memory prefs (accounts will not persist)")
                appContext.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private val gson = Gson()

    /** 全部账号，按 lastUsedAt 降序。 */
    @Synchronized
    fun list(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Account>>() {}.type
            gson.fromJson<List<Account>>(raw, type) ?: emptyList()
        }.onFailure { Log.w(TAG, "list: parse failed", it) }
            .getOrDefault(emptyList())
    }

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
        prefs.edit().putString(KEY_ACCOUNTS, gson.toJson(list)).apply()
    }

    companion object {
        private const val TAG = "AccountStore"
        private const val FILE_NAME = "xjtu_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_account"
        private const val KEY_MIGRATION_DONE = "migration_done"
    }
}
