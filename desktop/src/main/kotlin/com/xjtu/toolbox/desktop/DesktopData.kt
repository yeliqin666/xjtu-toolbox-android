package com.xjtu.toolbox.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import com.xjtu.toolbox.core.net.createToolboxClient
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.safeBoolean
import com.xjtu.toolbox.util.safeLong
import kotlinx.serialization.json.jsonObject

/**
 * 桌面端的**数据入口**（阶段 0 的脚手架）。
 *
 * ## 它为什么这么短
 *
 * `:core` 里已经有 13 个 campus-api 版的取数实现（`Campus*Api`：客户端、JSON 口径、
 * 字段映射、错误语义、降级声明全在里面，有测试钉着）。桌面端不需要另写一份，只需要回答
 * 一个其他端回答不了的问题：**基址填什么**。
 *
 *  - Web（`:web`）：留空 ⇒ 相对路径 = **同源**，靠开发服务器/nginx 反代到 `127.0.0.1:3099`。
 *    浏览器不给学校域名发 CORS 头，所以这是浏览器端唯一可用的路；
 *  - 桌面：**没有同源这回事**——窗口模式是进程内直取，干脆直连本机那个 campus-api。
 *
 * ## ⚠️ 这是脚手架，不是交付形态
 *
 * 直连 `127.0.0.1:3099` 意味着这一端**只能跑在「我自己这台开着 campus-api 的机器」上**，
 * 而设计目标（C2）是「任何人独立安装、独立登录、不依赖任何我方的服务器」。
 * 所以阶段 0 只用它把画面跑起来；Stage A 换 `:data`（自带登录与取数，同一个进程内直调）。
 * 这也是为什么这个常量**不叫** BASE_URL 而叫「脚手架基址」。
 */
const val SCAFFOLD_API_BASE: String = "http://127.0.0.1:3099"

/** 复用 `:core` 的客户端工厂：引擎、JSON 口径、Cookie 策略全在共享层，各端不各配一遍。 */
fun desktopCampusClient(): HttpClient = createToolboxClient()

/**
 * 脚手架自检：`/api/status` 是 campus-api 的**裸对象**端点（唯一一个没有 `{code,data}` 信封的），
 * 拿它探活最省事。「全部页面」页把这行显示出来 —— 免得「没数据」被误当成「屏坏了」。
 *
 * ⚠️ **只报状态，不报身份**：上游那份 JSON 里有 `username`（学号），而这一行会出现在截图与
 * 排障记录里。显示「已登录」就够了 —— 身份该出现在「我的」那一屏（Stage C），不该出现在探针里。
 * 与红线同源：个人数据只作证据，不落进文档、日志与画面。
 */
suspend fun scaffoldStatusText(client: HttpClient): String = runCatching {
    val text = client.get("$SCAFFOLD_API_BASE/api/status").bodyAsText()
    val json = AppJson.parseToJsonElement(text).jsonObject
    val authed = json["authenticated"].safeBoolean()
    val uptimeHours = json["uptimeSeconds"].safeLong() / 3600
    "campus-api 127.0.0.1:3099 已连上 · ${if (authed) "会话正常" else "未登录"} · 已运行 ${uptimeHours}h"
}.getOrElse { e ->
    "campus-api 连不上（${e.message ?: e::class.simpleName}）—— 桌面的脚手架数据源就是它，先把它起起来"
}
