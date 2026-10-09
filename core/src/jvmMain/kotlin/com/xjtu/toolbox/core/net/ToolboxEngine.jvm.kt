package com.xjtu.toolbox.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * jvm（以及将来的 Android）用 OkHttp 引擎。
 *
 * 注意这里的关系变了：App 现在是**直接用 okhttp3 做业务调用**（79 个文件），
 * 探针之后 okhttp 只作为 Ktor 的一个引擎存在——业务层不再认识 okhttp 类型。
 * 这样 iOS/wasm 才有地方插自己的引擎。
 */
actual fun toolboxEngine(): HttpClientEngine = OkHttp.create()
