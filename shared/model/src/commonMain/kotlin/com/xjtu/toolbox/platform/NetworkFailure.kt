package com.xjtu.toolbox.platform

/** 平台能力：网络/IO 失败分类。 */
enum class NetworkFailure {
    /** 域名解析不了 / 完全连不上：多半没网、不在校园网，或 VPN 没连上。 */
    NO_HOST,

    /** 连上了但对端不响应（读超时 / 连接超时）。 */
    TIMEOUT,

    /** 对端明确拒绝连接 —— 有 VPN 但隧道不通时的典型表现。 */
    REFUSED,

    /** TLS 握手 / 证书 / 代理失败。 */
    TLS,

    /** 其它 IO 失败（连接被重置、读到一半断开……）：不是业务失败，是「网络没成」。 */
    IO,
}

/** 把异常归类成 [NetworkFailure]；不是网络失败（业务代码自己抛的、解析错误等）返回 null。 */
expect fun classifyNetworkFailure(e: Throwable): NetworkFailure?
