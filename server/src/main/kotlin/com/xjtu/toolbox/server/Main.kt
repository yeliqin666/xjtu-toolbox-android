package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi
import java.io.IOException
import java.util.concurrent.CountDownLatch
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
 *
 * ## 两种形态（D11）
 *
 * - **明文**（不给新参数，默认）：完全就是这一段一直以来的流程（横幅 → [serveServer] 阻塞）；
 * - **HTTPS**（`--https`）：证书先在手（[ServeTls.prepare]，报错就退出码 2）→ 起 [ServeHttps]
 *   → 横幅（地址是 `https://…`、带上证书在哪儿）→ 阻塞。内层的明文监听只绑回环。
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
    // 反馈那一条链的凭据是**运行期**注入的（两个窗口端是构建期烘焙的常量，serve 是部署在那台
    // 机器上的常驻进程 ⇒ 按环境变量注入才是它这一形态的正确答案，见 `ServeFeedback`）。
    // 没配也只是 `/api/feedback*` 那两条端点报「没配」，不影响其余端点。
    ServeFeedback.install()
    val session = ServeSession()
    val tls = config.tls
    if (tls == null) {
        // ── 明文那一档（默认，不给新参数）：与 D11 之前**逐字相同** ──
        printStartupBanner(config, token)
        serveServer(config, token, session).start(wait = true)
    } else {
        // ── HTTPS 形态（--https）：证书先在手，再起监听 ──
        // 顺带说明为什么准备证书在**打印横幅之前**：没有证书就没有这次服务，先把话说清楚再退 ——
        // 而“路径不存在就悄悄起明文”是这一批唯一不能演的那种错。
        val material = try {
            ServeTls.prepare(tls, config.host)
        } catch (e: ServeTlsException) {
            System.err.println("开不了 HTTPS：${e.message}")
            exitProcess(2)
        }
        val https = try {
            ServeHttps.start(config, token, session, material.context)
        } catch (e: IOException) {
            System.err.println("绑不上 HTTPS 端口 https://${config.host}:${config.port}：${e.message}")
            exitProcess(2)
        }
        printStartupBanner(config, token, httpsPort = https.port, tls = material)
        // 阻塞主线程直到进程结束（Ctrl+C）：两个监听都守着各自的线程，CIO 自己注册了关停钩子。
        CountDownLatch(1).await()
    }
}

private val HELP_FLAGS = setOf("-h", "--help")

/**
 * 启动横幅。五件事，一条都不能省：
 *  - 地址（用户要往浏览器里敲的）；
 *  - 静态产物目录（**不存在时明说**，并指出怎么产出它 —— 这个目录缺失是 serve 模式最常见的
 *    「起来了但白屏」）；
 *  - 令牌（唯一出处，并提醒它不该出现在提交/截图/聊天里）；
 *  - 安全口径（默认只监听回环；对外暴露时必须显式，并明确「风险自负」）；
 *  - HTTPS 形态下多一件：证书库在那儿、是刚生成的自签还是现有的、密码存在哪儿。
 *
 * 明文那一档（[httpsPort] 与 [tls] 都不给）打印的**逐字**还是 D11 之前那几条 —— 用户在用的
 * 那个 `0.0.0.0:40961` 看到的东西一个字符不该变。
 *
 * @param httpsPort HTTPS 真正在对外服务的端口（`--port 0` 时那是系统挑的），明文形态不传。
 * @param tls HTTPS 形态的证书材料，明文形态不传。
 */
private fun printStartupBanner(
    config: ServeConfig,
    token: String,
    httpsPort: Int? = null,
    tls: ServeTls.TlsMaterial? = null,
) {
    val port = when {
        httpsPort != null -> ":$httpsPort（端口由系统挑选）"
        config.port == 0 -> "（端口由系统挑选，见引擎自己那一行）"
        else -> ":${config.port}"
    }
    val scheme = if (tls == null) "http" else "https"
    val dist = config.distDir
    println()
    println("XJTU ToolBox · serve 模式")
    println("  监听：        $scheme://${config.host}$port")
    println(
        "  静态产物：    ${dist.absolutePath}" + if (dist.isDirectory) {
            ""
        } else {
            "  ⚠️ 这个目录不存在：静态一律 404。先跑 ./gradlew :web:wasmJsBrowserDistribution（约 18 分钟），或用 --dist 指到别处"
        },
    )
    println(
        "  反馈后端：    " + if (FeedbackApi.isConfigured) {
            "已配置（/api/feedback/* 可用）"
        } else {
            "未配置 ⇒ /api/feedback/* 报 502；设 ${ServeFeedback.ENV_APP_ID} / ${ServeFeedback.ENV_APP_SECRET} / ${ServeFeedback.ENV_BASE_TOKEN} 再启动"
        },
    )
    println("  访问令牌：    $token")
    println("                （存在 ~/.local/share/xjtu-toolbox/${AccessToken.STORE_NAME}.properties（0600），每次启动都打印这一份；")
    println("                  别贴进提交 / 截图 / 聊天。换一枚：删掉那个文件再启动。）")
    println("  浏览器侧带上：Authorization: Bearer <令牌>（或 cookie ${AccessToken.COOKIE_NAME}=<令牌>）")
    println("                /api/status 是唯一免令牌端点。")
    if (tls != null) {
        println(
            "  HTTPS 证书：  ${tls.keyStore.absolutePath}（别名 ${tls.alias}，" +
                if (tls.generated) "本次刚生成的自签证书）" else "已有的证书库）",
        )
        println("                自签证书浏览器第一次会警告：点「继续访问」就行（自己签的没人替它背书）。")
        println("                密码：环境变量 ${ServeTls.ENV_PASSWORD}，或 ${ServeTls.passwordFile(tls.keyStore).path}（0600）。")
    }
    if (config.exposed) {
        println()
        println("⚠️ 已按 --host ${config.host} 对外暴露：请自己在前面加反代 + 鉴权，风险自负。")
        println("   默认只监听 ${ServeConfig.DEFAULT_HOST} 就是不想让这个端口在没鉴权的公网上露出去 ——")
        println("   它后面是这个用户的校园账号（约座 / 退座 / 付款码）。")
        if (tls == null) {
            println("⚠️ HTTP + 非 localhost = 浏览器的**非安全上下文**：navigator.clipboard / crypto.subtle /")
            println("   Service Worker 会静默失效（点「复制」没反应也不报错）。要对外就得自己上 HTTPS；")
            println("   见 docs/api-contract.md §3.5。")
        } else {
            println("   这一步已经是 HTTPS（--https）：安全上下文成立，navigator.clipboard / crypto.subtle /")
            println("   Service Worker 都能用（这才是对外暴露时想要的那一种形态）。")
            println("   证书是自签的 ⇒ 每台来访问的机器第一次都要接受一次警告；要摆脱它就先把")
            println("   ${tls.keyStore.absolutePath} 里那张证书装进那台机器的信任库。")
        }
    } else {
        println()
        println("默认只监听 ${ServeConfig.DEFAULT_HOST}（回环）：同机任何进程都连得上这个端口，所以令牌是唯一的门。")
        println("要对外暴露必须显式写 --host 0.0.0.0，并自己加反代 + 鉴权 —— 风险自负。")
        if (tls != null) {
            println("HTTPS 形态：令牌换来的 cookie 带 Secure（明文那一档不带）；纯明文那一层只绑回环，外面看不到。")
        }
    }
    println()
}
