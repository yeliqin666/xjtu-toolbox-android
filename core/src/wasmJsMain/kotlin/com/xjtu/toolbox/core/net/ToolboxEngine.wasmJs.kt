package com.xjtu.toolbox.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

/**
 * Web 端（Kotlin/Wasm）用 Ktor 的 JS 引擎（底层就是浏览器 fetch）。
 *
 * 与 jvm/Android 的差别不只是「换了个库」：
 *  - 浏览器有 **CORS**：直连 `jwxt.xjtu.edu.cn` 大概率拿不到 `Access-Control-Allow-Origin`，
 *    所以 Web 端必须走**同源反代**（campus-api + nginx）——这一条决定了 web 端的数据源
 *    不是「可选的」，而是「必须的」。留给下一步的浏览器探针实测。
 *  - 没有 `javax.crypto`：WebVPN 那套加解密在 wasm 上要么用 WebCrypto，要么放服务端。
 */
actual fun toolboxEngine(): HttpClientEngine = Js.create()
