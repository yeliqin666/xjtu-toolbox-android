package com.xjtu.toolbox.platform

import java.io.File

/**
 * 桌面：与凭据 / cookie / 站点快照那批**同一个数据根目录**（`~/.local/share/xjtu-toolbox/`，
 * 测试与多实例场景可以改 [dataRootOverride]）。
 *
 * 目录本身不在这里创建 —— 调用方写的是一份具体文件，父目录由写入那一侧按需 `mkdirs()`
 * （`FileKeyValueStore.persist()` 就是这么做的）。
 */
actual fun dataFilesDir(): File = dataRoot()
