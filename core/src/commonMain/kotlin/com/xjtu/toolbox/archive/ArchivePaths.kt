package com.xjtu.toolbox.archive

/**
 * 端内名 ↔ 档案路径的**唯一映射规则**（§2.0，A13 的可断言形式）。
 *
 * 端内名 = `<store>` + （账号档 ? `<suffix>` : `""`）：
 *  `accounts/_2253415556/stores/schedule_cache.json` ⇄ `schedule_cache_2253415556`
 *  `global/stores/app_settings.json`                 ⇄ `app_settings`
 *
 * suffix 为 `""` ⇒ `global/`；否则 ⇒ `accounts/<suffix>/`。目录名用 suffix 逐字，
 * 保证映射是**纯字符串拼接**（§2.0 / A13）。
 */
object ArchivePaths {

    /** 端内 base + suffix → 档案里 `stores/` 下的相对路径。 */
    fun storePath(base: String, suffix: String): String =
        if (suffix.isEmpty()) "global/stores/$base.json"
        else "accounts/$suffix/stores/$base.json"

    /** 档案路径的反向：base + suffix = 端内 store 名（§2.0）。 */
    fun rawStoreName(base: String, suffix: String): String = base + suffix

    /** `files/` 的相对路径 → 档案里的完整相对路径（§2.0 / §2.3）。 */
    fun filePath(suffix: String, relPath: String): String =
        if (suffix.isEmpty()) "global/files/$relPath"
        else "accounts/$suffix/files/$relPath"

    /**
     * 档案路径 → `(base, suffix)`（[storePath] 的反向，§2.0）；不是 `stores/` 那一层 ⇒ `null`。
     *
     * 导入侧（S1b）用它把档案里那条路径对回端内名 —— 与 [storePath] 同一条「纯字符串」口径，
     * 所以 `global/stores/app_settings.json` ⇄ `("app_settings", "")`、
     * `accounts/_2253415556/stores/schedule_cache.json` ⇄ `("schedule_cache", "_2253415556")`。
     */
    fun parseStorePath(path: String): Pair<String, String>? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val suffix = when {
            segments.size == 3 && segments[0] == "global" && segments[1] == "stores" -> ""
            segments.size == 4 && segments[0] == "accounts" && segments[2] == "stores" -> segments[1]
            else -> return null
        }
        val fileName = segments.last()
        if (!fileName.endsWith(".json")) return null
        val base = fileName.removeSuffix(".json")
        if (base.isEmpty()) return null
        return base to suffix
    }

    /**
     * 档案路径 → `(files/ 之下的相对路径, suffix)`（[filePath] 的反向，§2.0）；不是 `files/` 那层 ⇒ `null`。
     *
     * 与 store 那一层的区别：`files/` 的相对路径**可以带 `/`**（`agent_sessions/index.json`）
     * ⇒ 只按前两段/三段判形状，剩下的一律是相对路径。
     */
    fun parseFilePath(path: String): Pair<String, String>? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val suffix: String
        val relPath: String
        when {
            segments.size >= 3 && segments[0] == "global" && segments[1] == "files" -> {
                suffix = ""
                relPath = segments.drop(2).joinToString("/")
            }
            segments.size >= 4 && segments[0] == "accounts" && segments[2] == "files" -> {
                suffix = segments[1]
                relPath = segments.drop(3).joinToString("/")
            }
            else -> return null
        }
        if (relPath.isEmpty()) return null
        return relPath to suffix
    }
}
