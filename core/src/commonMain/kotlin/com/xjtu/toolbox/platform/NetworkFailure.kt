package com.xjtu.toolbox.platform

/**
 * 平台能力：**网络/IO 失败分类**（交接文档 §5 里 `error/FriendlyError` 那个阻挡者的本体）。
 *
 * 为什么要有这一族：界面文案只有一套，而「判断这是不是网络错、是哪一种网络错」各端能认的
 * 异常类型完全不同 —— Android/JVM 是 `java.net.UnknownHostException` / `SocketTimeoutException` /
 * `ConnectException` / `javax.net.ssl.SSLException` / `java.io.IOException`，Web 是 fetch 抛出的
 * `TypeError`。把这些**只用于类型判断**的 JVM 类型关在平台家族里，commonMain 的
 * [com.xjtu.toolbox.error.FriendlyError] 就只认识这个枚举。
 *
 * 各端实现：
 *   - Android / jvm：按上列 JVM 异常类型逐个判（顺序即优先级）；
 *   - wasmJs：如实返回 null（见 wasmJs actual 的说明），Web 端走兜底文案。
 */
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

/**
 * 把异常归类成 [NetworkFailure]；**不是**网络失败（业务代码自己抛的、解析错误等）返回 null。
 *
 * 顺序即优先级，越具体的类型要排在越前面：`SocketTimeoutException` 也是
 * `InterruptedIOException`，`UnknownHostException` / `ConnectException` 也都是 `IOException`。
 */
expect fun classifyNetworkFailure(e: Throwable): NetworkFailure?
