package com.xjtu.toolbox.archive

/**
 * 端内 `files` 那一层的**只读投影**（§1.4 缝 B，S3）。
 *
 * 与 store 投影（[ArchiveStoreBackend]）并列：这一格只覆盖「端内真实是文件」的两族 ——
 *  - `files/agent_sessions/…` ⇄ 端内 `agent_sessions<suffix>/`（§2.3 / `AgentSessionStore`）；
 *  - `files/schedule/<key>.json` ⇄ Android 的 `cacheDir/data_cache<suffix>/<key>.json`（§2.3 / §8.7）。
 * Room 三张表（`files/courses` / `files/media` / `files/jiaocai` 下的 `.json`）的端内源是 Room 表、
 * 空教室缓存（`files/cache/empty_room/…`）的端内源是 KV 店 —— 都不在这一格，由 S4–S6 的宿主经
 * [ArchiveFileRows] 与 store 投影组装。
 *
 * `relPath` 一律是 `files/` 之下的相对路径（`agent_sessions/index.json`、`schedule/<key>.json`），
 * **目录名不带 suffix**（suffix 已经在 `accounts/<suffix>/` 那一层，§2.3 末段）；`suffix` 由调用方给
 * （来自 manifest / AccountContext），逐字进端内目录名 `agent_sessions<suffix>` / `data_cache<suffix>`。
 *
 * web 没有文件系统 ⇒ [archiveFileBackend] 返回 `null`（§1.4 缝 B 的 wasm actual）。
 */
interface ArchiveFileBackend {
    /** 枚举某个账号档下 `files/` 某前缀的相对路径，按码点升序（§2.3）。 */
    fun list(suffix: String, prefix: String): List<String>

    /** 读某个账号档下 `files/` 的一个相对路径；不存在 ⇒ null。 */
    fun read(suffix: String, relPath: String): String?
}

/** 本端的 `files` 层投影；wasm = null（浏览器没有文件系统）。 */
expect fun archiveFileBackend(): ArchiveFileBackend?

/**
 * 本端「缓存根目录」的绝对路径（`data_cache*` 的父目录，§8.7 / `DataCache` 的落点）。
 *  - Android = `context.cacheDir`；
 *  - jvm    = `null`（桌面课表族在 KV 店，没有 `cacheDir` 这一层）；
 *  - web    = `null`。
 */
expect fun archiveCacheRoot(): String?
