package com.xjtu.toolbox.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

/** Web 端（Kotlin/Wasm）用 Ktor 的 JS 引擎（底层就是浏览器 fetch）。 */
actual fun toolboxEngine(): HttpClientEngine = Js.create()
