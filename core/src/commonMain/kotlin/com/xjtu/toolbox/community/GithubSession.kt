package com.xjtu.toolbox.community

import kotlinx.coroutines.flow.StateFlow

/** 社区登录态（契约在 :core，存储在 `:data`）。 */
interface GithubSession {
    /** 已登录的 GitHub 用户名；null 表示未登录。 */
    val login: StateFlow<String?>

    /** 带登录 token 的取数实现。 */
    val repository: GithubDiscussionsRepository

    /** 设备码授权拿到 token 后调：先查一次用户名，查得到才落盘。 */
    suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String>

    /** 退出登录：清掉本机 token。GitHub 那边的授权要用户自己去设置里撤销。 */
    fun signOut()
}
