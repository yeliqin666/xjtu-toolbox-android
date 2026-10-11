package com.xjtu.toolbox.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.xjtu.toolbox.auth.AccountType

/**
 * 多账号页要的那几件事 —— [AccountManagerScreen] 只认这条缝。
 *
 * ## 为什么是缝而不是把 `:app` 的 `AccountManager` 整个搬进来
 *
 * 「切换 / 新增 / 删除账号」的动作本体不是纯逻辑，它要**动会话内核**：
 * `SessionManager.reconfigureForAccount(suffix)`（把 backends 绑到这条账号的 cookie / 快照文件）、
 * `AccountContext.activeAccountId`、以及宿主侧的「这个账号的落盘文件在哪、要不要连缓存一起清」。
 * 前者在 `:data`，后两者正是**宿主能力**（`:app` 是 `Context` + Room + SharedPreferences + WebView 存储；
 * 桌面是一份 0600 的键值文件）。
 *
 * 所以按「能力」切这一刀：`：core` 留屏与交互（本文件那个接口），
 * `:app` 的 `AccountManager` 与 `:desktop` 的 `DesktopAccounts` 各实现一份 —— 与本仓库其余
 * 宿主面（`SettingsHost` / `VideoPlayerHost` / `FacultyScreen` 的 avatar 槽位）同一条口径。
 *
 * ## 语义债：方法名与搬迁前逐字相同
 *
 * `:app` 那份 `AccountManager` 本来就是这些方法（`accountList` / `switchTo` / `addAccount` /
 * `updatePassword` / `removeAccount`），本接口只是把它们**收成一条缝**，一个语义都没有改：
 * 屏上的文案、busy 状态、失败提示与搬迁前逐字一致。
 */
interface AccountsController {

    /** 全部已保存的账号（顺序由实现给；屏自己按「当前账号优先、最近使用优先」再排一次）。 */
    fun accountList(): List<Account>

    /**
     * 切换到目标账号。幂等：已是当前账号时只刷新 `lastUsedAt`。
     *
     * @return 切换后的账号；目标不存在返回 null。
     */
    suspend fun switchTo(accountId: String): Account?

    /**
     * 新增账号：先在目标账号的命名空间里探活登录（可能弹 MFA），**成功才落库**。
     * 失败要回滚到原激活账号，不留中间态。
     *
     * @return 成功是新增的 [Account]；失败是给用户看的错误（`FriendlyError` 会兜底成中文短句）。
     */
    suspend fun addAccount(username: String, password: String, accountType: AccountType): Result<Account>

    /** 更新账号密码（用户在设置页改密后调用）。 */
    fun updatePassword(accountId: String, newPassword: String)

    /**
     * 删除账号：清它的凭据与会话（要不要连本地缓存一起清是 [deleteCache]）。
     *
     * @return 账号本来就不存在时返回 false。
     */
    suspend fun removeAccount(accountId: String, deleteCache: Boolean): Boolean
}

/**
 * 账号头像的**照片那一格**（缝）。
 *
 * `:app` 传学工系统的证件照缓存（`HelloProfileStore.cachedAvatarFor`，要 `Context` 与
 * `android.graphics.Bitmap`）；桌面端不传 —— 那一档退回首字母圆（画法本来就在屏里，
 * 与 `FacultyScreen` 的 `avatar` 槽位同一条口径：**不装样子，也不假装没图就坏了**）。
 *
 * @return 没登录过学工系统（或本端没有那份缓存）时返回 null。
 */
fun interface AccountPhotoSource {
    @Composable
    fun photo(accountId: String): ImageBitmap?
}
