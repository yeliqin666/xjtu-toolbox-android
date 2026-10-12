package com.xjtu.toolbox.platform

import java.io.File

/**
 * Android：就是 `Context.filesDir` —— 与搬迁前 `AgentSessionStore` 用的**同一个目录**。
 *
 * `Context` 由 `:app` 在 `Application.onCreate` 里交给 [initDataPlatform]（与其余 Android actual
 * 同一个句柄），所以这里读的是那个已注入的 `applicationContext`。
 */
actual fun dataFilesDir(): File = requireDataPlatformContext().filesDir
