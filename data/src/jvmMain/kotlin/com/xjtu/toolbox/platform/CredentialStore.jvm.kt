package com.xjtu.toolbox.platform

import com.xjtu.toolbox.auth.XJTULogin

/**
 * **JVM（窗口模式 / serve 模式）的凭据存储**：账号 + 密码 + 设备指纹 + 缓存的 RSA 公钥。
 *
 * ## 它为什么在 `:data` 而不是 `:desktop`
 *
 * 设计文档 §5.1 把「凭据存储」列在**必须新写的宿主实现**里，与 `KeyValueStore` 的 JVM 落盘版
 * （就是同目录的 `SecureStore.jvm.kt`）并列 —— 这两件事是同一层的东西：一个存会话 cookie，
 * 一个存登录凭据，都落在同一个数据目录、同一套 `0600` 权限上。serve 模式（同一个 JVM 模块的
 * 另一个入口）将来也要用它，留在 `:desktop` 就得搬第二次。
 *
 * ## 存的是什么、为什么这么存
 *
 * 直接复用 [secureKeyValueStore]：Android 的 actual 是 `EncryptedSharedPreferences`，JVM 的
 * actual 是**权限 0600 的 Properties 文件**（`java.util.Properties` 自带转义，密码里出现
 * `=`、换行、非 ASCII 都不会坏）。文件名与键名**与 `:app` 的 `CredentialStore` 逐字相同**
 * （`xjtu_credentials` / `username` / `password` / `fp_visitor_id` / `rsa_public_key` / `rsa_key_time`）
 * —— 桌面端没有「老数据」要继承，保持一致是为了让两端的语义能一眼对上，也免得将来做
 * 「从 Android 迁一个账号过来」时又对一遍键名。
 *
 * ⚠️ **这是 Stage A 的口径，不是终点**：Linux 上「文件 + 0600」与 campus-api 现有做法同口径；
 * Windows 上按设计文档要给 DPAPI / Credential Manager，属于宿主机能族那一批（§5.4）。
 * 前端一旦有真账号，凭据就是分发的代价（§8 第 3 条）：退出登录必须调 [clear]。
 *
 * ## 键/值类型
 *
 * 全部走字符串：`:core` 的 `KeyValueStore` 没有 `getLong`（三端接口里没有那一档，也一直没需要），
 * 所以 `rsa_key_time` 存成十进制字符串，读的时候解析。**不要**改成别的类型 —— 类型变了
 * 老文件就读不出来（这条与 `SecureStore` 那批文件是同一条纪律）。
 */
class JvmCredentialStore(private val storeName: String = FILE_NAME) {

    private val store: KeyValueStore = secureKeyValueStore(storeName)

    /** 账号与密码。**任一为空都算没有**（与 `:app` 的 `CredentialStore.load` 同口径）。 */
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

    /**
     * 整份本次凭据抹掉（退出登录 / 删账号）。
     *
     * **不**删 cookie 与站点快照：那两样按账号命名空间分文件（`cookies_*_<后缀>`），
     * 由 `SessionBackend.wipe` / [wipeSecureStore] 负责 —— 与 `:app` 的登录/登出分工一致。
     */
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

    /**
     * 缓存的 RSA 公钥（学校统一认证那把静态公钥），**24 小时内有效**。
     *
     * 缓存它不是为了省流量，是为了省一次**必发**的请求：`XJTULogin.encryptPassword` 没有缓存
     * 公钥时会去 `https://login.xjtu.edu.cn/cas/jwt/publicKey` 取一份（那条路是 https，
     * 也是 `LibraryLogin` 那批站点唯一会多出来的往返）。过期后返回 null ⇒ 内核照常自己去取。
     */
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
