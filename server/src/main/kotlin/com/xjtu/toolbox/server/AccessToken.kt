package com.xjtu.toolbox.server

import com.xjtu.toolbox.platform.KeyValueStore
import com.xjtu.toolbox.platform.secureKeyValueStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * serve 模式的**访问令牌**：浏览器侧的唯一一道门（`docs/api-contract.md` §3.2）。
 *
 * ## 为什么必须有这一层
 *
 * 默认只监听 `127.0.0.1` 的意思不是「没人能连」—— 同机**任何**进程（包括浏览器里的任意一个页面）
 * 都能连上这个端口，而 `/api/…` 后面是这个用户的校园账号（约座 / 退座 / 付款码）。
 * 所以「只监听回环」只挡住了外网，同机这一层靠令牌。
 *
 * ## 它落在哪儿
 *
 * 走 `:data` 的 [secureKeyValueStore]（JVM 侧 = `~/.local/share/xjtu-toolbox/serve.properties`，
 * 权限 `0600`），与 cookie / 站点快照 / 凭据同一套落盘口径。语义要点：
 *
 * - **持久化**：下一次启动读到的是同一个令牌，浏览器那边不用每次重新贴（重启即失效的令牌
 *   会逼用户去翻日志，反而逼出「把令牌写进书签」这种更糟的做法）。
 * - **只在启动那一行出现**：令牌不进仓库、不进提交、不进别的日志行；`Main.kt` 打印它是**唯一**的
 *   出口（用户不打印就无从得知）。
 * - **换令牌 = 删文件**：`serve.properties` 删掉再启动即可（没有额外的失效机制，
 *   免得又多一份要同步的状态）。
 */
object AccessToken {

    /** `:data` 侧的文件名 = `serve.properties`。 */
    const val STORE_NAME = "serve"

    /** 存储里的键名。改它等于让所有已发的令牌失效。 */
    const val KEY = "access_token"

    /**
     * 令牌换 cookie 之后随请求带的那个 cookie 名（`docs/api-contract.md` §3.2 的第二种形态）。
     *
     * 换 cookie 的那一步是 `GET /api/session`（[setCookieHeader]，已落地）：浏览器在地址栏里
     * 贴不了 `Authorization`，所以「先拿 Bearer 换一枚 cookie，之后只靠它」是页面里唯一可行的形态。
     * 闸门（[accessTokenGate]）两种形态都认，名字就是这一个常量 —— 两边不会各起一个。
     */
    const val COOKIE_NAME = "serve_token"

    /** 32 字节 ≈ 256 位熵，Base64URL 之后 43 个字符。 */
    private const val TOKEN_BYTES = 32

    private val random = SecureRandom()

    /**
     * 读回已有令牌，没有就生成一个并落盘。
     *
     * @param store 默认是落盘那份；测试可以换成自己的（`dataRootOverride` 之外的一种隔离手法）。
     */
    fun loadOrCreate(store: KeyValueStore = secureKeyValueStore(STORE_NAME)): String {
        store.getString(KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        return newToken().also { store.putString(KEY, it) }
    }

    /** 生成一枚新令牌（URL 安全的 Base64，不带 `=` 填充 —— 它要出现在命令行与 cookie 里）。 */
    fun newToken(): String = ByteArray(TOKEN_BYTES)
        .also { random.nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /**
     * 令牌换 cookie 的 `Set-Cookie` 值（`GET /api/session` 回给浏览器的那一行）。
     *
     * 三个属性各自对应一件真事：
     * - `Path=/`：`/api/…` 与静态产物（`index.html`、`.wasm`）都走这一枚 cookie；
     * - `HttpOnly`：**这是重点** —— 页面里的脚本读不到令牌（令牌是这一层唯一的门，
     *   页面上任何一个 XSS 都能把非 HttpOnly 的 cookie 读走）；
     * - `SameSite=Lax`：serve 是「同源单页 + 用户自己敲地址/点书签」的形态，Lax 够用。
     *   不能给 `None`（那要求 `Secure`，而默认是明文 HTTP ⇒ cookie 直接被丢掉），
     *   也不用 `Strict`（从聊天/邮件里点进来的链接会丢掉 cookie ⇒ 用户看到的是
     *   「刚登录过、每次进去又都说没登录」）。
     */
    fun setCookieHeader(token: String): String = "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Lax"

    /**
     * 常数时间比较。
     *
     * 为什么不用 `==`：字符串相等会在第一个不同的字符处提前返回，理论上能按响应时间一个字符
     * 一个字符地把令牌试出来。这正是 [`MessageDigest.isEqual`] 存在的场合。
     */
    fun matches(expected: String, candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }
}
