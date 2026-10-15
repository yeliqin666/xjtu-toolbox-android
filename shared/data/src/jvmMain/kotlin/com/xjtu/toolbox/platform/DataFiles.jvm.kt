package com.xjtu.toolbox.platform

import java.io.File

/** 桌面：与凭据 / cookie / 站点快照那批同一个数据根目录（`~/.local/share/xjtu-toolbox/`，测试与多实例场景可以改 [dataRootOverride]）。 */
actual fun dataFilesDir(): File = dataRoot()
