package com.xjtu.toolbox.core.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 用 OkHttp 引擎。
 *
 * 与 jvmMain 那份实现一字不差 —— 只有一行，不值得为它造中间源集（jvm + android 的
 * 公共父源集）。等这里长到需要共享的东西不止一行时再抽。
 *
 * 关系变化是这一层的重点：App 现在有 79 个文件**直接用 okhttp3 做业务调用**；
 * 搬进 :core 之后，okhttp 只作为 Ktor 的一个引擎存在，业务层不再认识 okhttp 类型。
 * iOS 用 darwin、Web 用 js —— 换引擎不碰业务代码，就是 :core 存在的意义。
 */
actual fun toolboxEngine(): HttpClientEngine = OkHttp.create()
