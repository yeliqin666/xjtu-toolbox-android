package com.xjtu.toolbox.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.xjtu.toolbox.auth.AccountType

/** 多账号页要的那几件事 —— [AccountManagerScreen] 只认这条缝。 */
interface AccountsController {

    /** 全部已保存的账号（顺序由实现给；屏自己按「当前账号优先、最近使用优先」再排一次）。 */
    fun accountList(): List<Account>

    /** 切换到目标账号。 */
    suspend fun switchTo(accountId: String): Account?

    /** 新增账号：先在目标账号的命名空间里探活登录（可能弹 MFA），成功才落库。 */
    suspend fun addAccount(username: String, password: String, accountType: AccountType): Result<Account>

    /** 更新账号密码（用户在设置页改密后调用）。 */
    fun updatePassword(accountId: String, newPassword: String)

    /** 删除账号：清它的凭据与会话（要不要连本地缓存一起清是 [deleteCache]）。 */
    suspend fun removeAccount(accountId: String, deleteCache: Boolean): Boolean
}

/** 账号头像的照片那一格（缝）。 */
fun interface AccountPhotoSource {
    @Composable
    fun photo(accountId: String): ImageBitmap?
}
