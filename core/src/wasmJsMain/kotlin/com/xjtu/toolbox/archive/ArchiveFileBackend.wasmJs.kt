package com.xjtu.toolbox.archive

/**
 * web 侧的 `files` 层投影（S3）：浏览器没有文件系统（`localStorage` 已由 store 投影覆盖），
 * `files` 那一层在 web 上不存在 ⇒ 两个 actual 都返回 `null`（§1.4 缝 B）。
 *
 * ⚠️ web 侧本批没有测试源集（D14 未做），这条 actual 只吃 `:core:compileKotlinWasmJs` 编译门禁，
 * 运行时证明排在 S8。
 */
actual fun archiveFileBackend(): ArchiveFileBackend? = null

actual fun archiveCacheRoot(): String? = null
