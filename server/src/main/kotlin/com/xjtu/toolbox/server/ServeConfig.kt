package com.xjtu.toolbox.server

import java.io.File

/**
 * serve 模式的启动参数：`--host` / `--port` / `--dist`。
 *
 * 三个默认值就是**对外承诺**（`docs/api-contract.md` §3），改它们等于改安全口径：
 *
 * - [DEFAULT_HOST] = `127.0.0.1`：**默认只监听回环地址**。这不是「方便」而是设计文档 D3 定的：
 *   serve 模式的会话跑在本进程里、凭据也落在本机，把端口露到公网上等于把该用户的校园账号
 *   交给任何扫到端口的人。
 * - [DEFAULT_PORT] = `8123`：与 `web/tools/serve-same-origin.py`（serve 模式的雏形）同一个端口 ——
 *   浏览器书签、`:web` 的探针页都按它写。
 * - [DEFAULT_DIST] = `:web` 的 wasm 产物目录，**相对仓库根**（`:server:run` 的工作目录已钉成仓库根，
 *   见 `server/build.gradle.kts`）。
 *
 * @param distDir 只读的静态托管根目录。允许不存在（那种情况下 `/api/…` 照样能跑，静态一律 404）。
 */
data class ServeConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val distDir: File = File(DEFAULT_DIST),
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

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        val USAGE = """
            用法：server [--host <地址>] [--port <端口>] [--dist <目录>]

              --host   监听地址，默认 $DEFAULT_HOST（只监听本机）。要对外暴露必须显式写
                       --host 0.0.0.0，并且自己在前面加反代 + 鉴权 —— 风险自负。
              --port   监听端口，默认 $DEFAULT_PORT。写 0 表示让系统挑一个空闲端口（测试用）。
              --dist   要托管的静态产物目录，默认 $DEFAULT_DIST（相对仓库根）。

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
                    else -> throw IllegalArgumentException("认不得这个参数：$arg")
                }
                index += 1
            }
            return ServeConfig(host = host, port = port, distDir = File(dist))
        }
    }
}
