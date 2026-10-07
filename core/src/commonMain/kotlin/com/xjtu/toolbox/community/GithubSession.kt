package com.xjtu.toolbox.community

import kotlinx.coroutines.flow.StateFlow

/**
 * 社区登录态（**契约在 :core，存储留在 :app**）。
 *
 * 描述的是界面真正需要的那几件事：登录名（未登录为 null）、带 token 的取数实现、
 * 登录 / 退出。token 本身怎么存（:app 用 Android 加密偏好 `SecurePrefs`）不属于这一层，
 * 所以这里没有 `token()`。
 *
 * 实现是 :app 的 `PrefsGithubSession`（`github_community` 那份加密偏好 + 单例）。
 * 搬这一层的原因是社区那三屏要用它：`CommunityScreen` 原本从 `LocalContext` 自己 `get(context)`，
 * 而那正是「界面直接摸平台」——搬进 commonMain 后由 :app 的导航层注入。
 */
interface GithubSession {
    /** 已登录的 GitHub 用户名；null 表示未登录。 */
    val login: StateFlow<String?>

    /** 带登录 token 的取数实现。 */
    val repository: GithubDiscussionsRepository

    /**
     * 设备码授权拿到 token 后调：**先用它查一次用户名，查得到才落盘**（查不到说明 token
     * 其实不可用，落盘只会让界面以为已登录、随后每个请求都 401）。
     */
    suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String>

    /** 退出登录：清掉本机 token。GitHub 那边的授权要用户自己去设置里撤销。 */
    fun signOut()
}
