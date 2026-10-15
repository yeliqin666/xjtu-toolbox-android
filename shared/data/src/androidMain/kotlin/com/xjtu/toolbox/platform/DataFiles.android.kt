package com.xjtu.toolbox.platform

import java.io.File

/** Android：`Context.filesDir`。 */
actual fun dataFilesDir(): File = requireDataPlatformContext().filesDir
