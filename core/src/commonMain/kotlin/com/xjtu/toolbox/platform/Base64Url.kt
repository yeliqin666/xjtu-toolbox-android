package com.xjtu.toolbox.platform

/**
 * 平台能力：**URL-safe Base64（无填充）** —— 分享码本体的另一头。
 *
 * 与 [deflateBytes] 同一条缝的兄弟：分享码是「明文 → deflate → URL-safe Base64（去掉 `=` 填充）」
 * 拼出来的（见 `:core` 的 `MatchProfile`）。这一步原先在 `:app` 里是 `java.util.Base64`
 * （`getUrlEncoder().withoutPadding()`，minSdk 31 远高于它要求的 26），在 `:core` 的
 * commonMain 里同样不存在 ⇒ 切成平台能力。
 *
 * | | 这一端 | 怎么实现 |
 * |---|---|---|
 * | Android（`:app`） | `java.util.Base64` 的 URL 编码器（无填充） | **与搬迁前逐字相同** |
 * | jvm（`:desktop`） | 同一份 `java.util.Base64` | 同上 |
 * | wasmJs（`:web`） | **没有**：两件都返回「空 / null」 | 见 [deflateBytes] 文件头那条 TODO |
 *
 * ## 为什么 wasmJs 不顺手用纯 Kotlin 补一份
 *
 * Base64 本身二十行就能写（浏览器也有 `btoa` 的无填充变体），但**单有它没有 deflate**
 * 一样出不了码（两者是链条上的先后两步），而 Web 端那条路由今天没点亮、也没有 wasm 的测试
 * 运行器能钉住它 ⇒ 补一份没人跑得到的实现在仓库里只是负债。真要做 Web 端时，两条一起做
 * （见 [deflateBytes] 的 TODO），那时的验收才落得下去。
 */

/** URL-safe Base64、**不带 `=` 填充**（分享码就长这样）。 */
expect fun base64UrlEncode(bytes: ByteArray): String

/** URL-safe Base64 解码。**接受带填充与不带填充两种**；不是合法 Base64 时返回 null。 */
expect fun base64UrlDecode(text: String): ByteArray?

/** 这一端有没有上面那两件（`false` 的端上编码返回空串、解码返回 null）。 */
expect val base64Available: Boolean
