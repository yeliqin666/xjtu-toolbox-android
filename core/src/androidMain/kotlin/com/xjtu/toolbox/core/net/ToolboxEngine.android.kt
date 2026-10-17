package com.xjtu.toolbox.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/** Android 用 OkHttp 引擎。 */
actual fun toolboxEngine(): HttpClientEngine = OkHttp.create()
