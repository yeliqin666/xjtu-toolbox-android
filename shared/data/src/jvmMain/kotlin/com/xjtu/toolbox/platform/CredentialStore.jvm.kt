package com.xjtu.toolbox.platform

import com.xjtu.toolbox.auth.XJTULogin

/** JVM（窗口模式 / serve 模式）的凭据存储：账号 + 密码 + 设备指纹 + 缓存的 RSA 公钥。 */
class JvmCredentialStore(
    private val storeName: String = FILE_NAME,
    protector: SecretProtector? = secretProtectorForPlatform(),
) {

    /** 落盘层外再套保护器：Windows 上写 DPAPI 密文，其余平台原样。 */
    private val store: KeyValueStore = ProtectedKeyValueStore(secureKeyValueStore(storeName), protector)

    /** 账号与密码，任一为空都算没有。 */
    fun load(): Pair<String, String>? {
        val username = store.getString(KEY_USERNAME) ?: return null
        val password = store.getString(KEY_PASSWORD) ?: return null
        if (username.isEmpty() || password.isEmpty()) return null
        return username to password
    }

    fun save(username: String, password: String) {
        store.putString(KEY_USERNAME, username)
        store.putString(KEY_PASSWORD, password)
    }

    /** 整份本次凭据抹掉（退出登录 / 删账号）。 */
    fun clear() {
        store.remove(KEY_USERNAME)
        store.remove(KEY_PASSWORD)
        store.remove(KEY_FP_VISITOR_ID)
        store.remove(KEY_RSA_PUBLIC_KEY)
        store.remove(KEY_RSA_KEY_TIME)
    }

    /** 设备指纹 ID。首个完成登录的站点写进来，下次冷启动继续用同一个（换来换去会被学校当成新设备）。 */
    var fpVisitorId: String?
        get() = store.getString(KEY_FP_VISITOR_ID)
        set(value) = store.putString(KEY_FP_VISITOR_ID, value)

    /** 缓存的 RSA 公钥（学校统一认证那把静态公钥），24 小时内有效。 */
    var rsaPublicKey: String?
        get() {
            val time = store.getString(KEY_RSA_KEY_TIME)?.toLongOrNull() ?: return null
            if (System.currentTimeMillis() - time > RSA_KEY_TTL_MS) return null
            return store.getString(KEY_RSA_PUBLIC_KEY)
        }
        set(value) {
            store.putString(KEY_RSA_PUBLIC_KEY, value)
            store.putString(KEY_RSA_KEY_TIME, System.currentTimeMillis().toString())
        }

    /** 账号身份（本科 / 研究生）。CAS 的分支要用它 —— 研究生选错会登不进去。 */
    var accountType: XJTULogin.AccountType
        get() = XJTULogin.AccountType.valueOf(
            store.getString(KEY_ACCOUNT_TYPE) ?: XJTULogin.AccountType.UNDERGRADUATE.name
        )
        set(value) = store.putString(KEY_ACCOUNT_TYPE, value.name)

    companion object {
        /** 与 `:app` 的 `CredentialStore.FILE_NAME` 同名（见类 KDoc：不是为了继承数据，是为了对得上）。 */
        const val FILE_NAME = "xjtu_credentials"

        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FP_VISITOR_ID = "fp_visitor_id"
        private const val KEY_RSA_PUBLIC_KEY = "rsa_public_key"
        private const val KEY_RSA_KEY_TIME = "rsa_key_time"
        private const val KEY_ACCOUNT_TYPE = "account_type"

        /** 与 `:app` 的 `CredentialStore.loadRsaPublicKey` 同一个窗口。 */
        private const val RSA_KEY_TTL_MS = 24 * 3600 * 1000L
    }
}
