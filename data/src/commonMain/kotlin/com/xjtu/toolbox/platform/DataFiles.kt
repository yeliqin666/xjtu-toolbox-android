package com.xjtu.toolbox.platform

import java.io.File

/**
 * 本端的**数据根目录**（不是缓存目录）。
 *
 * 为什么需要这一格：`AgentSessionStore`（会话的 JSON 文件）原来抓 `Context.filesDir`，
 * 搬进 `:data` 之后 commonMain 不认识 `Context`。`KeyValueStore` 那一族缝解决的是
 * 「一堆键值」，这一件解决的是「一个目录里的若干 JSON 文件」—— 会话是**一会话一文件**
 * （`index.json` + `convo_<id>.json`，对标 opencode 早期实现），塞进键值存储就等于换落盘格式，
 * 老用户的会话会读不出来。
 *
 * | 端 | 根目录 |
 * |---|---|
 * | Android | `Context.filesDir`（与搬迁前**同一个目录**，路径逐字不变） |
 * | 桌面 | `~/.local/share/xjtu-toolbox/`（XDG 口径，与凭据 / cookie 那批同一个根，见 `SecureStore.jvm.kt`） |
 */
expect fun dataFilesDir(): File
