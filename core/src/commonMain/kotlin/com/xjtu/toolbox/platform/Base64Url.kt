package com.xjtu.toolbox.platform

/**
 * 平台能力：**URL-safe Base64（无填充）** —— 分享码本体的另一头。
 *
 * 与 [deflateBytes] 同一条缝的兄弟：分享码是「明文 → zlib → URL-safe Base64（去掉 `=` 填充）」
 * 拼出来的（见 `:core` 的 `MatchProfile`）。这一步原先在 `:app` 里是 `java.util.Base64`
 * （`getUrlEncoder().withoutPadding()`，minSdk 31 远高于它要求的 26），在 `:core` 的
 * commonMain 里同样不存在 ⇒ 切成平台能力。
 *
 * | | 这一端 | 怎么实现 |
 * |---|---|---|
 * | Android（`:app`） | `java.util.Base64` 的 URL 编码器（无填充） | **与搬迁前逐字相同** |
 * | jvm（`:desktop`） | 同一份 `java.util.Base64` | 同上 |
 * | wasmJs（`:web`） | [UrlSafeBase64]（纯 Kotlin，`commonMain`） | 2026-10-15（D12 ②）接上：`base64Available` 在三端都是 true |
 *
 * 原先这里写着「wasmJs 上不给：单有 Base64 没有 deflate 一样出不了码，所以两件一起做」——
 * 那正是这一批（D12 ②）做的事：两件一起补进 `commonMain`，浏览器的分享码生成与解析因此真能用。
 * 那一份纯 Kotlin 实现与 JDK 的 URL 编解码器**逐字节同结果**（编码一致、解码的宽松边界也一致，
 * 见 `:core:jvmTest` 的 `UrlSafeBase64JvmTest`）—— Base64 这一步不像压缩那样允许「字节不同」。
 */

/** URL-safe Base64、**不带** `=` 填充（分享码就长这样）。 */
expect fun base64UrlEncode(bytes: ByteArray): String

/** URL-safe Base64 解码。**接受带填充与不带填充两种**；不是合法 Base64 时返回 null。 */
expect fun base64UrlDecode(text: String): ByteArray?

/** 这一端有没有上面那两件（`false` 的端上编码返回空串、解码返回 null）。 */
expect val base64Available: Boolean
