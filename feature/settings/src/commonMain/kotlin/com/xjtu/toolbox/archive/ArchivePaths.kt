package com.xjtu.toolbox.archive

/** 端内名 ↔ 档案路径的唯一映射规则（§2.0）。 */
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

    /** 档案路径 → `(base, suffix)`（[storePath] 的反向，§2.0）；不是 `stores/` 那一层 ⇒ `null`。 */
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

    /** 档案路径 → `(files/ 之下的相对路径, suffix)`（[filePath] 的反向，§2.0）；不是 `files/` 那层 ⇒ `null`。 */
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
