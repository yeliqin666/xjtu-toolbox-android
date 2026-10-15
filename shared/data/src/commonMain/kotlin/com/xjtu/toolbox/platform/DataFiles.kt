package com.xjtu.toolbox.platform

import java.io.File

/** 本端的数据根目录（不是缓存目录）。 */
expect fun dataFilesDir(): File
