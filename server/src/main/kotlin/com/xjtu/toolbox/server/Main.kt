package com.xjtu.toolbox.server

import com.xjtu.toolbox.feedback.FeedbackApi
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** `./gradlew :server:run --args="--port 18234 --dist web/build/dist/wasmJs/productionExecutable"` —— serve 模式的入口（无窗口；浏览器打开它打印的那个地址即可）。 */
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
    ServeFeedback.install()
    val session = ServeSession()
    val tls = config.tls
    if (tls == null) {
        printStartupBanner(config, token)
        serveServer(config, token, session).start(wait = true)
    } else {
        // ── HTTPS（--https）：证书先在手再起监听；路径不存在绝不悄悄起明文 ──
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

/** 启动横幅。 */
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
