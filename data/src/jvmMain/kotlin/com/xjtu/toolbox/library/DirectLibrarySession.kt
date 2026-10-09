package com.xjtu.toolbox.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 没有会话内核的宿主用的**直连会话**（桌面窗口模式的 Stage 0 脚手架，JVM 单测也用它）。
 *
 * 它与 `:app` 那份 `AppLibrarySession` 的区别只有一件事：**没有 CAS 登录、没有重认证重放**。
 * 请求发出去，服务器答什么就是什么；答的是登录页就按会话失效报错（由 `LibraryApi` 判）。
 *
 * ⚠️ 这是 Stage 0 的脚手架，不是最终形态：设计里桌面端**要自己登录**（C2：每个人独立安装、
 * 独立登录），那份登录+会话内核按 `docs/desktop-port-plan.md` 的 Stage A 与数据层一起搬进
 * `:data`。在那之前，这个类让「同一份数据层在 JVM 上真能跑」这句话可以被测试证明。
 *
 * 放在 `jvmMain` 而不是 `commonMain`：它要 `Dispatchers.IO`，而 `commonMain` 里没有这一档
 * （`Dispatchers.IO` 不在 kotlinx-coroutines 的 common 元数据里）。阻塞调用必须落在 IO 上，
 * 所以这一份就待在它唯一被用到的那端。
 */
class DirectLibrarySession(override val client: OkHttpClient) : LibrarySession {

    override suspend fun fetch(request: Request): Response =
        withContext(Dispatchers.IO) { client.newCall(request).execute() }

    /** 没有会话内核 ⇒ 抛基类本身（文案与 `:app` 的 `AuthExpiredException` 逐字相同）。 */
    override fun authExpired(siteName: String) = com.xjtu.toolbox.auth.SessionExpiredException(siteName)
}
