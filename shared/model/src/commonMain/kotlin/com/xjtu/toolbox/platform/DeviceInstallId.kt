package com.xjtu.toolbox.platform

/** 平台能力：本机稳定匿名标识（哈希过，不含学号/姓名/IMEI）。 */
expect fun deviceInstallId(salt: String): String?
