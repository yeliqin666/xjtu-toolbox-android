package com.xjtu.toolbox.community

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 社区的 GitHub 登录态。 */
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

        /** 存储名，不能改（老登录态在这个文件里）。 */
        private const val STORE_NAME = "github_community"

        @Volatile private var instance: SecureGithubSession? = null

        /** 进程级单例：两个界面必须拿到同一份登录态。 */
        fun get(): GithubSession = instance ?: synchronized(this) {
            instance ?: SecureGithubSession(secureKeyValueStore(STORE_NAME)).also { instance = it }
        }
    }
}
