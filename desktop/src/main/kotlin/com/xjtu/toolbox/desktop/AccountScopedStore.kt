package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.account.AccountStore
import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore

/** 桌面端「按账号命名空间的 store 名」的唯一一处规则。 */
internal fun accountScopedStoreName(base: String, accountId: String? = AccountContext.activeAccountId): String =
    base + AccountContext.suffixFor(accountId)

private const val KEY_ADOPTED = "adopted_by_account"

/**
 * 无后缀的旧数据（下载记录、书架）：本机只有一个账号时只可能属于它，迁过去一次并在旧文件里记下；
 * 多个账号时归属不明，不读。旧文件本身保留。
 */
internal fun adoptLegacyRows(store: KeyValueStore, legacyName: String, key: String, accountId: String?): KeyValueStore {
    if (accountId.isNullOrEmpty() || store.getString(key) != null) return store
    val legacy = secureKeyValueStore(legacyName)
    if (legacy.getString(KEY_ADOPTED) != null) return store
    val rows = legacy.getString(key) ?: return store
    if (AccountStore().list().singleOrNull()?.accountId != accountId) return store
    store.putString(key, rows)
    legacy.putString(KEY_ADOPTED, accountId)
    return store
}
