package com.xjtu.toolbox.account

import android.content.Context
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.auth.AppLoginState
import com.xjtu.toolbox.auth.XJTULogin
import com.xjtu.toolbox.data.CredentialStore

/**
 * 用学校登记的身份校正账号类型。
 *
 * 之前这个开关只能用户自己在设置里选，选错的后果不是"显示不对"而是 CAS
 * 选身份时走错分支、一串子系统登不上，而普通用户根本没法判断自己该选哪个
 * （"我是直博生算研究生吗"）。既然一网通办已经把身份告诉我们了，就别再问。
 *
 * 识别不出来时什么都不做——保留用户原有设置，不用猜测覆盖已知。
 */
internal fun applyDetectedAccountType(
    context: Context,
    loginState: AppLoginState,
    identityTypeName: String?,
) {
    val detected = AccountType.fromIdentityName(identityTypeName) ?: return
    if (detected == loginState.accountType) return
    loginState.accountType = detected
    loginState.sessionManager?.accountType =
        if (detected == AccountType.POSTGRADUATE) {
            XJTULogin.AccountType.POSTGRADUATE
        } else {
            XJTULogin.AccountType.UNDERGRADUATE
        }
    CredentialStore(context).accountType = detected
    val id = loginState.accountId
    if (id.isNotEmpty()) {
        val store = AccountStore(context)
        store.get(id)?.let { store.upsert(it.copy(accountType = detected)) }
    }
}
