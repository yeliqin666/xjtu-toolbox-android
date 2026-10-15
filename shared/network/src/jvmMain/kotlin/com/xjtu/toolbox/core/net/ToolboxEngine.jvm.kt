package com.xjtu.toolbox.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/** jvm 用 OkHttp 引擎。 */
actual fun toolboxEngine(): HttpClientEngine = OkHttp.create()
