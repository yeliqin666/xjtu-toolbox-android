package com.xjtu.toolbox.community

import kotlinx.coroutines.Dispatchers

/**
 * 社区取数的**装配**：把 [OkHttpGithubTransport]（本文件隔壁）塞进 `:core` 的共享实现
 * [GraphQlGithubDiscussionsRepository]。
 *
 * 从 `:app` 的 `community/GithubDiscussions.kt` 原样搬进 `:data`（桌面端第 21 条真数据路由：
 * 桌面要自己去本仓库的 Discussions 取列表/详情）。类名与包路径一字未改；两行 okhttp 的传输
 * 就在隔壁，`dispatcher = Dispatchers.IO` 与搬迁前一致（`:core` 那边的默认值是
 * `Dispatchers.Default` —— `Dispatchers.IO` 是 JVM 专有的）。
 */
class HttpGithubDiscussionsRepository(
    token: () -> String?,
    onUnauthorized: () -> Unit = {},
    endpoint: String = "https://api.github.com/graphql",
) : GithubDiscussionsRepository by GraphQlGithubDiscussionsRepository(
    token = token,
    transport = OkHttpGithubTransport,
    onUnauthorized = onUnauthorized,
    endpoint = endpoint,
    dispatcher = Dispatchers.IO,
)
