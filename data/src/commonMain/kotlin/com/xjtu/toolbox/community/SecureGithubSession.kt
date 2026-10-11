package com.xjtu.toolbox.community

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 社区的 GitHub 登录态：一个 token 加用户名，**存在本机的密文键值存储里**（Android =
 * `EncryptedSharedPreferences`，桌面 = `0600` 的落盘文件）。
 *
 * 用的是 GitHub App 的设备码登录，App 设置里关掉了 token 过期，所以没有刷新令牌这回事；
 * token 被用户在 GitHub 上撤销后，请求会 401，此时清掉重新登录即可。
 *
 * ## 它为什么搬进 `:data`（如实交代）
 *
 * 原来是 `:app` 的 `community/PrefsGithubSession.kt`（`SecurePrefs.open(context, "github_community")`
 * + 单例）。**存储换成了 `:data` 的密文键值缝 [secureKeyValueStore]，而它在 Android 上的 actual
 * 就是同一个 `SecurePrefs.open(context, name)`**（见 `data/androidMain/.../SecureStore.android.kt`
 * 的 KDoc）—— 所以：
 *
 *  - **文件名与键名一个没改**（`github_community` / `token` / `login`），老登录态无缝沿用；
 *  - 语义逐条保持：`login` 只在 token 非空时才算已登录；[signIn] **先用 token 查一次用户名，
 *    查得到才落盘**；[signOut] 只清本机 token（GitHub 那边的授权要用户自己去设置里撤销）。
 *
 * 换来的东西是**桌面端能复用同一份**（`:desktop` 里没有 `Context`，而 `secureKeyValueStore` 的
 * JVM actual 是一份落盘 Properties —— 与其余会话快照同一套）。
 *
 * 改名理由与 `scoreReportSource` 同一条：名字里带 `Prefs` 在共享层会撒谎（这一份不再是
 * Android 的 `SharedPreferences`，而是跨端的密文键值存储）。单例照旧（`get()`），
 * 否则同一进程里两个界面各拿一份 `StateFlow`、退出登录互相看不见。
 */
class SecureGithubSession private constructor(private val store: KeyValueStore) : GithubSession {

    // 键值缝上没有 `getString(key, default)` 那一档（`SharedPreferences` 才有）——键不存在就是 null，
    // 而 `store.getString(KEY_LOGIN, null)` 与 `getString(KEY_LOGIN)` 语义本来就一样，调用方自己给默认值。
    private val _login = MutableStateFlow(store.getString(KEY_LOGIN)?.takeIf { token() != null })
    override val login: StateFlow<String?> = _login.asStateFlow()

    override val repository = HttpGithubDiscussionsRepository(token = ::token, onUnauthorized = ::signOut)

    fun token(): String? = store.getString(KEY_TOKEN)?.takeIf { it.isNotBlank() }

    /** 设备码授权拿到 token 后调：先用它查一次用户名，查得到才落盘。 */
    override suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String> {
        val probe = HttpGithubDiscussionsRepository(token = { accessToken.accessToken })
        return probe.viewerLogin().onSuccess { name ->
            store.putString(KEY_TOKEN, accessToken.accessToken)
            store.putString(KEY_LOGIN, name)
            _login.value = name
        }
    }

    override fun signOut() {
        store.remove(KEY_TOKEN)
        store.remove(KEY_LOGIN)
        _login.value = null
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_LOGIN = "login"

        /** 存储名 —— 与搬迁前 `PrefsGithubSession` 的 `SecurePrefs` 文件名**逐字相同**。 */
        private const val STORE_NAME = "github_community"

        @Volatile private var instance: SecureGithubSession? = null

        /** 进程级单例（与搬迁前同形）：两个界面拿到的必须是同一份登录态。 */
        fun get(): GithubSession = instance ?: synchronized(this) {
            instance ?: SecureGithubSession(secureKeyValueStore(STORE_NAME)).also { instance = it }
        }
    }
}
