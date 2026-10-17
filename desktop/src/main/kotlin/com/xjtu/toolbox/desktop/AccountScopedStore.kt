package com.xjtu.toolbox.desktop

import com.xjtu.toolbox.account.AccountContext

/** 桌面端「按账号命名空间的 store 名」的唯一一处规则。 */
internal fun accountScopedStoreName(base: String, accountId: String? = AccountContext.activeAccountId): String =
    base + AccountContext.suffixFor(accountId)
