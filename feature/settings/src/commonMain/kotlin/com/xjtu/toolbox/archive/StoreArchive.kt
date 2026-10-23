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
            out[path] = ArchiveCodec.encodeStore(declaredTypes(snapshot, base), base, id.suffix)
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

    /**
     * 按【声明类型】写值（`ArchiveRegistry.isStringSet`）：声明的键强制成 `SetValue` ⇒ 档案里是 `t="set"` + 数组。
     *
     * 为什么靠声明而不是靠猜：端内存储只有字符串（web 逗号 join、桌面 `\u0000` join、Android 真集合），
     * “只看字符串”反推不出集合语义——单元素集合 `42` 看着像 int、空集合就是空字符串，
     * 导出成 `t="i"` / `t="s"` 后到 Android 那边用 `getStringSet` 一读就是 `ClassCastException`。
     * 没声明的键原样放过（不在此处做档案形状统一 / 键名归一）。
     */
    private fun declaredTypes(snapshot: StoreSnapshot, base: String): StoreSnapshot {
        val keys = snapshot.keys.entries.associate { (key, value) -> key to declaredValue(base, key, value) }
        return if (keys == snapshot.keys) snapshot else snapshot.copy(keys = keys)
    }

    /**
     * 一个键的【声明类型】归一：声明的键（[ArchiveRegistry.isStringSet]）⇒ 摊成 [StoreValue.SetValue]；
     * 没声明的键原样返回（不在此处做档案形状统一 / 键名归一）。
     *
     * 导出侧（[export]）与导入侧（[import]）共用这一条，方向相反但口径相同：导出侧据此**写** `t="set"`，
     * 导入侧据此**读**——老档案里同一个键可能是 `t="i"`（单元素集合被猜成 int）或 `t="s"` + `""`（空集合），
     * Android 落它用 `getStringSet`，读到一个非集合值就是 `ClassCastException`。
     */
    private fun declaredValue(base: String, key: String, value: StoreValue): StoreValue =
        if (ArchiveRegistry.isStringSet(base, key)) asStringSet(value) else value

    /**
     * 把反推出来的值摊成字符串集合：已是集合的原样返回；字符串按分隔符拆（web 逗号 / 桌面 `\u0000` 都支持）；
     * 被猜成 `b/i/l/f` 的标量就是一个元素（如单收藏 `42`）；空串 = 空集合（不是不存在的键）。
     */
    private fun asStringSet(value: StoreValue): StoreValue.SetValue = when (value) {
        is StoreValue.SetValue -> value
        is StoreValue.S -> StoreValue.SetValue(
            value.v.split(',', '\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        )
        is StoreValue.B -> StoreValue.SetValue(listOf(value.v.toString()))
        is StoreValue.I -> StoreValue.SetValue(listOf(value.v.toString()))
        is StoreValue.L -> StoreValue.SetValue(listOf(value.v.toString()))
        is StoreValue.F -> StoreValue.SetValue(listOf(value.v.toString()))
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

    // ── 导入（§6.4 的冲突与合并；同一节里还有「显式镜像档」） ──────────────

    /**
     * 算一次导入的差异，**端内一个字节都不动**。
     *
     * 三端同一套两步流（§6.4「显式镜像档」）：先算差异把「将删除 N 个键 / 覆盖 M 个键」摆给用户看，
     * 用户确认之后再用 [apply] 一次性落地 —— 中途失败整体回滚（§6.1 第 3 条）。
     */
    fun plan(
        source: ArchiveSource,
        target: ArchiveImportTarget,
        mode: ArchiveImportMode = ArchiveImportMode.MERGE,
    ): ArchivePlanOutcome {
        val validated = when (val v = validate(source)) {
            is Validated.Failed -> return ArchivePlanOutcome.Failed(v.reason)
            is Validated.Ok -> v
        }
        val mirror = mode == ArchiveImportMode.MIRROR
        val steps = mutableListOf<ArchiveImportStep>()
        var diff = ArchiveImportDiff()

        validated.records.forEach record@{ record ->
            if (ArchiveRegistry.isExcluded(record.base)) return@record
            val snapshot = record.snapshot
            val local = target.readStore(snapshot.rawName)
            snapshot.keys.forEach keyLoop@{ (key, value) ->
                val at = snapshot.writtenAt[key]
                // 归一与落地整条都不许往上抛（§8.6）：归一不了 ⇒ 这一笔不写，计数上等于本端胜。
                val declared = runCatching { declaredValue(record.base, key, value) }.getOrNull()
                if (declared == null) {
                    diff = diff.copy(keysKept = diff.keysKept + 1)
                    return@keyLoop
                }
                val missing = local == null || !local.keys.containsKey(key)
                val overwrite = missing || mirror || archiveWins(key, at, local)
                if (!overwrite) {
                    diff = diff.copy(keysKept = diff.keysKept + 1)
                    return@keyLoop
                }
                steps += ArchiveImportStep.PutKey(snapshot.rawName, key, declared, at)
                diff = if (missing) {
                    diff.copy(keysAdded = diff.keysAdded + 1)
                } else {
                    diff.copy(keysOverwritten = diff.keysOverwritten + 1)
                }
            }

            // 镜像档只对「档案里声明过的这个店」对齐：把档案里没有的键删掉（红线清单除外）。
            if (!mirror || local == null) return@record
            val stale = local.keys.keys.filter { key -> !snapshot.keys.containsKey(key) }
            if (stale.isEmpty()) return@record
            if (ArchiveRedLines.isProtected(record.base)) {
                diff = diff.copy(keysProtected = diff.keysProtected + stale.size)
            } else {
                stale.forEach { key -> steps += ArchiveImportStep.RemoveKey(snapshot.rawName, key) }
                diff = diff.copy(keysDeleted = diff.keysDeleted + stale.size)
            }
        }

        // `accounts/<suffix>` → 学号（§6.3 键名归一时把端内名拼回去要用它）
        val accountIds = validated.manifest.accounts.associate { it.suffix to it.accountId }
        // 档案里出现过的 (suffix, 族) → 这一族在端内该有的相对路径；以及这一族里拼不出来的条数。
        val declaredFamilies = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val unmapped = mutableMapOf<Pair<String, String>, Int>()
        source.filePaths().forEach fileLoop@{ path ->
            val (relPath, suffix) = ArchivePaths.parseFilePath(path) ?: return@fileLoop
            val family = suffix to relPath.substringBefore('/')
            // 落地整条都不许往上抛：拼不出端内名 / 转不动形状 / 读不出来 ⇒ 只丢这一条并计数（§8.6）。
            val landing = runCatching {
                landFile(suffix, accountIds[suffix], relPath, path, source, target, existingWins = !mirror)
            }.getOrElse { FileLanding.Skipped }
            when (landing) {
                is FileLanding.Write -> {
                    steps += ArchiveImportStep.PutFile(landing.suffix, landing.relPath, landing.content)
                    declaredFamilies.getOrPut(family) { mutableSetOf() } += landing.relPath
                    diff = if (landing.overwrite) {
                        diff.copy(filesOverwritten = diff.filesOverwritten + 1)
                    } else {
                        diff.copy(filesAdded = diff.filesAdded + 1)
                    }
                }
                is FileLanding.Present -> {
                    declaredFamilies.getOrPut(family) { mutableSetOf() } += landing.relPath
                    diff = diff.copy(filesUnchanged = diff.filesUnchanged + 1)
                }
                FileLanding.Skipped -> {
                    unmapped[family] = (unmapped[family] ?: 0) + 1
                    diff = diff.copy(filesSkipped = diff.filesSkipped + 1)
                }
            }
        }

        if (mirror) {
            declaredFamilies.forEach { (family, keep) ->
                // 这一族里只要有一条拼不出端内名，就整族不删（保守口径：拿不准就不删）。
                if ((unmapped[family] ?: 0) > 0) return@forEach
                val (suffix, name) = family
                val local = runCatching { target.listFiles(suffix, name) }.getOrElse { emptyList<String>() }
                local.filterNot { it in keep }.forEach { stale ->
                    steps += ArchiveImportStep.RemoveFile(suffix, stale)
                    diff = diff.copy(filesDeleted = diff.filesDeleted + 1)
                }
            }
        }

        return ArchivePlanOutcome.Ok(ArchiveImportPlan(mode, diff, steps))
    }

    /**
     * 把算好的 [plan] 一次性落地（§6.1 第 3 条：要么全落，要么端内回到 apply 之前）。
     *
     * 默认档保留导入侧的老容错（§8.6：坏一条只丢这一条，别的键照写）；
     * 镜像档会删数据，所以它**不容错** —— 有一笔不顺就整体回滚并报 [ImportResult.Failed]。
     */
    fun apply(plan: ArchiveImportPlan, target: ArchiveImportTarget): ImportResult {
        val tolerant = plan.mode == ArchiveImportMode.MERGE
        val undo = mutableListOf<ArchiveUndo>()
        var keysWritten = 0
        // 本端胜 / 档案里没有的键在算差异时已经数完了（§8.6 的老口径），落地只再加「写不进去」的那几条。
        var keysKept = plan.diff.keysKept
        var keysDeleted = 0
        var filesWritten = 0
        var filesDeleted = 0
        // 同理：算差异时就跳过的那几条（§6.3 残尾）已经在 diff 里，落地只再加「写不进去」的那几条。
        var filesSkipped = plan.diff.filesSkipped

        for (step in plan.steps) {
            // 每一笔都先记「原来是什么」再动，回滚才有东西可恢复。
            val landed = runCatching {
                when (step) {
                    is ArchiveImportStep.PutKey -> {
                        val before = target.readStore(step.rawName)
                        val old = before?.keys?.get(step.key)
                        undo += if (old == null) {
                            ArchiveUndo.RemoveKey(step.rawName, step.key)
                        } else {
                            ArchiveUndo.RestoreKey(step.rawName, step.key, old, before?.writtenAt?.get(step.key))
                        }
                        target.putKey(step.rawName, step.key, step.value, step.at)
                    }
                    is ArchiveImportStep.RemoveKey -> {
                        val before = target.readStore(step.rawName)
                        val old = before?.keys?.get(step.key)
                        if (target.removeKey(step.rawName, step.key)) {
                            keysDeleted++
                            if (old != null) {
                                undo += ArchiveUndo.RestoreKey(
                                    step.rawName,
                                    step.key,
                                    old,
                                    before?.writtenAt?.get(step.key),
                                )
                            }
                        } else {
                            // 这一端不支持删 / 没删成 ⇒ 保守：当「本端保留」。
                            keysKept++
                        }
                    }
                    is ArchiveImportStep.PutFile -> {
                        val old = target.readFile(step.suffix, step.relPath)
                        undo += if (old == null) {
                            ArchiveUndo.RemoveFile(step.suffix, step.relPath)
                        } else {
                            ArchiveUndo.RestoreFile(step.suffix, step.relPath, old)
                        }
                        target.putFile(step.suffix, step.relPath, step.content)
                    }
                    is ArchiveImportStep.RemoveFile -> {
                        val old = target.readFile(step.suffix, step.relPath)
                        if (old != null && target.removeFile(step.suffix, step.relPath)) {
                            filesDeleted++
                            undo += ArchiveUndo.RestoreFile(step.suffix, step.relPath, old)
                        } else {
                            // 端内已经没有 / 删不掉 ⇒ 算什么都没动。
                            filesSkipped++
                        }
                    }
                }
            }.isSuccess

            if (landed) {
                when (step) {
                    is ArchiveImportStep.PutKey -> keysWritten++
                    is ArchiveImportStep.PutFile -> filesWritten++
                    is ArchiveImportStep.RemoveKey, is ArchiveImportStep.RemoveFile -> Unit
                }
                continue
            }
            if (!tolerant) {
                val unrestored = rollback(undo, target)
                val tail = if (unrestored == 0) {
                    "端内已整体回滚（与导入前逐字相同）"
                } else {
                    "端内回滚未完全：$unrestored 笔没恢复回去"
                }
                return ImportResult.Failed("${describe(step)}；$tail")
            }
            // 老口径（§8.6）：这一笔没落地，端内没动 —— 计数上等于本端胜。
            when (step) {
                is ArchiveImportStep.PutKey -> keysKept++
                is ArchiveImportStep.PutFile -> filesSkipped++
                is ArchiveImportStep.RemoveKey, is ArchiveImportStep.RemoveFile -> Unit
            }
        }

        return ImportResult.Ok(
            keysWritten = keysWritten,
            keysKept = keysKept,
            filesWritten = filesWritten,
            filesSkipped = filesSkipped,
            keysDeleted = keysDeleted,
            filesDeleted = filesDeleted,
        )
    }

    /** 把一份档案并进端内（§6.4）；默认档 [ArchiveImportMode.MERGE]，口径与以前逐字相同。 */
    fun import(
        source: ArchiveSource,
        target: ArchiveImportTarget,
        mode: ArchiveImportMode = ArchiveImportMode.MERGE,
    ): ImportResult = when (val planned = plan(source, target, mode)) {
        is ArchivePlanOutcome.Failed -> ImportResult.Failed(planned.reason)
        is ArchivePlanOutcome.Ok -> apply(planned.plan, target)
    }

    /**
     * 一条 `files/` 条目的落地计划：① 键名归一（档案名 → 本端名）→ ② §6.4 文件层的仲裁
     * （默认档：本端有 ⇒ 保持不变；镜像档：本端有就看字节是否相同）→ ③ 形状归一 → ④ 记一笔。
     *
     * ①②③ 任一步拼不出来 ⇒ [FileLanding.Skipped]（调用方计数，不抛）。
     */
    private fun landFile(
        suffix: String,
        accountId: String?,
        relPath: String,
        archivePath: String,
        source: ArchiveSource,
        target: ArchiveImportTarget,
        existingWins: Boolean,
    ): FileLanding {
        val localRelPath = target.fileRelPath(suffix, accountId, relPath) ?: return FileLanding.Skipped
        val existing = target.readFile(suffix, localRelPath)
        // 默认档：本端已经有这一条 ⇒ 保持不变（本端胜），连档案那几字节都不用读。
        if (existing != null && existingWins) return FileLanding.Present(suffix, localRelPath)
        val content = source.readFile(archivePath) ?: return FileLanding.Skipped
        val localContent = target.fileContent(suffix, accountId, relPath, content) ?: return FileLanding.Skipped
        if (existing == null) return FileLanding.Write(suffix, localRelPath, localContent, overwrite = false)
        if (existing == localContent) return FileLanding.Present(suffix, localRelPath)
        return FileLanding.Write(suffix, localRelPath, localContent, overwrite = true)
    }

    /** 一条 `files/` 条目的落地计划（只影响计数与 [ArchiveImportStep]）。 */
    private sealed interface FileLanding {

        /** 要写这一条（[overwrite] = 本端已有、镜像档按档案改写）。 */
        data class Write(
            val suffix: String,
            val relPath: String,
            val content: String,
            val overwrite: Boolean,
        ) : FileLanding

        /** 本端已经有这一条，按当前档位不动它。 */
        data class Present(val suffix: String, val relPath: String) : FileLanding

        /** 拼不出端内名 / 读不出来 / 转不动形状。 */
        data object Skipped : FileLanding
    }

    /** 回滚：反向重放记下来的那些「原来是什么」；返回没恢复回去的笔数（0 = 与 apply 之前逐字相同）。 */
    private fun rollback(undo: List<ArchiveUndo>, target: ArchiveImportTarget): Int {
        var unrestored = 0
        undo.asReversed().forEach { op ->
            val restored = runCatching {
                when (op) {
                    is ArchiveUndo.RestoreKey -> {
                        target.putKey(op.rawName, op.key, op.value, op.at)
                        true
                    }
                    is ArchiveUndo.RemoveKey ->
                        target.removeKey(op.rawName, op.key) ||
                            target.readStore(op.rawName)?.keys?.containsKey(op.key) != true
                    is ArchiveUndo.RestoreFile -> {
                        target.putFile(op.suffix, op.relPath, op.content)
                        true
                    }
                    is ArchiveUndo.RemoveFile ->
                        target.removeFile(op.suffix, op.relPath) ||
                            target.readFile(op.suffix, op.relPath) == null
                }
            }.getOrDefault(false)
            if (!restored) unrestored++
        }
        return unrestored
    }

    /** 失败那一笔的说明（回滚报告要指出是哪一笔不顺）。 */
    private fun describe(step: ArchiveImportStep): String = when (step) {
        is ArchiveImportStep.PutKey -> "写 ${step.rawName} 的 ${step.key} 时不顺"
        is ArchiveImportStep.RemoveKey -> "删 ${step.rawName} 的 ${step.key} 时不顺"
        is ArchiveImportStep.PutFile -> "写 ${step.relPath} 时不顺"
        is ArchiveImportStep.RemoveFile -> "删 ${step.relPath} 时不顺"
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
        /** 本端胜 / 档案里没有的键数；落地时被吞掉的那条也算在这里 —— 端内没动。 */
        val keysKept: Int,
        /** 写进端内的 `files/` 条目数。 */
        val filesWritten: Int,
        /**
         * 拼不出端内名 / 转不动形状 / 读不出来的 `files/` 条目数（§6.3 逐族迁移的残尾）。
         *
         * 它们**一条一笔都没写**，整次导入照旧跑完（§8.6：绝不抛）；本端已经有那条的不算在这里（那是本端胜）。
         */
        val filesSkipped: Int = 0,
        /**
         * 镜像档删掉的本端键数（默认档恒为 0：它一个键都不删）。
         *
         * 与 [keysWritten] 不同时成立为怪：镜像档可以只删不写，也可以只写不删。
         */
        val keysDeleted: Int = 0,
        /** 镜像档删掉的本端 `files/` 条目数（默认档恒为 0）。 */
        val filesDeleted: Int = 0,
    ) : ImportResult

    /** 明确失败；端内一笔都没写。 */
    data class Failed(val reason: String) : ImportResult
}
