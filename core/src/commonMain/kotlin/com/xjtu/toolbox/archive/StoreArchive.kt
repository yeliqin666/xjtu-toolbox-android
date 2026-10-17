package com.xjtu.toolbox.archive

import kotlin.time.Clock

/** 统一存档层的纯函数门面。 */
object StoreArchive {

    /** 纯函数导出：把 host 已投影的 store 快照 + `files/` 组装成档案，返回 `档案内相对路径 → 字节` 的映射（含 `manifest.json`）。 */
    fun export(
        accounts: List<ArchiveAccount>,
        createdBy: String,
        appVersion: String,
        stores: List<StoreSnapshot>,
        files: List<ArchiveFile>,
        options: ExportOptions = ExportOptions(),
    ): Map<String, String> {
        val suffixes = accounts.map { it.suffix } + listOf("default", "_")
        val ids = ArchiveNames.split(stores.map { it.rawName }, suffixes)

        val out = linkedMapOf<String, String>()
        val storeNames = mutableSetOf<String>()
        val redacted = mutableListOf<String>()

        stores.zip(ids).forEach { (snapshot, id) ->
            val base = id.base
            if (ArchiveRegistry.isExcluded(base)) return@forEach
            val path = ArchivePaths.storePath(base, id.suffix)
            if (ArchiveRegistry.isCredential(base)) {
                if (!options.includeCredentials) {
                    redacted += path
                    return@forEach
                }
            }
            storeNames += base
            out[path] = ArchiveCodec.encodeStore(snapshot, base, id.suffix)
        }

        files.forEach { f ->
            out[ArchivePaths.filePath(f.suffix, f.relPath)] = f.content
        }

        val manifest = ArchiveManifest(
            createdAt = ArchiveCodec.formatTimestamp(options.now()),
            createdBy = createdBy,
            appVersion = appVersion,
            noAccount = accounts.isEmpty(),
            accounts = accounts,
            stores = storeNames.sorted(),
            files = files.map { it.relPath }.distinct().sorted(),
            redacted = redacted.sorted(),
        )
        out["manifest.json"] = ArchiveCodec.encodeManifest(manifest)
        return out
    }


    /** 自校验一份档案（纯读）：`manifest.json` 在不在、`format` 对不对、`version` 认不认识（不认识就明确失败）、每个 `stores/` 下的 `.json` 解不解得开、账号后缀有没有碰撞（§4）。 */
    fun inspect(source: ArchiveSource): ArchiveInspection = when (val validated = validate(source)) {
        is Validated.Failed -> ArchiveInspection.Failed(validated.reason)
        is Validated.Ok -> ArchiveInspection.Ok(
            manifest = validated.manifest,
            stores = validated.storePaths,
            files = source.filePaths().size,
        )
    }

    // ── 导入（§6.4 的冲突与合并） ─────────────────────────────────────────────

    /** 把一份档案合并进端内（§6.4），返回写了几笔。 */
    fun import(source: ArchiveSource, target: ArchiveImportTarget): ImportResult {
        val validated = when (val v = validate(source)) {
            is Validated.Failed -> return ImportResult.Failed(v.reason)
            is Validated.Ok -> v
        }

        var keysWritten = 0
        var keysKept = 0
        validated.records.forEach { record ->
            val snapshot = record.snapshot
            if (ArchiveRegistry.isExcluded(record.base)) return@forEach
            val local = target.readStore(snapshot.rawName)
            snapshot.keys.forEach { (key, value) ->
                val at = snapshot.writtenAt[key]
                if (archiveWins(key, at, local)) {
                    target.putKey(snapshot.rawName, key, value, at)
                    keysWritten++
                } else {
                    keysKept++
                }
            }
        }

        var filesWritten = 0
        source.filePaths().forEach { path ->
            val (relPath, suffix) = ArchivePaths.parseFilePath(path) ?: return@forEach
            if (target.readFile(suffix, relPath) != null) return@forEach
            val content = source.readFile(path) ?: return@forEach
            target.putFile(suffix, relPath, content)
            filesWritten++
        }

        return ImportResult.Ok(keysWritten = keysWritten, keysKept = keysKept, filesWritten = filesWritten)
    }

    /** §6.4：本端没有这个键 ⇒ 写入；有 ⇒ 双方都有 `at` 时新者胜（相同也算本端胜），缺一方 ⇒ 本端胜。 */
    private fun archiveWins(key: String, archiveAt: Long?, local: StoreSnapshot?): Boolean {
        if (local == null || !local.keys.containsKey(key)) return true
        val localAt = local.writtenAt[key] ?: return false
        if (archiveAt == null) return false
        return archiveAt > localAt
    }

    /** 校验 + 解码（[inspect] 与 [import] 的共同前置）；任何一步不对就是一句明确的原因。 */
    private fun validate(source: ArchiveSource): Validated {
        val storePaths = source.storeNames()
        val manifestJson = source.readManifest() ?: return Validated.Failed("缺 manifest.json")
        val manifest = runCatching { ArchiveCodec.decodeManifest(manifestJson) }.getOrElse {
            return Validated.Failed("manifest.json 解不开：${it.message ?: it::class.simpleName}")
        }
        if (manifest.format != ARCHIVE_FORMAT) return Validated.Failed("format 不是 $ARCHIVE_FORMAT")
        if (manifest.version != ARCHIVE_VERSION) return Validated.Failed("不认识的版本 ${manifest.version}")

        val collisions = ArchiveNames.detectCollision(manifest.accounts)
        if (collisions.isNotEmpty()) return Validated.Failed("账号后缀碰撞：${collisions.joinToString()}")

        val records = mutableListOf<ArchiveCodec.StoreRecord>()
        storePaths.forEach { path ->
            val json = source.readStore(path) ?: return Validated.Failed("$path 读不出来")
            records += runCatching { ArchiveCodec.decodeStore(json) }.getOrElse {
                return Validated.Failed("$path 解不开：${it.message ?: it::class.simpleName}")
            }
        }
        return Validated.Ok(manifest, storePaths.sorted(), records)
    }

    private sealed interface Validated {
        data class Ok(
            val manifest: ArchiveManifest,
            /** 档案里 `stores/` 那一层的相对路径（码点升序）。 */
            val storePaths: List<String>,
            val records: List<ArchiveCodec.StoreRecord>,
        ) : Validated

        data class Failed(val reason: String) : Validated
    }
}

/** 默认不导出凭据族（§8.10）；显式开关时照写，`redacted` 为空。 */
data class ExportOptions(
    val includeCredentials: Boolean = false,
    /** 固定时钟注入；默认墙上时钟。 */
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

/** 一份档案的自校验结果（§6.2 第 3 条的前半）。 */
sealed interface ArchiveInspection {

    /** 校验通过。 */
    data class Ok(
        /** 解出来的 `manifest.json`（§2.1）。 */
        val manifest: ArchiveManifest,
        /** 档案里 `stores/` 那一层的相对路径（码点升序）。 */
        val stores: List<String>,
        /** `files/` 那一层的条目数。 */
        val files: Int,
    ) : ArchiveInspection

    /** 明确失败：缺 `manifest.json` / `format` 不对 / `version` 不认识 / JSON 解不开 / 后缀碰撞。 */
    data class Failed(val reason: String) : ArchiveInspection
}

/** 导入的结论（§6.4）。[Failed] ⇒ 端内零改动。 */
sealed interface ImportResult {

    /** 导入跑完了。 */
    data class Ok(
        /** 真正写进端内的键数（档案有本端没有 + 档案更新）。 */
        val keysWritten: Int,
        /** 本端胜 / 档案里没有的键数。 */
        val keysKept: Int,
        /** 写进端内的 `files/` 条目数。 */
        val filesWritten: Int,
    ) : ImportResult

    /** 明确失败；端内一笔都没写。 */
    data class Failed(val reason: String) : ImportResult
}
