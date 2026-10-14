package com.xjtu.toolbox.server

import com.xjtu.toolbox.platform.dataRootDir
import java.io.File

/**
 * serve 模式的启动参数：`--host` / `--port` / `--dist` / `--https`（+ `--cert` / `--cert-alias`）。
 *
 * 前三个的默认值就是**对外承诺**（`docs/api-contract.md` §3），改它们等于改安全口径：
 *
 * - [DEFAULT_HOST] = `127.0.0.1`：**默认只监听回环地址**。这不是「方便」而是设计文档 D3 定的：
 *   serve 模式的会话跑在本进程里、凭据也落在本机，把端口露到公网上等于把该用户的校园账号
 *   交给任何扫到端口的人。
 * - [DEFAULT_PORT] = `8123`：与 `web/tools/serve-same-origin.py`（serve 模式的雏形）同一个端口 ——
 *   浏览器书签、`:web` 的探针页都按它写。
 * - [DEFAULT_DIST] = `:web` 的 wasm 产物目录，**相对仓库根**（`:server:run` 的工作目录已钉成仓库根，
 *   见 `server/build.gradle.kts`）。
 *
 * ## HTTPS 是**另一档形态**，不是给默认形态加东西（D11）
 *
 * [tls] 为 null（默认、不给新参数）= 今天那一档：明文 HTTP + 令牌闸门，**逐字不变**。
 * 给了 `--https` 才是 HTTPS 形态：`--host/--port` 就是 **HTTPS** 的地址与端口，明文监听只绑回环
 * （本进程内部用，见 [ServeHttps]），cookie 多一个 `Secure`（[AccessToken.setCookieHeader]）。
 *
 * 为什么不是「加一个 https 开关就完事」：HTTPS 必须有证书，而证书要么用户给、要么我们生成 ——
 * 这两件事都在 [ServeTls] 里，且**失败一律响亮报错**（不给证书、路径不存在、密码不对都**不**回落
 * 成明文：那会把「我以为开了 HTTPS」变成最糟的那种静默降级）。
 *
 * @param distDir 只读的静态托管根目录。允许不存在（那种情况下 `/api/…` 照样能跑，静态一律 404）。
 * @param tls 非 null = HTTPS 形态（`--https`）；null = 明文（默认）。
 */
data class ServeConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val distDir: File = File(DEFAULT_DIST),
    val tls: TlsSettings? = null,
) {
    /**
     * 这个进程正在**对外**服务（`--host` 不是回环地址）⇒ 启动日志要明确警告「自己加反代 + 鉴权」。
     *
     * 判据是「不等于回环地址」而不是「等于 0.0.0.0」：`--host 192.168.1.5` 同样是暴露。
     */
    val exposed: Boolean get() = host !in LOOPBACK_HOSTS

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8123
        const val DEFAULT_DIST = "web/build/dist/wasmJs/productionExecutable"

        /** 证书默认落在**数据根**下面的这一层（与访问令牌那份 `serve.properties` 同一个根）。 */
        const val DEFAULT_TLS_DIR = "tls"

        /** 自签证书库的默认文件名（PKCS12；`keytool` 生成的就是它）。 */
        const val DEFAULT_TLS_KEYSTORE = "serve.p12"

        /** 证书库里的别名（`keytool -alias` 生成时用的那一个）。 */
        const val DEFAULT_TLS_ALIAS = "serve"

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        /** 默认证书库：`<数据根>/tls/serve.p12`（[dataRootDir] 的口径，数据根 override 优先）。 */
        fun defaultTlsKeyStore(): File = File(File(dataRootDir(), DEFAULT_TLS_DIR), DEFAULT_TLS_KEYSTORE)
        val USAGE = """
            用法：server [--host <地址>] [--port <端口>] [--dist <目录>] [--https [--cert <证书库>] [--cert-alias <别名>]]

              --host   监听地址，默认 $DEFAULT_HOST（只监听本机）。要对外暴露必须显式写
                       --host 0.0.0.0，并且自己在前面加反代 + 鉴权 —— 风险自负。
              --port   监听端口，默认 $DEFAULT_PORT。写 0 表示让系统挑一个空闲端口（测试用）。
              --dist   要托管的静态产物目录，默认 $DEFAULT_DIST（相对仓库根）。
              --https  用 HTTPS 监听（--host / --port 就是 HTTPS 的地址与端口，不再是明文）。
                       自签证书浏览器会警告一次，接受之后 navigator.clipboard / crypto.subtle /
                       Service Worker 这些**安全上下文**才可用。
              --cert   证书库文件（PKCS12 或 JKS），只在 --https 下有意义。
                       省略 = $DEFAULT_TLS_DIR/$DEFAULT_TLS_KEYSTORE（在数据根下），不存在就自动生成一张自签证书。
                       给了 --cert 时那个文件必须已经存在：不存在就**报错**，不会捂着生成，也不回落成明文。
              --cert-alias  证书库里的别名，默认 $DEFAULT_TLS_ALIAS（只在 --https 下有意义）。

            自签证书库的密码：环境变量 XJTU_TOOLBOX_TLS_PASSWORD，或与库文件同名的 `.password` 文件
            （自动生成时会在库旁边写一个，权限 0600）。

            启动后会打印访问令牌；浏览器侧必须带上它（Authorization: Bearer <令牌>
            或 cookie serve_token=<令牌>）。`/api/status` 是唯一免令牌端点。
        """.trimIndent()
        /**
         * 解析命令行。两种写法都收：`--port 18234` 与 `--port=18234`。
         *
         * 不认的参数**直接失败**（不静默忽略）：`--prot 18234` 这种手滑如果被忽略，进程会安静地
         * 起在 8123 上 —— 那正是「明明改了参数却没生效」最难查的一类。
         *
         * @throws IllegalArgumentException 参数认不得、缺值、或值不合法（消息是中文短句，直接给用户看）
         */
        fun parse(args: Array<String>): ServeConfig {
            var host = DEFAULT_HOST
            var port = DEFAULT_PORT
            var dist = DEFAULT_DIST
            var https = false
            var cert: String? = null
            var certAlias = DEFAULT_TLS_ALIAS
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                val eq = arg.indexOf('=')
                val name = if (arg.startsWith("--") && eq > 0) arg.substring(0, eq) else arg
                val inline = if (arg.startsWith("--") && eq > 0) arg.substring(eq + 1) else null

                fun value(): String {
                    inline?.let { return it }
                    require(index + 1 < args.size) { "$name 后面缺少值" }
                    index += 1
                    return args[index]
                }

                when (name) {
                    "--host" -> host = value().also { require(it.isNotBlank()) { "--host 不能是空字符串" } }
                    "--port" -> {
                        val raw = value()
                        port = raw.toIntOrNull()?.takeIf { it in 0..65535 }
                            ?: throw IllegalArgumentException("--port 需要 0~65535 的整数，给的是「$raw」")
                    }
                    "--dist" -> dist = value().also { require(it.isNotBlank()) { "--dist 不能是空字符串" } }
                    // --https 是一个**开关**（不带值）：写成 `--https=1` 多半是把它当成了带值的参数
                    "--https" -> {
                        require(inline == null) { "--https 不带值（它是个开关）：只写 --https" }
                        https = true
                    }
                    "--cert" -> cert = value().also { require(it.isNotBlank()) { "--cert 不能是空字符串" } }
                    "--cert-alias" -> certAlias = value().also {
                        require(it.isNotBlank()) { "--cert-alias 不能是空字符串" }
                    }
                    else -> throw IllegalArgumentException("认不得这个参数：$arg")
                }
                index += 1
            }
            // 证书那两个参数只在 HTTPS 形态下有意义：给了却没开 HTTPS 就是「我以为它生效了」的典型
            // ⇒ 响亮报错，而不是静默忽略（与「认不得的参数直接失败」同一条纪律）。
            if (!https) {
                require(cert == null) { "--cert 只在 --https 下有意义（要么加上 --https，要么去掉 --cert）" }
                require(certAlias == DEFAULT_TLS_ALIAS) {
                    "--cert-alias 只在 --https 下有意义（要么加上 --https，要么去掉 --cert-alias）"
                }
            }
            val tls = if (!https) {
                null
            } else if (cert != null) {
                // 用户给的库：**只读**，不存在也不生成（那是 ServeTls 那一层的明确报错）
                TlsSettings(keyStore = File(cert), alias = certAlias, generateIfMissing = false)
            } else {
                TlsSettings(
                    keyStore = defaultTlsKeyStore(),
                    alias = certAlias,
                    generateIfMissing = true,
                )
            }
            return ServeConfig(host = host, port = port, distDir = File(dist), tls = tls)
        }
    }
}

/**
 * HTTPS 形态的材料申报（[ServeConfig.tls] 非 null 时才有）：证书库在哪儿、用哪个别名、
 * 缺文件时允不允许生成一张自签。
 *
 * 为什么不含密码：密码从不进命令行 —— 要么从环境变量读、要么从库旁边那个 0600 的文件读
 * （见 [ServeTls]）。命令行上的密码会在 `ps` 与 shell 历史里留着。
 *
 * @param keyStore 证书库文件（PKCS12 或 JKS）
 * @param alias 库里的别名
 * @param generateIfMissing true 只出现在「没给 `--cert`」这一档：默认路径上不存在就生成自签；
 *   用户显式给了 `--cert` 时是 false —— 那样路径不存在就**报错**，不捂着生成、不回落成明文。
 */
data class TlsSettings(
    val keyStore: File,
    val alias: String = ServeConfig.DEFAULT_TLS_ALIAS,
    val generateIfMissing: Boolean = false,
)
