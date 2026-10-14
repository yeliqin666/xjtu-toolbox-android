package com.xjtu.toolbox.archive

import kotlin.time.Clock

/**
 * 统一存档层的**纯函数门面**（S1，零端接线）。
 *
 * 它只做三件**纯事**，一端 I/O 都不碰：
 *  - [export]：把已投影的 store 快照 + `files/` 组装成档案字节映射（A3 字节稳定的落点）；
 *  - [inspect]：自校验一份档案（§6.2 第 3 条的前半，A7 的前半）；
 *  - [import]：§6.4 的逐键合并 + 逐笔落地（A6/A7）。
 *
 * 端内 I/O、迁移、UI 入口分属各端宿主（S2–S6）：档案字节从 [ArchiveSource] 进来、要写的那些键
 * 从 [ArchiveImportTarget] 出去 —— 两端都是窄接口，所以这里不认得任何端。
 */
object StoreArchive {

    /**
     * 纯函数导出：把 host 已投影的 store 快照 + `files/` 组装成档案，
     * 返回 `档案内相对路径 → 字节` 的映射（含 `manifest.json`）。
     *
     * 组装时做的事（全部是纯函数）：
     *  - §2.0：端内名拆 base+suffix → `global/` 或 `accounts/<suffix>/`；
     *  - §8.10 / A8：凭据族默认不写、点名列进 `manifest.redacted`；端内私有（§2.4）永不写；
     *  - §2.1 / A3：`stores[]`/`files[]` 按码点升序、`keys`/`set` 的排序在 codec 里做；
     *  - `createdAt` 来自 [ExportOptions.now] —— 注入固定时钟后两次导出字节相同（A3）。
     *
     * @param accounts 已记录的账号（`accountId` 原值 + `suffix`）。suffix 列表另补 `default`/`_`
     *   两个匿名档（§4：未登录/登出中间态也占目录）。
     */
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

    // ── 自校验（§6.2 第 3 条的前半；S5/S6 各有一份，S1b 收到这里） ───────────────

    /**
     * 自校验一份档案（**纯读**）：`manifest.json` 在不在、`format` 对不对、`version` 认不认识
     * （不认识就明确失败，A7）、每个 `stores/` 下的 `.json` 解不解得开、账号后缀有没有碰撞（§4 / A9）。
     *
     * 不写任何东西；要真正落地就调 [import]（它把这一步当自己的前置）。
     */
    fun inspect(source: ArchiveSource): ArchiveInspection = when (val validated = validate(source)) {
        is Validated.Failed -> ArchiveInspection.Failed(validated.reason)
        is Validated.Ok -> ArchiveInspection.Ok(
            manifest = validated.manifest,
            stores = validated.storePaths,
            files = source.filePaths().size,
        )
    }

    // ── 导入（§6.4 的冲突与合并） ─────────────────────────────────────────────

    /**
     * 把一份档案**合并**进端内（§6.4），返回写了几笔。
     *
     * 顺序是「先全量校验与裁决、再逐笔落地」⇒ 坏档案（缺 manifest / `format` 不对 / `version` 不认识 /
     * JSON 解不开 / 后缀碰撞）在写第一笔之前就返回 [ImportResult.Failed]，**端内零改动**（A7）。
     * 裁决逐键做（§6.4）：
     *  - 本端没有这个键 ⇒ 写入；
     *  - 双方都有 ⇒ 都有 `at` 时新者胜，任一方缺 `at` ⇒ **本端胜**；
     *  - 本端有、档案没有 ⇒ **保持不变**（只增不删，这是 A6 的「不丢键」那一半）；
     *  - `set` 值一律**覆盖**（不并集）—— 并集会让「取消收藏」永远无法同步。
     *
     * 两条不算在 §6.4 里、但必须在这里说清的口径：
     *  - 端内私有名（§2.4 的 `isExcluded`）一律**不写**（与导出侧不写它们是同一条规则的两半）；
     *  - `files/` 那一层没有 `at` 可比 ⇒ 本端已有那个相对路径就**保持不变**，没有才写（§6.4 的
     *    「任一方缺 `at` ⇒ 本端胜」在文件层的同一条读法）。
     *
     * 幂等（§6.1 第 1 条）：导入后再导出一份，第二次导入不再写任何键（本端已是最新）。
     */
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

/** §8.10 / D17：默认不导出凭据族；显式开关时凭据族照写，`redacted` 为空。 */
data class ExportOptions(
    val includeCredentials: Boolean = false,
    /** 固定时钟注入（A3 字节稳定）；默认墙上时钟。 */
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

/**
 * 一份档案的自校验结果（§6.2 第 3 条的前半）。
 *
 * S5（桌面）与 S6（web）当时各写了一份等价的自校验，S1b 收到 `:core` 的 [StoreArchive.inspect]；
 * 两端只剩「把字节交出来」的通道（它们各自的 `ArchiveCheck` 现在就只是这个结果的映射）。
 */
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

/** 导入的结论（§6.4）。[Failed] ⇒ 端内**零改动**（A7 的「坏档案不动端」）。 */
sealed interface ImportResult {

    /**
     * 导入跑完了。三个计数是屏上那句结论文本要的东西，也是一条测试可断言的边界。
     */
    data class Ok(
        /** 真正写进端内的键数（档案有本端没有 + 档案更新）。 */
        val keysWritten: Int,
        /** 本端胜 / 档案里没有 ⇒ 一个字没动的键数。 */
        val keysKept: Int,
        /** 写进端内的 `files/` 条目数。 */
        val filesWritten: Int,
    ) : ImportResult

    /** 明确失败（原因逐字来自自校验）；端内一笔都没写。 */
    data class Failed(val reason: String) : ImportResult
}
