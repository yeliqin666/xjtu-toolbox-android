package com.xjtu.toolbox.server

import kotlin.system.exitProcess

/**
 * `./gradlew :server:run --args="--port 18234 --dist web/build/dist/wasmJs/productionExecutable"`
 * —— serve 模式的入口（无窗口；浏览器打开它打印的那个地址即可）。
 *
 * ## 这个 main 只做四件事
 *
 * 1. 解析命令行（[ServeConfig.parse]）；
 * 2. 取访问令牌（[AccessToken.loadOrCreate]：没有就生成并落盘 `0600`）；
 * 3. **打印启动横幅** —— 令牌与「只监听 127.0.0.1」的说明都在里面，这是用户唯一能拿到令牌的地方；
 * 4. 建好**会话装配**（[ServeSession]）并启动（阻塞；Ctrl+C 结束，CIO 自己注册了关停钩子）。
 *
 * 没有业务代码：路由在 [serveModule]，静态托管在 `staticSite`。
 *
 * ⚠️ 横幅在 `start` **之前**打印：万一端口被占，用户至少已经看到令牌与它存在哪儿 ——
 * 反过来（先启动再打印）在起不来的时候就什么都看不到。
 */
fun main(args: Array<String>) {
    if (args.any { it in HELP_FLAGS }) {
        println(ServeConfig.USAGE)
        return
    }
    val config = try {
        ServeConfig.parse(args)
    } catch (e: IllegalArgumentException) {
        // 参数不对是**用法**问题，不是崩溃：把用法一起打出来，退出码 2（与 shell 的惯例一致）
        System.err.println("参数不对：${e.message}")
        System.err.println()
        System.err.println(ServeConfig.USAGE)
        exitProcess(2)
    }

    val token = AccessToken.loadOrCreate()
    printStartupBanner(config, token)
    serveServer(config, token, ServeSession()).start(wait = true)
}

private val HELP_FLAGS = setOf("-h", "--help")

/**
 * 启动横幅。四件事，一条都不能省：
 *  - 地址（用户要往浏览器里敲的）；
 *  - 静态产物目录（**不存在时明说**，并指出怎么产出它 —— 这个目录缺失是 serve 模式最常见的
 *    「起来了但白屏」）；
 *  - 令牌（唯一出处，并提醒它不该出现在提交/截图/聊天里）；
 *  - 安全口径（默认只监听回环；对外暴露时必须显式，并明确「风险自负」）。
 */
private fun printStartupBanner(config: ServeConfig, token: String) {
    val port = if (config.port == 0) "（端口由系统挑选，见引擎自己那一行）" else ":${config.port}"
    val dist = config.distDir
    println()
    println("XJTU ToolBox · serve 模式")
    println("  监听：        http://${config.host}$port")
    println(
        "  静态产物：    ${dist.absolutePath}" + if (dist.isDirectory) {
            ""
        } else {
            "  ⚠️ 这个目录不存在：静态一律 404。先跑 ./gradlew :web:wasmJsBrowserDistribution（约 18 分钟），或用 --dist 指到别处"
        },
    )
    println("  访问令牌：    $token")
    println("                （存在 ~/.local/share/xjtu-toolbox/${AccessToken.STORE_NAME}.properties（0600），每次启动都打印这一份；")
    println("                  别贴进提交 / 截图 / 聊天。换一枚：删掉那个文件再启动。）")
    println("  浏览器侧带上：Authorization: Bearer <令牌>（或 cookie ${AccessToken.COOKIE_NAME}=<令牌>）")
    println("                /api/status 是唯一免令牌端点。")
    if (config.exposed) {
        println()
        println("⚠️ 已按 --host ${config.host} 对外暴露：请自己在前面加反代 + 鉴权，风险自负。")
        println("   默认只监听 ${ServeConfig.DEFAULT_HOST} 就是不想让这个端口在没鉴权的公网上露出去 ——")
        println("   它后面是这个用户的校园账号（约座 / 退座 / 付款码）。")
        println("⚠️ HTTP + 非 localhost = 浏览器的**非安全上下文**：navigator.clipboard / crypto.subtle /")
        println("   Service Worker 会静默失效（点「复制」没反应也不报错）。要对外就得自己上 HTTPS；")
        println("   见 docs/api-contract.md §3.5。")
    } else {
        println()
        println("默认只监听 ${ServeConfig.DEFAULT_HOST}（回环）：同机任何进程都连得上这个端口，所以令牌是唯一的门。")
        println("要对外暴露必须显式写 --host 0.0.0.0，并自己加反代 + 鉴权 —— 风险自负。")
    }
    println()
}
