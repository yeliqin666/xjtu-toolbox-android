package com.xjtu.toolbox.platform

/**
 * Web：**不分类**，如实返回 null。
 *
 * Ktor 的 js 引擎把 fetch 失败包成 `JsError` / `TypeError` —— 动态类型，没有稳定的类型层级，
 * KMP 侧只能读到它的 `name` / `message`。而「按消息猜关键字」正是 `FriendlyError` 明确要避开
 * 的做法（历史上就因为消息里出现 `token` / `404` 而误判），所以这里不装作能认：
 * Web 端一律走「失败，请稍后重试」的兜底文案。
 *
 * 真要分类时的正确做法（等 Web 端有实际需要再做）：在 `js` 侧用 interop 判
 * `TypeError`（断网/DNS）与 `AbortError`（超时）两个 name，各映射一档。
 */
actual fun classifyNetworkFailure(e: Throwable): NetworkFailure? = null
