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
 * ## 本轮为什么从 `:app` 搬到 `:data`
 *
 * 它是多账号的**持久化**那一半（列表 / 激活指针 / upsert / remove），与「谁来登、谁来切会话」
 * 无关，本来就只需要一个键值存储。搬迁前它拿 `Context` 去开 `SecurePrefs`——那一步换成了
 * `:data` 自己的密文键值缝 `secureKeyValueStore`（**Android 上的 actual 就是同一个 `SecurePrefs`、
 * 同一个 `xjtu_accounts` 文件名、同一批键** ⇒ 老用户的账号列表与激活指针一个字不变；桌面上
 * 那份落在一个 0600 的文件里）。于是 `:desktop` 也能用这同一份实现。
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
        /**
         * 密文存储的文件名。**是存储契约的一部分**：Android 侧的 actual 就是
         * `SecurePrefs.open(context, "xjtu_accounts")`，`XjtuApp.attachBaseContext` 靠这个常量
         * 在首帧前 `SecurePrefs.prewarm` 它（换一个字符，老用户的账号列表就找不到了）。
         */
        const val FILE_NAME = "xjtu_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_account"
        private const val KEY_MIGRATION_DONE = "migration_done"
    }
}
