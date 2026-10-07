package com.xjtu.toolbox.community

import android.content.Context
import android.content.SharedPreferences
import com.xjtu.toolbox.data.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 社区的 GitHub 登录态：一个 token 加用户名，加密存在本机。
 *
 * 用的是 GitHub App 的设备码登录，App 设置里关掉了 token 过期，所以没有刷新令牌这回事；
 * token 被用户在 GitHub 上撤销后，请求会 401，此时清掉重新登录即可。
 *
 * 从 :app 的 `community/GithubSession.kt` 改名而来：**契约**（[GithubSession]）搬进了 :core，
 * 这里只剩 Android 侧的实现 —— 存储（`SecurePrefs` 的 `github_community`）与 okhttp 取数实现
 * 都留在 :app，所以社区那三屏搬进 :core 之后由导航层把实例注入进去，界面不再自己 `get(context)`。
 */
class PrefsGithubSession private constructor(private val prefs: SharedPreferences) : GithubSession {
    private val _login = MutableStateFlow(prefs.getString(KEY_LOGIN, null)?.takeIf { token() != null })
    override val login: StateFlow<String?> = _login.asStateFlow()

    override val repository = HttpGithubDiscussionsRepository(token = ::token, onUnauthorized = ::signOut)

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** 设备码授权拿到 token 后调：先用它查一次用户名，查得到才落盘。 */
    override suspend fun signIn(accessToken: GithubDeviceAccessToken): Result<String> {
        val probe = HttpGithubDiscussionsRepository(token = { accessToken.accessToken })
        return probe.viewerLogin().onSuccess { name ->
            prefs.edit().putString(KEY_TOKEN, accessToken.accessToken).putString(KEY_LOGIN, name).apply()
            _login.value = name
        }
    }

    override fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
        _login.value = null
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_LOGIN = "login"

        @Volatile private var instance: PrefsGithubSession? = null

        fun get(context: Context): GithubSession = instance ?: synchronized(this) {
            instance ?: PrefsGithubSession(SecurePrefs.open(context.applicationContext, "github_community"))
                .also { instance = it }
        }
    }
}
