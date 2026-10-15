package com.xjtu.toolbox.platform

/** 平台能力：URL-safe Base64（无填充） —— 分享码本体的另一头。 */

/** URL-safe Base64、**不带** `=` 填充（分享码就长这样）。 */
expect fun base64UrlEncode(bytes: ByteArray): String

/** URL-safe Base64 解码。**接受带填充与不带填充两种**；不是合法 Base64 时返回 null。 */
expect fun base64UrlDecode(text: String): ByteArray?

/** 这一端有没有上面那两件（`false` 的端上编码返回空串、解码返回 null）。 */
expect val base64Available: Boolean
