package com.xjtu.toolbox.community

import kotlinx.coroutines.Dispatchers

/**
 * 社区取数的 Android 入口：**薄薄一层**，只负责把 okhttp 传输塞进共享实现
 * [GraphQlGithubDiscussionsRepository]。
 *
 * 查询字符串、JSON 解析、401 回调、限流判定全在 :core（`community/GithubGraphQl.kt`）——
 * 搬迁前这 272 行都在这里，只有两行真的碰 okhttp，于是那两行被抽成 `GithubHttpTransport`，
 * 剩下的整体搬走，Web 端因此能拿同一份代码去打同一个 GraphQL 端点。
 *
 * `dispatcher = Dispatchers.IO`：okhttp 的 `execute()` 是阻塞调用，与搬迁前一致；
 * :core 那边的默认值是 `Dispatchers.Default`（`Dispatchers.IO` 是 JVM 专有的）。
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
