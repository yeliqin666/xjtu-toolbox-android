package com.xjtu.toolbox.community

import kotlinx.coroutines.Dispatchers

/** 社区取数的装配：把 [OkHttpGithubTransport]（本文件隔壁）塞进 `:core` 的共享实现 [GraphQlGithubDiscussionsRepository]。 */
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
