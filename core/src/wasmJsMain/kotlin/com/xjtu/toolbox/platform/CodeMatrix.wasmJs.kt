package com.xjtu.toolbox.platform

/**
 * Web（wasmJs）侧的码阵实现：**转调 `:core` commonMain 里那份纯 Kotlin 编码器**。
 *
 * zxing（`QRCodeWriter` / `Code128Writer`）只有 JVM/Android 那一份 jar，进不了 wasmJs；
 * 而浏览器这一端要画真码（付款码那两个矩阵、分享码的二维码）⇒ 编码器按「三端一份」的做法
 * 搬进 commonMain：二维码是 [QrCodec]，条形码是 [Code128Codec]。
 *
 * | | 这一端 |
 * |---|---|
 * | 二维码 | [QrCodec.encode]（单个模式、纠错 L、UTF-8、不留静默区 —— 与 zxing 那条路逐字同参数） |
 * | 条形码 | [Code128Codec.encode]（code A/B/C 自动选集 + 校验位 + 停止符，静默区每侧 5 个模块） |
 * | 与 Android 的关系 | **逐模块相同**：`:core:jvmTest` 的 `CodeMatrixParityJvmTest` 拿同一批输入同时跑这一份与 zxing 那份 actual，逐格断言相等；androidMain / jvmMain 两份 actual **一行未改**（红线：Android 行为逐字不变） |
 *
 * 编不出来（内容太长、装不进 40 版；或条形码里出现非 ASCII 字符）时与那两份 actual 一样返回 null。
 */
actual fun qrCodeModules(text: String): CodeMatrix? = QrCodec.encode(text)

actual fun code128Modules(text: String): CodeMatrix? = Code128Codec.encode(text)
