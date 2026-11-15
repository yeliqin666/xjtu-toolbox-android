@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.xjtu.toolbox.platform

import kotlin.random.Random

/**
 * Web（wasmJs）：`crypto.getRandomValues` 取一次 32 位无符号随机数，再用**拒绝采样**削掉取模偏差。
 *
 * 为什么不用 `kotlinx.browser`：0.5.0 的 wasmJs 包里没有 `window.crypto` 成员（拿不到 DOM API），
 * 所以照 `:web` 的 `WebBrowserCapabilities` 的先例用 `@JsFun` 直接写 JS 表达式 —— 见 [jsRandomUint32]。
 *
 * 拒绝采样怎么算：`limit = 2^32 - (2^32 % bound)` —— `[0, limit)` 这一段能被 `bound` 整除，
 * `r % bound` 才是均匀的；落在 `[limit, 2^32)`（不足 `bound` 个数）就重取。期望循环次数 < 2。
 *
 * **降级路径**：`crypto.getRandomValues` 在明文 HTTP 页面里其实也在（只有 `crypto.subtle` 要安全上下文），
 * 但不赌 —— JS 侧取不到它时返回 `-1`，这里退化成 [Random.Default]（可预测）。
 * 那一条只在我们之外的宿主把 `crypto` 抹掉时才会走到：弱随机，但调用方（匿名 ID / 请求号 / 一次性令牌）不会崩。
 */
actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound 必须为正" }
    // 2^32：`Uint32Array` 的一个元素能给出的全部取值个数。
    val range = 0x1_0000_0000L
    val limit = range - range % bound
    while (true) {
        val raw = jsRandomUint32()
        if (raw < 0.0) return Random.Default.nextInt(bound) // 见 KDoc 的降级路径
        val r = raw.toLong()
        if (r < limit) return (r % bound).toInt() // 重取：`r` 落在 `[limit, 2^32)` 那一小段
    }
}

/** 一次 32 位无符号随机数（`[0, 2^32)`，Double 能精确装下）；取不到 `crypto.getRandomValues` 时返回 `-1.0`。 */
@JsFun(
    "() => {" +
        " const c = globalThis.crypto;" +
        " if (typeof c === 'undefined' || !c || typeof c.getRandomValues !== 'function') return -1;" +
        " const a = new Uint32Array(1);" +
        " c.getRandomValues(a);" +
        " return a[0];" +
        " }",
)
private external fun jsRandomUint32(): Double
